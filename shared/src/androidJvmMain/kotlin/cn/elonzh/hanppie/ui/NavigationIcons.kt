package cn.elonzh.hanppie.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

internal val navigationIcons = List(5) { index ->
    ImageVector.Builder("navigation-$index", 24.dp, 24.dp, 24f, 24f).apply {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f) {
            when (index) {
                0 -> {
                    moveTo(4f, 7f); lineTo(20f, 7f); lineTo(20f, 20f); lineTo(4f, 20f); close()
                    moveTo(12f, 2f); lineTo(12f, 7f)
                    moveTo(8f, 12f); lineTo(8f, 15f); moveTo(16f, 12f); lineTo(16f, 15f)
                }
                1 -> {
                    moveTo(8f, 5f); lineTo(2f, 12f); lineTo(8f, 19f)
                    moveTo(16f, 5f); lineTo(22f, 12f); lineTo(16f, 19f)
                    moveTo(14f, 3f); lineTo(10f, 21f)
                }
                2 -> { for (y in listOf(6f, 12f, 18f)) { moveTo(4f, y); lineTo(20f, y) } }
                3 -> {
                    moveTo(4f, 4f); lineTo(20f, 4f); lineTo(20f, 17f)
                    lineTo(10f, 17f); lineTo(4f, 22f); close()
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
