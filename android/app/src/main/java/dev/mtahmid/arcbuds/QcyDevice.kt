/** Native BLE client for the QCY command GATT service (HT07 / ArcBuds). */
package dev.mtahmid.arcbuds

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

class QcyDevice(val address: String) {

    interface Listener {
        fun onPacket(cmd: Protocol.Command)
        fun onLinkState(connected: Boolean)
        fun onLog(line: String)
    }

    var listener: Listener? = null

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private val writeLock = Any()

    // suspended request() calls wait here, fed by the BLE callback thread
    private val pending: ConcurrentHashMap<Int, MutableList<CompletableDeferred<Protocol.Command>>> =
        ConcurrentHashMap()

    /** Completes true once the link is up, big MTU negotiated, chars discovered. */
    val linked = CompletableDeferred<Boolean>()

    fun startConnect(context: Context, device: BluetoothDevice): Boolean {
        try {
            gatt = device.connectGatt(
                context, false, callback, BluetoothDevice.TRANSPORT_LE,
            )
            return gatt != null
        } catch (e: SecurityException) {
            Log.e("ArcBuds", "connectGatt", e)
            listener?.onLog("permission missing: ${e.message}")
            return false
        }
    }

    /**
     * Resolve the QCY characteristics once the link is up. Calling this before
     * [linked] completes or after a failure is invalid.
     */
    suspend fun resolveChars(): Boolean {
        val g = gatt ?: return false
        // HT07 exposes the command service TWICE (7033 and a001); prefer a001.
        val svc = g.services.firstOrNull { it.uuid == UUID.fromString(Protocol.SERVICE_UUID) }
            ?: g.services.firstOrNull { s ->
                s.characteristics.any { it.uuid == UUID.fromString(Protocol.CMD_WRITE_UUID) }
            }
        if (svc == null) {
            listener?.onLog("QCY command service not found — wrong device?")
            return false
        }
        val w = svc.characteristics.firstOrNull {
            it.uuid == UUID.fromString(Protocol.CMD_WRITE_UUID)
        } ?: return false.also { listener?.onLog("0x1001 write char missing") }
        val n = svc.characteristics.firstOrNull {
            it.uuid == UUID.fromString(Protocol.CMD_NOTIFY_UUID)
        } ?: return false.also { listener?.onLog("0x1002 notify char missing") }

        writeChar = w
        val cccd = n.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
            ?: return false.also { listener?.onLog("no CCCD on notify char") }
        val done = CompletableDeferred<Boolean>()
        descWrite = done
        g.setCharacteristicNotification(n, true)
        val ok = try {
            g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } catch (e: NoSuchMethodError) {
            @Suppress("DEPRECATION")
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            g.writeDescriptor(cccd)
        }
        if (!ok) return false.also { listener?.onLog("CCCD write rejected") }
        val descOk = withTimeoutOrNull(3000) { done.await() } ?: false
        if (descOk) listener?.onLog("notifications on (${svc.characteristics.size} chars in service)")
        return descOk
    }

    private var descWrite: CompletableDeferred<Boolean>? = null

    suspend fun disconnect() {
        failPending()
        val g = gatt
        writeChar = null
        gatt = null
        try {
            g?.close() // close() also tears down any live link for an app client
        } catch (e: SecurityException) {
            Log.e("ArcBuds", "close", e)
        }
        listener?.onLinkState(false)
    }

    // ------------------------------------------------------------- transport

    suspend fun send(cmd: Int, params: ByteArray = ByteArray(0)): Boolean {
        val g = gatt ?: return false
        val w = writeChar ?: return false
        val bytes = Protocol.buildOne(cmd, params)
        val ok = synchronized(writeLock) {
            listener?.onLog("tx  ${Protocol.hex(bytes)}")
            try {
                g.writeCharacteristic(w, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            } catch (e: NoSuchMethodError) {
                // pre-API33 devices take the legacy path
                @Suppress("DEPRECATION")
                try {
                    w.value = bytes
                    g.writeCharacteristic(w)
                } catch (e2: SecurityException) {
                    false
                }
            }
        }
        if (!ok) listener?.onLog("write rejected by stack")
        return ok
    }

    suspend fun request(cmd: Int, timeoutMs: Long = 1600): Protocol.Command? {
        val deferred = CompletableDeferred<Protocol.Command>()
        val list = pending.getOrPut(cmd) { mutableListOf() }
        synchronized(list) { list.add(deferred) }
        send(Protocol.CMD_REQUEST, byteArrayOf(cmd.toByte()))
        val got = withTimeoutOrNull(timeoutMs) { deferred.await() }
        synchronized(list) { list.remove(deferred) }
        return got?.takeIf { it.cmd == cmd }
    }

    private fun failPending() {
        for (list in pending.values) {
            synchronized(list) {
                for (d in list) d.complete(Protocol.Command(-1, ByteArray(0)))
                list.clear()
            }
        }
        descWrite?.complete(false)
    }

    // ------------------------------------------------------------------ GATT

    private fun process(payload: ByteArray) {
        listener?.onLog("rx  ${Protocol.hex(payload)}")
        for (cmd in Protocol.parse(payload)) {
            val list = pending[cmd.cmd]
            var served = false
            if (list != null) {
                synchronized(list) {
                    if (list.isNotEmpty()) {
                        list.removeAt(0).complete(cmd)
                        served = true
                    }
                }
            }
            if (!served) listener?.onPacket(cmd) // unsolicited: battery/ANC pushes
        }
    }

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    listener?.onLinkState(true)
                    listener?.onLog("connected — negotiating MTU…")
                    g.requestMtu(247) // EQ frames exceed the default 23-byte MTU
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    listener?.onLog("link down")
                    failPending()
                    linked.complete(false)
                    listener?.onLinkState(false)
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            listener?.onLog("mtu $mtu${if (status != 0) " (status $status)" else ""}")
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) linked.complete(false)
            else linked.complete(true)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int,
        ) {
            if (status != 0) listener?.onLog("write status $status")
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int,
        ) {
            descWrite?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        // API 33+ signature
        override fun onCharacteristicChanged(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray,
        ) {
            process(value)
        }

        // API 26-32 signature
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            g: BluetoothGatt, c: BluetoothGattCharacteristic,
        ) {
            val data = try {
                c.value
            } catch (e: SecurityException) {
                return
            } ?: return
            process(data)
        }
    }
}
