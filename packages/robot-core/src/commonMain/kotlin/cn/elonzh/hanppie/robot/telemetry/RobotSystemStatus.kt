package cn.elonzh.hanppie.robot.telemetry

/** One native system report. Metres/seconds are the fixed client's interpretation, pending hardware checks. */
data class RobotSystemStatus(val mileageMetres: Int?, val drivingSeconds: Int?, val lowPower: Boolean,
    val reportAgeSeconds: Long = 0)
