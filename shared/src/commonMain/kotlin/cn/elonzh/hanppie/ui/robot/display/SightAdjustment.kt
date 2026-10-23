package cn.elonzh.hanppie.ui.robot.display

import cn.elonzh.hanppie.robot.display.SightPoint
import kotlin.math.abs
import kotlin.math.roundToInt

/** Same 50-step fine adjustment budget as the managed calibration controls, at the actual zoom. */
internal data class SightAdjustment(val point: SightPoint, val zoomHundredths: Int) {
    init { require(zoomHundredths == 100 || zoomHundredths == 400) }
    val xSteps: Int get() = ((point.x - 5000) * zoomHundredths / 2500.0).roundToInt()
    val ySteps: Int get() = ((point.y - 5000) * zoomHundredths / 2500.0).roundToInt()
    val adjustable: Boolean get() = abs(xSteps) <= 50 && abs(ySteps) <= 50
    fun move(x: Int, y: Int): SightPoint {
        require(abs(x) + abs(y) == 1 && adjustable)
        val nextX = (xSteps + x).coerceIn(-50, 50)
        val nextY = (ySteps + y).coerceIn(-50, 50)
        return SightPoint((5000 + nextX * 2500.0 / zoomHundredths).toInt(),
            (5000 + nextY * 2500.0 / zoomHundredths).toInt())
    }
}
