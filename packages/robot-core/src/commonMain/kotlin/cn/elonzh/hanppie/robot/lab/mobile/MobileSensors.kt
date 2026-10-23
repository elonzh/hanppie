package cn.elonzh.hanppie.robot.lab.mobile

/** The mobile_ctrl wire types are not their subscription bits. GPS has no recovered consumer. */
enum class MobileSensor(val bit: Int, val wireType: Int) {
    ACCELERATION(1, 1), ATTITUDE(2, 2), ROTATION_RATE(4, 3),
}

/** Device axes: x right, y toward the top, z out of the screen; no display rotation compensation.
 * Acceleration includes gravity in g; attitude is quaternion Z-X-Y Euler degrees; rate is rad/s.
 * [stamp] is the platform's monotonically increasing sample identity, not a robot timestamp.
 */
data class MobileSensorReading(val sensor: MobileSensor, val x: Float, val y: Float, val z: Float,
    val stamp: Long, val ageMillis: Long)

/** Only the physical sampling belongs to a platform. Calls are serialized by the session lock. */
interface MobileSensorSource {
    val availableMask: Int
    fun start(mask: Int)
    fun read(): List<MobileSensorReading>
    fun stop()
}

/** Desktop has no supported motion source; it must never fabricate stationary readings. */
object NoMobileSensors : MobileSensorSource {
    override val availableMask = 0
    override fun start(mask: Int) = Unit
    override fun read() = emptyList<MobileSensorReading>()
    override fun stop() = Unit
}
