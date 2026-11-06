package dev.mtahmid.arcbuds

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import java.util.ArrayDeque
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : AppCompatActivity(), QcyDevice.Listener {

    private val adapter: BluetoothAdapter? by lazy {
        (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    private var dev: QcyDevice? = null
    private var scanJob: Job? = null

    /** One merged EQ band (L/R averaged on read, as in the web UI). */
    private class Band(var freq: Int, var gain: Float, var q: Float)

    private var eqBands: MutableList<Band> = mutableListOf()
    private var eqMaster = 0f

    private val logLines = ArrayDeque<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        wireUi()
        gatePermissions()
        log("ArcBuds — native BLE, no vendor SDK")
    }

    // -------------------------------------------------------------------- UI

    // ---------------- Vista lookups: v<TextView>(R.id.x) for typed, v(R.id.x) for View
    @JvmName("vPlain") // distinct JVM name avoids the erasure clash with the reified overload
    private fun v(id: Int): View = findViewById(id)

    private fun wireUi() {
        v(R.id.btnConnect).setOnClickListener { onConnect() }
        v(R.id.btnDisconnect).setOnClickListener { onDisconnect() }
        v(R.id.btnRefresh).setOnClickListener { lifecycleScope.launch { refresh() } }

        v(R.id.btnAncNormal).setOnClickListener { setAnc(Protocol.ANC_OFF, "normal") }
        v(R.id.btnAncOn).setOnClickListener {
            val level = (v<SeekBar>(R.id.seekAncStrength).progress.coerceAtLeast(1))
            setAnc(64 + level.coerceAtMost(3), "ANC level $level")
        }
        v(R.id.btnAncTr).setOnClickListener { setAnc(Protocol.ANC_TRANSPARENCY, "transparency") }

        v<SeekBar>(R.id.seekAncStrength).hook { level ->
            v<TextView>(R.id.tvAncStrength).text =
                if (level == 0) "ANC strength: off" else "ANC strength: level $level"
            if (level > 0) setAnc(64 + level, "ANC L$level")
        }

        val game = v<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.swGame)
        game.setOnCheckedChangeListener { _, checked ->
            if (!v(R.id.swGame).isEnabled) return@setOnCheckedChangeListener
            lifecycleScope.launch { dev?.send(Protocol.CMD_LOW_LATENCY, byteArrayOf(if (checked) 1 else 2)) }
        }

        v<SeekBar>(R.id.seekVol).hook { value ->
            v<TextView>(R.id.tvVol).text = "Volume: $value/${v<SeekBar>(R.id.seekVol).max}"
            lifecycleScope.launch { dev?.send(Protocol.CMD_VOLUME, byteArrayOf(value.toByte(), value.toByte(), 0)) }
        }
        v<SeekBar>(R.id.seekBal).hook { value ->
            v<TextView>(R.id.tvBal).text = balanceLabel(value)
            lifecycleScope.launch { dev?.send(Protocol.CMD_BALANCE, byteArrayOf(value.toByte())) }
        }

        v(R.id.btnRename).setOnClickListener {
            val name = v<TextView>(R.id.etName).text.toString().trim()
            if (name.isEmpty()) return@setOnClickListener
            lifecycleScope.launch { dev?.send(Protocol.CMD_RENAME, name.toByteArray(Charsets.UTF_8)) }
            log("rename -> \"$name\"")
        }

        val spinner = v<Spinner>(R.id.spinAutoOff)
        spinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            arrayOf("Auto-off: never", "Auto-off: 30 min", "Auto-off: 1 h", "Auto-off: 2 h", "Auto-off: 3 h"),
        )
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?, view: View?, pos: Int, id: Long,
            ) {
                // first programmatic set during onCreate must not send a command
                if (!spinner.isEnabled || !uiReady) return
                val minutes = intArrayOf(0, 30, 60, 120, 180)[pos]
                val mins = if (minutes == 0) 0xFFFF else minutes
                lifecycleScope.launch {
                    dev?.send(
                        Protocol.CMD_POWER_OFF_TIMER,
                        byteArrayOf(
                            (mins and 0xFF).toByte(),
                            ((mins shr 8) and 0xFF).toByte(), 0, 0,
                        ),
                    )
                    log("auto-off -> ${if (minutes == 0) "never (0xFFFF)" else "$minutes min"}")
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        v(R.id.btnReset).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Reset settings")
                .setMessage("Reset all settings to defaults? Pairing is kept.")
                .setPositiveButton("Reset") { _, _ ->
                    lifecycleScope.launch { dev?.send(Protocol.CMD_RESET_DEFAULT) }
                    log("settings reset (0x01)")
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        v(R.id.btnEqRead).setOnClickListener { lifecycleScope.launch { readEq() } }
        v(R.id.btnEqWrite).setOnClickListener { lifecycleScope.launch { applyEq() } }
        v(R.id.btnEqFlat).setOnClickListener {
            eqBands.forEach { it.gain = 0f }
            rebuildEqUi()
        }
        v(R.id.btnEqBass).setOnClickListener {
            eqBands.forEach { it.gain = when { it.freq < 250 -> 4f; it.freq < 1000 -> 1f; else -> 0f } }
            rebuildEqUi()
        }

        v(R.id.btnHexSend).setOnClickListener {
            val raw = v<TextView>(R.id.etHex).text.toString().trim()
            if (raw.isEmpty()) return@setOnClickListener
            val bytes = raw.split(Regex("[\\s,]+")).mapNotNull { s ->
                s.removePrefix("0x").toIntOrNull(16)
            }
            if (bytes.isEmpty() || bytes.any { it !in 0..255 }) { toast("bad hex"); return@setOnClickListener }
            val frame = Protocol.buildOne(bytes[0], bytes.drop(1).map { it.toByte() }.toByteArray())
            log("tx  ${Protocol.hex(frame)}")
            lifecycleScope.launch { dev?.send(bytes[0], bytes.drop(1).map { it.toByte() }.toByteArray()) }
        }
    }

    private var uiReady = false

    private inline fun <reified T : View> v(id: Int): T = findViewById(id)

    private fun SeekBar.hook(block: (Int) -> Unit) {
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser && isEnabled) block(progress)
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })
    }

    // ------------------------------------------------------------ permissions

    private fun gatePermissions() {
        uiReady = true
        val wanted = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, grants: IntArray) {
        super.onRequestPermissionsResult(code, perms, grants)
        toast(if (grants.all { it == PackageManager.PERMISSION_GRANTED }) "permissions granted" else "Bluetooth permissions are required")
    }

    private fun has(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------------- connect

    private fun onConnect() {
        val bt = adapter
        when {
            bt == null -> { toast("no Bluetooth on this phone"); return }
            Build.VERSION.SDK_INT >= 31 && !has(Manifest.permission.BLUETOOTH_CONNECT) -> { gatePermissions(); return }
            bt.isEnabled != true -> { startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 2); return }
        }
        v(R.id.btnConnect).isEnabled = false
        v<TextView>(R.id.tvStatus).text = "scanning…"
        log("scanning for QCY advertisements (company 0x521C)…")
        scanJob?.cancel()
        scanJob = lifecycleScope.launch(Dispatchers.IO) {
            val device = scanForBuds()
            if (device == null) {
                runOnUi {
                    log("no QCY advertising device found in 12 s — take the buds out of the case and make sure they are not connected to another device")
                    v<TextView>(R.id.tvStatus).text = "nothing found"
                    v(R.id.btnConnect).isEnabled = true
                }
                return@launch
            }
            val name = try { device.name } catch (e: SecurityException) { null }
            runOnUi { log("found: ${name ?: "(unnamed)"} ${device.address}") }

            val q = QcyDevice(device.address)
            withContext(Dispatchers.Main.immediate) { dev = q; q.listener = this@MainActivity }
            if (!q.startConnect(this@MainActivity, device)) {
                runOnUi { v(R.id.btnConnect).isEnabled = true }
                return@launch
            }
            val linked = withTimeoutOrNull(20_000) { q.linked.await() }
            val ready = linked == true && q.resolveChars()
            if (!ready) {
                runOnUi {
                    log("could not reach the QCY command service — wrong device picked?")
                    v<TextView>(R.id.tvStatus).text = "connect failed"
                    lifecycleScope.launch(Dispatchers.IO) { q.disconnect() }
                    dev = null
                    v(R.id.btnConnect).isEnabled = true
                }
                return@launch
            }
            runOnUi {
                v<TextView>(R.id.tvStatus).text = name ?: "connected"
                v<TextView>(R.id.tvModel).text = ""
                setControlsEnabled(true)
                if (name != null) v<TextView>(R.id.etName).setText(name)
            }
            refresh()
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun scanForBuds(): BluetoothDevice? {
        val bt = adapter ?: return null
        val scanner = try { bt.bluetoothLeScanner } catch (e: SecurityException) { return null } ?: return null
        // Match on the QCY company id (0x521C) — the same signal tools/scan.py keys
        // on. No name filter: the buds advertise with odd/absent names.
        val filter = ScanFilter.Builder()
            .setManufacturerData(Protocol.QCY_COMPANY_ID, ByteArray(0))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        var found: BluetoothDevice? = null
        val latch = CompletableDeferred<BluetoothDevice?>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (found != null) return
                found = result.device
                latch.complete(found)
            }
            override fun onScanFailed(errorCode: Int) {
                log("scan failed: $errorCode")
                latch.complete(null)
            }
        }
        try { scanner.startScan(listOf(filter), settings, cb) } catch (e: SecurityException) {
            log("scan denied: ${e.message}")
            return null
        }
        val out = try { withTimeoutOrNull(12_000) { latch.await() } } finally {
            try { scanner.stopScan(cb) } catch (e: Exception) { /* already stopped */ }
        }
        return out
    }

    private fun onDisconnect() {
        val d = dev
        dev = null
        scanJob?.cancel()
        lifecycleScope.launch(Dispatchers.IO) { d?.disconnect() }
        onLinkState(false, explicit = true)
        v<TextView>(R.id.tvStatus).text = "Disconnected"
    }

    // ------------------------------------------------------------------ packets

    override fun onPacket(cmd: Protocol.Command) {
        when (cmd.cmd) {
            Protocol.CMD_BATTERY -> runOnUi { renderBattery(cmd.params) }
            Protocol.CMD_NOISE_CANCEL -> runOnUi { if (cmd.params.isNotEmpty()) renderAnc(cmd.params[0].toInt() and 0xFF) }
        }
    }

    private fun onLinkState(connected: Boolean, explicit: Boolean = false) {
        runOnUi {
            if (!connected && !explicit) {
                log("link lost")
                dev = null
                setControlsEnabled(false)
                v<TextView>(R.id.tvStatus).text = "Disconnected (link lost)"
            }
        }
    }

    override fun onLinkState(connected: Boolean) = onLinkState(connected, explicit = dev == null)

    override fun onLog(line: String) = runOnUi { log(line) }

    private fun renderBattery(p: ByteArray) {
        if (p.size < 3) return
        renderBat(v<TextView>(R.id.batL), p[0].toInt() and 0xFF, isCase = false)
        renderBat(v<TextView>(R.id.batR), p[1].toInt() and 0xFF, isCase = false)
        renderBat(v<TextView>(R.id.batC), p[2].toInt() and 0xFF, isCase = true)
    }

    private fun renderBat(tv: TextView, raw: Int, isCase: Boolean) {
        val level = raw and 0x7F
        val charging = raw and 0x80 != 0
        tv.text = when {
            isCase && level == 0 && !charging -> "—"
            else -> "$level" + (if (charging) "+" else "") + "%"
        }
    }

    private fun renderAnc(mode: Int) {
        fun dim(id: Int, on: Boolean) { v<View>(id).alpha = if (on) 1f else 0.5f }
        dim(R.id.btnAncNormal, mode == Protocol.ANC_OFF)
        dim(R.id.btnAncOn, mode in 65..113)
        dim(R.id.btnAncTr, mode in 160..166)
        val seek = v<SeekBar>(R.id.seekAncStrength)
        val tv = v<TextView>(R.id.tvAncStrength)
        when {
            mode in 65..67 -> { seek.progress = mode - 64; tv.text = "ANC strength: level ${mode - 64}" }
            mode in 160..166 -> tv.text = "ANC strength: transparency"
            else -> { seek.progress = 0; tv.text = "ANC strength: off" }
        }
    }

    private fun setAnc(mode: Int, label: String) {
        lifecycleScope.launch { dev?.send(Protocol.CMD_NOISE_CANCEL, byteArrayOf(mode.toByte())) }
        log("anc -> $label (0x0C $mode)")
        renderAnc(mode)
    }

    private fun setControlsEnabled(on: Boolean) {
        listOf(
            R.id.btnDisconnect, R.id.btnRefresh, R.id.btnAncNormal, R.id.btnAncOn, R.id.btnAncTr,
            R.id.seekAncStrength, R.id.seekVol, R.id.seekBal, R.id.swGame,
            R.id.btnEqRead, R.id.btnEqFlat, R.id.btnEqBass,
            R.id.etName, R.id.btnRename, R.id.btnReset, R.id.etHex, R.id.btnHexSend,
        ).forEach { v<View>(it).isEnabled = on }
        v<Spinner>(R.id.spinAutoOff).isEnabled = on
        v(R.id.btnEqWrite).isEnabled = on && eqBands.isNotEmpty()
        v(R.id.btnConnect).isEnabled = !on
    }

    private fun balanceLabel(value: Int) = when (value) {
        50 -> "Balance: center"
        else -> if (value < 50) "Balance: L ${100 - value * 2}%" else "Balance: R ${value * 2 - 100}%"
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

    private fun runOnUi(block: () -> Unit) { lifecycleScope.launch(Dispatchers.Main) { block() } }

    private fun log(line: String) {
        val at = DateFormat.format("HH:mm:ss", java.util.Date())
        // mutating the deque happens under lock, because callers reach this
        // from binder/IO threads too — only view updates are posted to main
        val text = synchronized(logLines) {
            logLines.addLast("[$at] $line")
            while (logLines.size > 300) logLines.removeFirst()
            logLines.joinToString("\n")
        }
        runOnUi {
            v<TextView>(R.id.tvLog).text = text
            v<android.widget.ScrollView>(R.id.logScroll).fullScroll(View.FOCUS_DOWN)
        }
    }

    // ----------------------------------------------------------------- refresh

    private suspend fun refresh() {
        val q = dev ?: return
        try {
            // gather responses on the calling (IO) thread…
            val battery = request(Protocol.CMD_BATTERY)
            val version = request(Protocol.CMD_VERSION)
            val anc = request(Protocol.CMD_NOISE_CANCEL)
            val vol = request(Protocol.CMD_VOLUME)
            val bal = request(Protocol.CMD_BALANCE)
            // Per the product DB this firmware answers only Game (verified live);
            // the other toggles time out and are shown dimmed instead.
            val game = request(Protocol.CMD_LOW_LATENCY, 900)
            // …then apply every UI change on the main thread.
            runOnUi {
                battery?.let { renderBattery(it.params) }
                version?.let { p ->
                    v<TextView>(R.id.tvVersion).text = "firmware ${versionText(p.params)}"
                }
                anc?.let { if (it.params.isNotEmpty()) renderAnc(it.params[0].toInt() and 0xFF) }
                vol?.let { volPkt ->
                    if (volPkt.params.size >= 2) {
                        val max = (volPkt.params.getOrNull(2)?.toInt() ?: 0xFF).coerceIn(50, 0xFF)
                        val sb = v<SeekBar>(R.id.seekVol)
                        sb.max = max
                        sb.progress = volPkt.params[0].toInt() and 0xFF
                        v<TextView>(R.id.tvVol).text = "Volume: ${volPkt.params[0].toInt() and 0xFF}/$max"
                    }
                }
                bal?.let { b ->
                    if (b.params.isNotEmpty()) {
                        val value = b.params[0].toInt() and 0xFF
                        v<SeekBar>(R.id.seekBal).progress = value
                        v<TextView>(R.id.tvBal).text = balanceLabel(value)
                    }
                }
                game?.let { g ->
                    v<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.swGame).isChecked =
                        g.params.isNotEmpty() && g.params[0].toInt() == 1
                }
            }
            readEq()
        } catch (e: Exception) {
            log("refresh: ${e.message}")
        }
    }

    private fun versionText(p: ByteArray): String {
        fun part(from: Int): String = if (from + 3 > p.size) "" else "${p[from]}.${p[from + 1]}.${p[from + 2]}"
        val a = part(0)
        val b = if (p.size >= 6) part(3) else ""
        return listOf(a, b).filter { it.isNotEmpty() }.joinToString(" / ")
    }

    // ---------------------------------------------------------------------- EQ

    private suspend fun request(cmd: Int, timeoutMs: Long = 1600): Protocol.Command? =
        dev?.request(cmd, timeoutMs)

    private suspend fun readEq() {
        val pkt = request(Protocol.CMD_EQ_V1, 2000)
        if (pkt == null || pkt.params.size < 3) { log("no EQ reply from device"); return }
        val p = pkt.params
        val bands = ArrayList<Band>()
        var i = 3
        while (i + 6 <= p.size && bands.size < 20) {
            val freq = (p[i].toInt() and 0xFF) or ((p[i + 1].toInt() and 0xFF) shl 8)
            val gainRaw = (p[i + 2].toInt() and 0xFF) or ((p[i + 3].toInt() and 0xFF) shl 8)
            val gain = (if (gainRaw > 0x7FFF) gainRaw - 0x10000 else gainRaw) / 100f
            val qRaw = (p[i + 4].toInt() and 0xFF) or ((p[i + 5].toInt() and 0xFF) shl 8)
            bands.add(Band(freq, gain, qRaw / 100f))
            i += 6
        }
        val mgRaw = (p[1].toInt() and 0xFF) or ((p[2].toInt() and 0xFF) shl 8)
        eqMaster = (if (mgRaw > 0x7FFF) mgRaw - 0x10000 else mgRaw) / 100f
        val merged = if (bands.size > 10) {
            bands.take(10).mapIndexed { idx, b -> Band(b.freq, (b.gain + bands[idx + 10].gain) / 2f, b.q) }
        } else {
            bands
        }
        // override garbage freqs with the known HT07 centres
        val CENTRES = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
        merged.forEachIndexed { idx, band ->
            if ((band.freq > 20000 || band.freq == 0) && idx < CENTRES.size) band.freq = CENTRES[idx]
        }
        eqBands = merged.toMutableList()
        runOnUi { rebuildEqUi() }
        log("eq read (0x20, type ${p[0]})")
    }

    private fun rebuildEqUi() {
        val holder = v<android.widget.LinearLayout>(R.id.eqList)
        holder.removeAllViews()
        val meta = "10 bands · master " + (if (eqMaster >= 0) "+" else "") +
            String.format("%.1f", eqMaster) + " dB"
        v<TextView>(R.id.tvEqMeta).text = meta

        for (band in eqBands) addEqRow(holder, "${if (band.freq >= 1000) "${band.freq / 1000}k" else "${band.freq}"} Hz", band.gain) {
            band.gain = it
        }
        addEqRow(holder, "master", eqMaster) { eqMaster = it }
        v(R.id.btnEqWrite).isEnabled = eqBands.isNotEmpty() && dev != null
    }

    private fun addEqRow(holder: android.widget.LinearLayout, label: String, initial: Float, set: (Float) -> Unit) {
        val row = layoutInflater.inflate(R.layout.eq_band_row, holder, false)
        (row.findViewById<TextView>(R.id.txFreq)).text = label
        val seek = row.findViewById<SeekBar>(R.id.xGain)
        val out = row.findViewById<TextView>(R.id.tGain)
        fun refresh() {
            val value = (seek.progress - 120) / 10f
            out.text = (if (value >= 0) "+" else "") + String.format("%.1f", value) + " dB"
        }
        seek.progress = Math.round(initial * 10) + 120
        refresh()
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) { set((progress - 120) / 10f); refresh() }
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })
        holder.addView(row)
    }

    private suspend fun applyEq() {
        val q = dev ?: return
        if (eqBands.isEmpty()) return
        // HT07 verified path: v1 (0x20), 6 bytes per band, 10 bands mono.
        // Writing v2 first corrupted the profile on real hardware.
        val payload = ArrayList<Byte>()
        payload.add(1) // custom preset index; 0 is reserved in the product DB
        val mg = Math.round(eqMaster * 100)
        payload.add((mg and 0xFF).toByte()); payload.add(((mg shr 8) and 0xFF).toByte())
        for (band in eqBands.take(10)) {
            val g = Math.round(band.gain.coerceIn(-8f, 8f) * 100).let {
                if (it < 0) it + 0x10000 else it
            }
            val fq = band.freq
            val qv = Math.round(band.q * 100)
            payload.add((fq and 0xFF).toByte()); payload.add(((fq shr 8) and 0xFF).toByte())
            payload.add((g and 0xFF).toByte()); payload.add(((g shr 8) and 0xFF).toByte())
            payload.add((qv and 0xFF).toByte()); payload.add(((qv shr 8) and 0xFF).toByte())
        }
        q.send(Protocol.CMD_EQ_V1, payload.toByteArray())
        log("eq written via 0x20 (v1, 10 bands)")
    }
}
