package cn.elonzh.hanppie.robot

import kotlin.math.roundToInt

object RemoteControl {
    data class Follow(val chassisYaw: Double, val gimbalYaw: Double)
    /** Only outward user input engages follow; no autonomous centering while idle. */
    fun follow(relativeYaw: Double?, requestedYaw: Double): Follow {
        require(requestedYaw.isFinite())
        if (relativeYaw == null || !relativeYaw.isFinite()) return Follow(0.0, 0.0)
        val outward = relativeYaw * requestedYaw > 0
        if (!outward) return Follow(0.0, requestedYaw)
        val angle = kotlin.math.abs(relativeYaw)
        val weight = ((angle - 60.0) / 30.0).coerceIn(0.0, 1.0)
        val chassis = (requestedYaw * 2 * weight).coerceIn(-60.0, 60.0)
        return Follow(chassis, if (angle >= 230.0) 0.0 else requestedYaw)
    }
    /** Camera forward/right to chassis forward/right; positive yaw is clockwise. */
    fun cameraVelocity(forward: Double, right: Double, yawDegrees: Double): Pair<Double, Double> {
        require(listOf(forward, right, yawDegrees).all { it.isFinite() })
        val angle = yawDegrees * kotlin.math.PI / 180
        return (forward * kotlin.math.cos(angle) - right * kotlin.math.sin(angle)) to
            (forward * kotlin.math.sin(angle) + right * kotlin.math.cos(angle))
    }
    fun velocity(x: Double, y: Double, z: Double): ByteArray {
        require(listOf(x,y,z).all { it.isFinite() })
        return listOf(x.coerceIn(-1.0,1.0),y.coerceIn(-1.0,1.0),z.coerceIn(-150.0,150.0)).flatMap { value ->
            val bits=value.toFloat().toBits(); List(4) { (bits ushr (it*8)).toByte() }
        }.toByteArray()
    }
    /** Recovered S1 rm_module.Gimbal.set_accel_ctrl: yaw/roll/pitch in 0.1 deg/s. */
    fun gimbalVelocity(pitch: Double, yaw: Double): ByteArray {
        require(pitch.isFinite() && yaw.isFinite())
        return ByteArray(7).apply {
            put16(0,(yaw.coerceIn(-120.0,120.0)*10).roundToInt())
            put16(4,(pitch.coerceIn(-120.0,120.0)*10).roundToInt())
            this[6]=0xdc.toByte()
        }
    }

    /** S1 armor LEDs through RM common LED command 0x3f:0x33. */
    fun led(red: Int, green: Int, blue: Int, enabled: Boolean = true): ByteArray {
        require(red in 0..255 && green in 0..255 && blue in 0..255)
        return byteArrayOf(
            0x3f, 0, 0, 0, // all armor LEDs
            0xff.toByte(), 0, // all subcomponents
            (if (enabled) 0x71 else 0x70).toByte(),
            red.toByte(), green.toByte(), blue.toByte(),
            0, // repeat count
            0xe8.toByte(), 0x03, // one-second on interval (unused for solid)
            0xe8.toByte(), 0x03, // one-second off interval (unused for solid)
        )
    }

    /** S1 firing-channel muzzle LED payload for RM common LED command 0x3f:0x33. */
    fun muzzleFireLed(enabled: Boolean): ByteArray =
        (if (enabled) "40000000ff0001ffffff6401000100" else "40000000ff0000ffffff6401000100").hexBytes()

    /** Visible S1 blaster LED payload for ProtoBlasterSetLed 0x3f:0x55. */
    fun blasterLed(enabled: Boolean): ByteArray =
        (if (enabled) "71ffffff0164006400" else "70ffffff0164006400").hexBytes()
}

object SpeakerAudio {
    const val chunkBytes = 960
    val playPayload = "0b040000010001000001".hexBytes()
    private val transferId = "00000100".hexBytes()

    fun start(packetCount: Int, totalBytes: Int): ByteArray {
        require(packetCount in 1..0xffff && totalBytes in 1..0xffff)
        return ByteArray(17).apply {
            this[0] = 0
            transferId.copyInto(this, 1)
            put16(5, packetCount)
            put16(7, totalBytes)
        }
    }

    fun block(payload: ByteArray, index: Int): ByteArray {
        require(payload.size <= chunkBytes && index in 0..0xffff)
        return ByteArray(7 + payload.size).apply {
            this[0] = (index ushr 8).toByte()
            this[1] = index.toByte()
            put16(5, payload.size)
            payload.copyInto(this, 7)
        }
    }
}

/** Incremental Annex-B NAL splitter; retains split start codes and bounds corrupt streams. */
class AnnexB {
    private var pending = byteArrayOf()
    fun reset() { pending = byteArrayOf() }
    fun accept(chunk: ByteArray): List<ByteArray> {
        if (pending.size + chunk.size > 2_000_000) reset()
        pending += chunk
        val starts = mutableListOf<Int>()
        var i = 0
        while (i + 3 <= pending.size) {
            if (pending[i] == 0.toByte() && pending[i+1] == 0.toByte()) {
                if (pending[i+2] == 1.toByte()) { starts.add(i); i += 3; continue }
                if (i+3 < pending.size && pending[i+2] == 0.toByte() && pending[i+3] == 1.toByte()) { starts.add(i); i += 4; continue }
            }
            i++
        }
        val result = starts.zipWithNext { a, b -> pending.copyOfRange(a, b) }
        if (starts.isNotEmpty()) pending = pending.copyOfRange(starts.last(), pending.size)
        return result
    }
}
