package cn.elonzh.hanppie.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.app.testConsoleModel
import cn.elonzh.hanppie.ui.design.TestWorkbenchTheme
import cn.elonzh.hanppie.ui.i18n.Localization
import cn.elonzh.hanppie.ui.i18n.uiText
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class SettingsToastUiTest {
    @Test fun temporaryFeedbackDoesNotMoveTheFormAndErrorsRemainBesideTheTask() = runDesktopComposeUiTest(width = 1280, height = 720) {
        Localization.initialize("zh", null)
        val model = testConsoleModel()
        try {
            setContent { TestWorkbenchTheme { SettingsPage(model, Modifier.fillMaxSize().padding(16.dp), onAlbum = {}) } }
            onNodeWithTag("settings-category-media").performScrollTo().performClick()
            mainClock.advanceTimeBy(32)
            mainClock.autoAdvance = false
            val before = onNodeWithTag("video-resolution-selector").getBoundsInRoot()
            runOnUiThread { model.settingsMessage.value = uiText(Res.string.settings_saved) }
            mainClock.advanceTimeBy(32)
            onNodeWithTag("workbench-toast").assertIsDisplayed()
            assertEquals(before, onNodeWithTag("video-resolution-selector").getBoundsInRoot())
            val image = onRoot().captureToImage(); val pixels = IntArray(image.width * image.height); image.readPixels(pixels)
            val out = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB); out.setRGB(0, 0, image.width, image.height, pixels, 0, image.width)
            ImageIO.write(out, "png", File("build/reports/ui/settings-toast.png").apply { parentFile.mkdirs() })
            mainClock.advanceTimeBy(3_200)
            onNodeWithTag("workbench-toast").assertDoesNotExist()
            runOnUiThread { model.settingsMessage.value = uiText(Res.string.settings_save_failed) }
            mainClock.advanceTimeBy(32)
            onNodeWithTag("workbench-toast").assertDoesNotExist()
            assertNotNull(model.settingsMessage.value)
            onNodeWithTag("video-resolution-selector").assertExists()
        } finally { model.close() }
    }
}
