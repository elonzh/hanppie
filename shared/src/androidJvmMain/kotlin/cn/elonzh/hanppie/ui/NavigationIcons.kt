package cn.elonzh.hanppie.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

internal val navigationIcons = List(5) { index ->
    ImageVector.Builder("navigation-$index", 24.dp, 24.dp, 24f, 24f).apply {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            when (index) {
                0 -> {
                    moveTo(6f, 7f); lineTo(18f, 7f); curveTo(20f, 7f, 21f, 8f, 21f, 10f); lineTo(21f, 17f); curveTo(21f, 19f, 20f, 20f, 18f, 20f); lineTo(6f, 20f); curveTo(4f, 20f, 3f, 19f, 3f, 17f); lineTo(3f, 10f); curveTo(3f, 8f, 4f, 7f, 6f, 7f); close()
                    moveTo(12f, 2f); lineTo(12f, 7f)
                    moveTo(8f, 12f); lineTo(8f, 14f); moveTo(16f, 12f); lineTo(16f, 14f); moveTo(10f, 17f); lineTo(14f, 17f)
                }
                1 -> {
                    moveTo(8f, 5f); lineTo(2f, 12f); lineTo(8f, 19f)
                    moveTo(16f, 5f); lineTo(22f, 12f); lineTo(16f, 19f)
                    moveTo(14f, 3f); lineTo(10f, 21f)
                }
                2 -> { moveTo(2f, 12f); lineTo(6f, 12f); lineTo(9f, 5f); lineTo(13f, 19f); lineTo(16f, 9f); lineTo(18f, 12f); lineTo(22f, 12f) }
                3 -> {
                    moveTo(6f, 4f); lineTo(18f, 4f); curveTo(20f, 4f, 21f, 5f, 21f, 7f); lineTo(21f, 14f); curveTo(21f, 16f, 20f, 17f, 18f, 17f); lineTo(10f, 17f); lineTo(4f, 21f); lineTo(4f, 17f); curveTo(3f, 17f, 3f, 16f, 3f, 14f); lineTo(3f, 7f); curveTo(3f, 5f, 4f, 4f, 6f, 4f); close()
                }
                4 -> {
                    for ((y, x) in listOf(5f to 8f, 12f to 16f, 19f to 8f)) {
                        moveTo(2f, y); lineTo(x - 2, y); moveTo(x + 2, y); lineTo(22f, y)
                        moveTo(x - 2, y - 2); lineTo(x + 2, y - 2)
                        lineTo(x + 2, y + 2); lineTo(x - 2, y + 2); close()
                    }
                }
            }
        }
    }.build()
}
