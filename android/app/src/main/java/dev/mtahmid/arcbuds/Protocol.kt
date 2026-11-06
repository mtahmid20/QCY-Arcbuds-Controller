/** QCY packet framing: [0xFF, body_len, (cmd, param_len, params...)*]. */
package dev.mtahmid.arcbuds

object Protocol {
    const val SOF = 0xFF

    const val SERVICE_UUID = "0000a001-0000-1000-8000-00805f9b34fb"
    const val CMD_WRITE_UUID = "00001001-0000-1000-8000-00805f9b34fb"
    const val CMD_NOTIFY_UUID = "00001002-0000-1000-8000-00805f9b34fb"

    // direct-write characteristics (no 0xFF framing)
    const val UUID_VERSION = "00000007-0000-1000-8000-00805f9b34fb"
    const val UUID_BATTERY = "00000008-0000-1000-8000-00805f9b34fb"

    /** QCY earphones in the Bluetooth SIG company-id space. */
    const val QCY_COMPANY_ID = 0x521C

    // command ids
    const val CMD_RESET_DEFAULT = 0x01
    const val CMD_CLEAR_PAIRING = 0x02
    const val CMD_FACTORY_RESET = 0x03
    const val CMD_MUSIC_CONTROL = 0x04
    const val CMD_IN_EAR = 0x06
    const val CMD_VOLUME = 0x08
    const val CMD_LOW_LATENCY = 0x09
    const val CMD_NOISE_CANCEL = 0x0C
    const val CMD_POWER_OFF_TIMER = 0x14
    const val CMD_BALANCE = 0x16
    const val CMD_RENAME = 0x18
    const val CMD_EQ_V1 = 0x20
    const val CMD_EQ_V2 = 0x22
    const val CMD_LDAC = 0x23
    const val CMD_ADAPTIVE_EQ = 0x27
    const val CMD_SPATIAL = 0x2D
    const val CMD_BATTERY = 0x2F
    const val CMD_VERSION = 0x30
    const val CMD_REQUEST = 0xFE

    // HT07 ANC readbacks are "startcmdid" values, not 0..3 (verified live on hardware)
    const val ANC_OFF = 2
    const val ANC_INDOOR_1 = 65
    const val ANC_INDOOR_3 = 67
    const val ANC_TRANSPARENCY = 164

    class Command(val cmd: Int, val params: ByteArray)

    fun encode(cmd: Int, params: ByteArray): ByteArray =
        byteArrayOf(cmd.toByte(), params.size.toByte()) + params

    /** Frame one command. Refuses packets that would not fit a single BLE write. */
    fun buildOne(cmd: Int, params: ByteArray = ByteArray(0)): ByteArray {
        val body = encode(cmd, params)
        require(body.size <= 254) { "body too long: ${body.size} bytes" }
        return byteArrayOf(SOF.toByte(), body.size.toByte()) + body
    }

    /** Parse a notification payload into commands. Tolerates junk/garbage. */
    fun parse(payload: ByteArray): List<Command> {
        val out = ArrayList<Command>()
        var i = 0
        val n = payload.size
        while (i < n) {
            if (payload[i].toInt() and 0xFF != SOF) { // find next start-of-frame
                i++
                continue
            }
            if (i + 1 >= n) break
            val bodyLen = payload[i + 1].toInt() and 0xFF
            val bodyEnd = (i + 2 + bodyLen).coerceAtMost(n)
            var j = i + 2
            while (j + 1 < bodyEnd) {
                val cmd = payload[j].toInt() and 0xFF
                val plen = payload[j + 1].toInt() and 0xFF
                val pEnd = (j + 2 + plen).coerceAtMost(n)
                out.add(Command(cmd, payload.copyOfRange(j + 2, pEnd)))
                j = j + 2 + plen
            }
            i = i + 2 + bodyLen
        }
        return out
    }

    fun hex(b: ByteArray): String = b.joinToString(" ") { "%02x".format(it) }
}
