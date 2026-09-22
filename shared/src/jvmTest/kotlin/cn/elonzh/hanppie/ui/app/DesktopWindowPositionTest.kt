package cn.elonzh.hanppie.ui.app

import cn.elonzh.hanppie.ui.settings.SavedWindowPosition
import java.awt.Rectangle
import kotlin.test.*
import org.junit.Test

class DesktopWindowPositionTest {
    @Test fun restoresWithinUsableScreenAndKeepsSecondaryDisplayCoordinates() {
        val screens = listOf(Rectangle(0, 25, 1728, 1055), Rectangle(-1920, 0, 1920, 1080))
        val saved = SavedWindowPosition(120f, 72f)
        assertEquals(saved, visibleWindowPosition(saved, screens))
        val secondary = SavedWindowPosition(-1800f, 80f)
        assertEquals(secondary, visibleWindowPosition(secondary, screens))
        assertEquals(SavedWindowPosition(608f, 422f), visibleWindowPosition(SavedWindowPosition(1600f, 900f), screens))
        assertNull(visibleWindowPosition(secondary, screens.take(1)), "Unplugged display must not strand the window")
        assertNull(visibleWindowPosition(SavedWindowPosition(Float.NaN, 0f), screens))
    }
}
