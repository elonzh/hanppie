package cn.elonzh.hanppie.ui.app

import java.awt.Dimension
import java.awt.Insets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopVideoAspectTest {
    @Test fun videoRatioExcludesTitleBarAndTracksBothResizeAxes() {
        val borders = Insets(28, 0, 0, 0)
        assertEquals(Dimension(1120, 658), videoWindowSize(Dimension(1120, 800), borders, 16.0 / 9, false))
        assertEquals(Dimension(1280, 748), videoWindowSize(Dimension(1120, 748), borders, 16.0 / 9, true))
        val minimum = videoWindowSize(Dimension(200, 200), borders, 16.0 / 9, false)
        assertTrue(minimum.width >= 740 && minimum.height >= 480)
        assertEquals(minimum, videoWindowSize(minimum, borders, 16.0 / 9, false))
    }
}
