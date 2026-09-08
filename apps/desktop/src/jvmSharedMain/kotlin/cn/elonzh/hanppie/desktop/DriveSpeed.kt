package cn.elonzh.hanppie.desktop

internal object DriveSpeed {
    val gears = listOf(.15, .30, .60)
    fun translation(forward: Double, right: Double, gear: Int, creeping: Boolean): Pair<Double, Double> {
        require(forward.isFinite() && right.isFinite())
        val magnitude = kotlin.math.hypot(forward, right).coerceAtLeast(1.0)
        val speed = gears[gear.coerceIn(1, gears.size) - 1] * if (creeping) .25 else 1.0
        return forward / magnitude * speed to right / magnitude * speed
    }
}
