package cn.elonzh.hanppie.robot.protocol

fun String.hexBytes(): ByteArray {
    require(length % 2 == 0) { "Invalid hex length" }
    return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
fun ByteArray.hex(): String = joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
internal fun ByteArray.u8(index: Int) = this[index].toInt() and 255
internal fun ByteArray.u16(index: Int) = u8(index) or (u8(index + 1) shl 8)
internal fun ByteArray.put16(index: Int, value: Int) {
    this[index] = value.toByte()
    this[index + 1] = (value ushr 8).toByte()
}

/** App wire format, ported from the packet-tested Hanppie Python implementation. */
object Protocol {
    // Network ports and defaults
    const val DEFAULT_ROBOT_IP = "192.168.2.1"
    const val APP_PORT = 45678
    const val ROBOT_APP_PORT = 56789
    const val LOCAL_CONTROL_PORT = 10609
    const val ROBOT_CONTROL_PORT = 10607
    const val ROBOT_FTP_PORT = 21

    // DUSS frame header & CRC
    const val DUSS_MAGIC = 0x55
    const val CRC8_INIT = 0x77
    const val CRC8_POLY = 0x8c
    const val CRC16_INIT = 0x3692
    const val CRC16_POLY = 0x8408

    // Discovery beacon
    const val BROADCAST_MAGIC_0 = 0x5a
    const val BROADCAST_MAGIC_1 = 0x5b

    // DUSS packet attributes (attr)
    const val ATTR_NO_ACK = 0x00
    const val ATTR_NEED_ACK_NO_FINISH = 0x20
    const val ATTR_NEED_ACK = 0x40
    const val ATTR_ACK = 0x80
    const val ATTR_RESP_NEED_ACK = 0xc0

    // Host / Node module addresses: host2byte(type, index)
    const val HOST_CAMERA = 0x01          // host=1, index=0 (camera_id = 100)
    const val HOST_MOBILE = 0x02          // host=2, index=0 (mobile_id = 200)
    const val HOST_CHASSIS_CAN = 0x03     // host=3, index=0
    const val HOST_GIMBAL = 0x04          // host=4, index=0 (gimbal_id = 400)
    const val HOST_WIFI = 0x07            // host=7, index=0 (wifi_id = 700)
    const val HOST_HDVT_UAV = 0x09        // host=9, index=0 (hdvt_uav_id = 900)
    const val HOST_GUN = 0x17             // host=23, index=0 (gun_id = 2300)
    const val HOST_SYSTEM = 0x28          // host=8, index=1 (system_id = 801)
    const val HOST_SCRATCH_CLIENT = 0x42  // host=2, index=2
    const val HOST_SCRATCH_SYS = 0xa9     // host=9, index=5 (scratch_sys_id = 905)
    const val HOST_CHASSIS = 0xc3         // host=3, index=6 (chassis_id = 306)
    const val HOST_SCRATCH_SCRIPT = 0xc9  // host=9, index=6 (scratch_script_id = 906)
    const val HOST_VISION = 0xf1          // host=17, index=7 (vision_id = 1707)

    // Command sets (cmdset)
    const val CMDSET_COMMON = 0x00
    const val CMDSET_SPECIAL = 0x01
    const val CMDSET_CAMERA = 0x02
    const val CMDSET_FC = 0x03
    const val CMDSET_GIMBAL = 0x04
    const val CMDSET_WIFI = 0x07
    const val CMDSET_DM368 = 0x08
    const val CMDSET_HDVT = 0x09
    const val CMDSET_VISION = 0x0a
    const val CMDSET_RM = 0x3f
    const val CMDSET_VIRTUAL_BUS = 0x48

    // Common command IDs (cmdset 0x00)
    const val CMD_GET_DEVICE_VERSION = 0x01
    const val CMD_FW_TRANSMIT = 0x09
    const val CMD_GET_CFG_FILE = 0x4f

    // Special command IDs (cmdset 0x01)
    const val CMD_SPECIAL_RM_CONTROL = 0x04

    // Camera command IDs (cmdset 0x02)
    const val CMD_SET_VIDEO_FORMAT = 0x18
    const val CMD_SET_ZOOM_PARAM = 0x34

    // Gimbal command IDs (cmdset 0x04)
    const val CMD_GIMBAL_EXT_CTRL_ACCEL = 0x0c

    // Wi-Fi command IDs (cmdset 0x07)
    const val CMD_WIFI_AP_PUSH_RSSI = 0x09
    const val CMD_WIFI_AP_KEEPALIVE = 0x17
    const val CMD_WIFI_AP_SET_COUNTRY_CODE = 0x30
    const val CMD_WIFI_GET_WORK_MODE = 0x39
    const val CMD_WIFI_CONFIG_BY_QRCODE = 0x3b

    // RM command IDs (cmdset 0x3f)
    const val CMD_RM_SPECIAL_CONTROL = 0x04
    const val CMD_RM_GAME_STATE_SYNC = 0x09
    const val CMD_RM_GAMECTRL_CMD = 0x0a
    const val CMD_RM_MODULE_STATUS_PUSH = 0x12
    const val CMD_RM_WORK_MODE_SET = 0x19
    const val CMD_RM_AUDIO_TO_APP = 0x1d
    const val CMD_RM_SET_AUDIO_STATUS = 0x1e
    const val CMD_RM_WHEEL_SPEED_SET = 0x20
    const val CMD_RM_SPEED_SET = 0x21
    const val CMD_RM_SPEED_MODE_SET = 0x28
    const val CMD_RM_LED_COLOR_SET = 0x33
    const val CMD_RM_EXIT_LOW_POWER_MODE = 0x4c
    const val CMD_RM_SHOOT_CMD = 0x51
    const val CMD_RM_GUN_LED_SET = 0x55
    const val CMD_RM_GET_SIGHT_BEAD_POSITION = 0x57
    const val CMD_RM_SYSTEM_FUNCTION_CONFIG = 0x59
    const val CMD_RM_SYSTEM_STATUS_CONFIG = 0x5b
    const val CMD_RM_AUDIO_TRANSFER = 0x5f
    const val CMD_RM_FC_RMC = 0x66
    const val CMD_RM_SAVE_PREF = 0x77
    const val CMD_RM_SCRIPT_DOWNLOAD_DATA = 0xa1
    const val CMD_RM_SCRIPT_DOWNLOAD_FINSH = 0xa2
    const val CMD_RM_SCRIPT_CTRL = 0xa3
    const val CMD_RM_SCRIPT_CUSTOM_INFO_PUSH = 0xa4
    const val CMD_RM_SCRIPT_BLOCK_STATUS_PUSH = 0xa5
    const val CMD_RM_SUB_MOBILE_INFO = 0xab
    const val CMD_RM_PLAY_SOUND_TASK = 0xb3
    const val CMD_RM_CUSTOM_UI_ATTRIBUTE_SET = 0xba
    const val CMD_RM_STREAM_CTRL = 0xd2
    const val CMD_RM_PRODUCT_ATTRIBUTE_GET = 0xfe

    // Virtual Bus command IDs (cmdset 0x48)
    const val CMD_VBUS_ADD_NODE = 0x01
    const val CMD_VBUS_NODE_RESET = 0x02
    const val CMD_VBUS_ADD_MSG = 0x03
    const val CMD_VBUS_DEL_MSG = 0x04
    const val CMD_VBUS_DATA_ANALYSIS = 0x08

    // Vision command IDs (cmdset 0x0a)
    const val CMD_VISION_CUSTOM = 0xa3

    // System mode strings
    const val MODE_NORMAL = "000300"
    const val MODE_LAB = "020302"
    const val MODE_REMOTE = "0b0300"
    const val MODE_EXIT_PREF = "010300"

    // SCRIPT_CTRL markers (cmd 0xa3)
    const val SCRIPT_CTRL_METADATA_FULL = 0x21
    const val SCRIPT_CTRL_METADATA_GUID = 0x2d
    const val SCRIPT_CTRL_START = 0x52
    const val SCRIPT_CTRL_STOP = 0x55

    // Lab game state sync parameters
    const val GAME_STATE_SYNC_LAB_PARAMS =
        "05000000ea03000000000000ef0300000a000000f003000000000000f1030000b80b0000f2030000dc0500000000000000000000"

    val neutral: ByteArray get() = "0000042000010840000210".hexBytes()

    fun crc(data: ByteArray, initial: Int, polynomial: Int): Int {
        var result = initial
        for (byte in data) {
            result = result xor (byte.toInt() and 255)
            repeat(8) { result = (result ushr 1) xor (if (result and 1 != 0) polynomial else 0) }
        }
        return result
    }

    fun duss(sender: Int = HOST_MOBILE, receiver: Int, attr: Int, set: Int, id: Int,
             payload: ByteArray = byteArrayOf(), sequence: Int): ByteArray {
        val size = 13 + payload.size
        require(size in 13..1023)
        return ByteArray(size).apply {
            this[0] = DUSS_MAGIC.toByte()
            this[1] = size.toByte()
            this[2] = (4 or (size ushr 8)).toByte()
            this[3] = crc(copyOfRange(0, 3), CRC8_INIT, CRC8_POLY).toByte()
            this[4] = sender.toByte(); this[5] = receiver.toByte()
            put16(6, sequence)
            this[8] = attr.toByte(); this[9] = set.toByte(); this[10] = id.toByte()
            payload.copyInto(this, 11)
            put16(size - 2, crc(copyOfRange(0, size - 2), CRC16_INIT, CRC16_POLY))
        }
    }

    fun frames(data: ByteArray): List<DussFrame> = buildList {
        var offset = 0
        while (offset <= data.size - 13) {
            if (data.u8(offset) != DUSS_MAGIC || data.u8(offset + 2) and 0xfc != 4) { offset++; continue }
            val length = data.u8(offset + 1) or ((data.u8(offset + 2) and 3) shl 8)
            if (length < 13 || offset + length > data.size) { offset++; continue }
            val raw = data.copyOfRange(offset, offset + length)
            val valid = crc(raw.copyOfRange(0, 3), CRC8_INIT, CRC8_POLY) == raw.u8(3) &&
                crc(raw.copyOfRange(0, length - 2), CRC16_INIT, CRC16_POLY) == raw.u16(length - 2)
            add(DussFrame(offset, raw.u8(4), raw.u8(5), raw.u16(6), raw.u8(8), raw.u8(9),
                raw.u8(10), raw.copyOfRange(11, length - 2), valid))
            offset += length
        }
    }

    fun broadcast(data: ByteArray): DiscoveredRobot? {
        if (data.size != 24) return null
        var key = 7
        val decoded = data.map { byte ->
            val value = (byte.toInt() xor key).toByte()
            key = ((key + 7) xor 178) and 255
            value
        }.toByteArray()
        if (decoded.u8(0) != BROADCAST_MAGIC_0 || decoded.u8(1) != BROADCAST_MAGIC_1) return null
        val appBytes = decoded.copyOfRange(16, 24)
        val appId = if (appBytes.all { it == 0.toByte() }) "00000000" else appBytes.decodeToString()
        if (!Regex("[0-9a-fA-F]{8}").matches(appId)) return null
        return DiscoveredRobot((6..9).joinToString(".") { decoded.u8(it).toString() },
            decoded.copyOfRange(10, 16).hex().chunked(2).joinToString(":"), appId.lowercase(),
            decoded.u8(2) and 1 != 0)
    }
}

data class DiscoveredRobot(val ip: String, val mac: String, val appId: String, val pairing: Boolean)
data class DussFrame(val offset: Int, val sender: Int, val receiver: Int, val sequence: Int,
                     val attr: Int, val set: Int, val id: Int, val payload: ByteArray, val valid: Boolean)

class AppEnvelope(var session: Int, private val initialTick: Int) {
    init { require(session in 1..65535) }
    private var directTick = (initialTick + 8) and 65535
    private var directReference = initialTick
    private var latestDirect = initialTick
    private var controlTick = (initialTick + 0xa8) and 65535
    private var controlReference = initialTick
    private var packetIndex = 1

    fun preconnect(): ByteArray =
        "3080dc6800000004d84664006400c005140000640064006400c005140000640014006400c00514000064000101040102".hexBytes().apply {
            put16(2, session); checksum(this); put16(8, initialTick)
        }

    private fun checksum(bytes: ByteArray) {
        bytes[7] = bytes.take(7).fold(0) { sum, byte -> sum xor (byte.toInt() and 255) }.toByte()
    }

    fun direct(duss: ByteArray, flags: ByteArray = byteArrayOf(0, 0)): ByteArray {
        require(flags.size == 2)
        val tick = directTick
        directTick = (tick + 8) and 65535
        latestDirect = tick
        return ByteArray(20 + duss.size).apply {
            this[0] = size.toByte(); this[1] = (0x80 or ((size ushr 8) and 3)).toByte()
            put16(2, session); put16(4, tick); this[6] = 5; checksum(this)
            put16(8, directReference); put16(10, tick)
            this[16] = (packetIndex++).toByte(); this[17] = 1
            flags.copyInto(this, 18); duss.copyInto(this, 20)
        }
    }

    fun control(duss: ByteArray): ByteArray {
        val tick = controlTick
        controlTick = (tick + 8) and 65535
        return ByteArray(34 + duss.size).apply {
            this[0] = size.toByte(); this[1] = 0x80.toByte()
            put16(2, session); this[6] = 4; checksum(this)
            put16(8, tick); put16(10, tick)
            put16(16, controlReference); put16(18, controlReference)
            put16(24, directReference); put16(26, latestDirect)
            put16(32, duss.size); duss.copyInto(this, 34)
        }
    }

    fun observe(data: ByteArray) {
        if (data.size < 12 || data.u16(2) != session) return
        if (data.size >= 28 && data.u16(4) == 0) {
            if (data.u16(24) != 0) directReference = data.u16(24)
            if (data.u16(16) != 0) controlReference = data.u16(16)
            if (data.size != 34 && Protocol.frames(data).any { it.valid && it.offset >= 34 } && data.u16(10) != 0)
                controlTick = data.u16(10)
        }
    }
}
