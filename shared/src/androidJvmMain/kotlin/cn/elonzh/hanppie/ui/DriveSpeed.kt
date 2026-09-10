package cn.elonzh.hanppie.ui

internal object DriveSpeed {
    const val gearCount = 5

    fun translation(forward: Double, right: Double, gear: Int, creeping: Boolean,
                    settings: ControlSettings = ControlSettings()): Pair<Double, Double> {
        require(forward.isFinite() && right.isFinite())
        val magnitude = kotlin.math.hypot(forward, right).coerceAtLeast(1.0)
        val speed = settings.translationSpeeds[gear.coerceIn(1, gearCount) - 1] *
            if (creeping) settings.creepMultiplier else 1.0
        return forward / magnitude * speed to right / magnitude * speed
    }

    fun rotation(gear: Int, creeping: Boolean, settings: ControlSettings = ControlSettings()): Double =
        settings.rotationSpeeds[gear.coerceIn(1, gearCount) - 1] *
            if (creeping) settings.creepMultiplier else 1.0
}
