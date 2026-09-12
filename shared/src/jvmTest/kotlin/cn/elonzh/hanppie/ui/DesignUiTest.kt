package cn.elonzh.hanppie.ui

import cn.elonzh.hanppie.resources.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/** Use real test surface sizes: requiredSize alone crops oversized desktop screenshots. */
@OptIn(ExperimentalTestApi::class)
class DesignUiTest {
    @Test fun ledColorPickerAtPhoneWidth() = runDesktopComposeUiTest(width = 320, height = 640) {
        Localization.initialize("zh", null)
        val color = mutableStateOf(RobotLedColor(255, 0, 0))
        setContent { WorkbenchTheme {
            RemoteLedColorPicker("待机", "test-led", color.value, androidx.compose.ui.Modifier) { color.value = it }
        } }
        onNodeWithContentDescription("test-led").performClick()
        onNodeWithTag("led-brightness").performTouchInput { swipe(center.copy(x = width - 12f, y = height - 24f), center.copy(x = width * 0.5f, y = height - 24f)) }
        onNodeWithText("取消").performClick()
        runOnIdle { assertEquals(RobotLedColor(255, 0, 0), color.value) }
        onNodeWithContentDescription("test-led").performClick()
        onNodeWithTag("led-color-apply").assertIsDisplayed().performClick()
        runOnIdle { assertEquals(RobotLedColor(255, 0, 0), color.value) }
        onNodeWithContentDescription("test-led").performClick()
        onNodeWithTag("led-brightness").performTouchInput { swipe(center.copy(x = width - 12f, y = height - 24f), center.copy(x = width * 0.5f, y = height - 24f)) }
        saveDesignSnapshot("led-color-picker-phone", onAllNodes(isRoot()).onLast(), 320, 640)
        onNodeWithTag("led-color-apply").performClick()
        runOnIdle {
            assertTrue(color.value.red in 1..254)
            assertEquals(0, color.value.green)
            assertEquals(0, color.value.blue)
        }
    }

    @Test fun cameraFixedTopViews() = runDesktopComposeUiTest(width = 640, height = 160) {
        setContent {
            WorkbenchTheme {
                androidx.compose.foundation.layout.Row(
                    androidx.compose.ui.Modifier
                        .background(HanppieDesignTokens.RemoteHudSurface).fillMaxSize(),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    HeadingIndicator(90.0)
                    HeadingIndicator(0.0)
                    HeadingIndicator(-90.0)
                }
            }
        }
        saveDesignSnapshot("camera-fixed-top-views", onRoot(), 640, 160)
    }

    @Test fun desktopNavigationConnectionAndScrollableLogs() = runDesktopComposeUiTest(width = 1040, height = 700) {
        Localization.initialize("zh", null)
        val model = ConsoleModel()
        model.state.value = model.state.value.copy(logs = (0..150).map { "测试日志 $it" })
        try {
            setContent { WorkbenchTheme { Console(model, mutableStateOf(EditorDocument())) } }
            val chat = onNodeWithContentDescription("对话").fetchSemanticsNode().boundsInRoot
            val debug = onNodeWithContentDescription("诊断").fetchSemanticsNode().boundsInRoot
            assertEquals(48f, chat.height)
            assertTrue(chat.bottom < debug.top)
            onNodeWithTag("connection-status").performClick()
            onNodeWithText("连接状态").assertIsDisplayed()
            runOnIdle { model.state.value = model.state.value.copy(connected = true, statusMessage = uiText(Res.string.connected), connectedAddress = "192.0.2.1", battery = 72, signalQuality = 80) }
            waitUntil(timeoutMillis = 3_000) { onAllNodesWithText("192.0.2.1").fetchSemanticsNodes().isNotEmpty() }
            saveDesignSnapshot("connection-details", onAllNodes(isRoot()).onLast(), 1040, 700)
            runOnIdle { model.state.value = model.state.value.copy(connected = false, statusMessage = uiText(Res.string.disconnected), connectedAddress = null, battery = null, signalQuality = null) }
            waitUntil(timeoutMillis = 3_000) { onAllNodesWithTag("connection-manual").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("connection-manual").performClick()
            onNodeWithText("取消").performClick()
            onNodeWithContentDescription("脚本").performClick()
            onNodeWithTag("connection-status").performClick()
            onNodeWithText("连接状态").assertIsDisplayed()
            onNodeWithTag("connection-manual").performClick()
            onNodeWithText("取消").performClick()
            onNodeWithContentDescription("诊断").performClick()
            onNodeWithTag("diagnostic-scrollbar").assertIsDisplayed()
            onNodeWithTag("diagnostic-output").performScrollToIndex(150)
            onNodeWithText("测试日志 150").assertIsDisplayed()
            saveDesignSnapshot("desktop-scrollable-logs", onRoot(), 1040, 700)
        } finally { model.close() }
    }

    @Test fun headingChangesNeverResizeTelemetry() = runDesktopComposeUiTest(width = 740, height = 393) {
        val model = ConsoleModel()
        try {
            setContent { WorkbenchTheme { RemotePage(model) } }
            val original = onNodeWithTag("remote-telemetry").fetchSemanticsNode().boundsInRoot
            for (angle in listOf(0.0, 9.0, -10.0, 180.0, -359.0)) {
                runOnIdle { model.state.value = model.state.value.copy(gimbal = cn.elonzh.hanppie.robot.GimbalTelemetry(0.0, 0.0, angle, 0.0, 0)) }
                waitForIdle()
                assertEquals(original.width, onNodeWithTag("remote-telemetry").fetchSemanticsNode().boundsInRoot.width)
            }
        } finally { model.close() }
    }

    @Test fun minimumDesktopContentFits() = runDesktopComposeUiTest(width = 900, height = 572) {
        val model = ConsoleModel()
        try {
            setContent { WorkbenchTheme { Console(model, mutableStateOf(EditorDocument())) } }
            onNodeWithTag("discover-robot").assertIsDisplayed()
            onNodeWithContentDescription("设置").performClick()
            onNodeWithContentDescription("gimbal-sensitivity-selector").assertIsDisplayed()
            saveDesignSnapshot("desktop-minimum-settings", onRoot(), 900, 572)
            onNodeWithContentDescription("设备").performClick()
            saveDesignSnapshot("desktop-minimum-device", onRoot(), 900, 572)
        } finally { model.close() }
    }

    @Test fun narrowPhoneWithLargeEnglishText() = runDesktopComposeUiTest(width = 320, height = 640) {
        Localization.initialize("en", null)
        val model = ConsoleModel()
        try {
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1.3f)) {
                    WorkbenchTheme(AppearanceController(AppearanceSettings(NightMode.LIGHT))) {
                        Console(model, mutableStateOf(EditorDocument()))
                    }
                }
            }
            onNodeWithTag("discover-robot").assertIsDisplayed()
            onNodeWithTag("manual-connect").assertIsDisplayed()
            assertTrue(onNodeWithTag("connection-status").fetchSemanticsNode().boundsInRoot.right <= 300f)
            saveDesignSnapshot("design-phone-320-large-text", onRoot(), 320, 640)
            onNode(hasContentDescription("Chat") and hasClickAction()).performClick()
            onNodeWithText("What would you like to do?").assertIsDisplayed()
            saveDesignSnapshot("design-chat-320-large-text", onRoot(), 320, 640)
        } finally { model.close(); Localization.initialize("zh", null) }
    }

    @Test fun wideDesktopKeepsContentCenteredInBothModes() = runDesktopComposeUiTest(width = 1440, height = 900) {
        Localization.initialize("en", null)
        val model = ConsoleModel()
        val appearance = AppearanceController(AppearanceSettings(NightMode.LIGHT))
        try {
            setContent { WorkbenchTheme(appearance) { Console(model, mutableStateOf(EditorDocument())) } }
            val page = onNodeWithTag("device-page").fetchSemanticsNode().boundsInRoot
            assertTrue(page.width <= 1080f)
            assertTrue(page.left > 100f && page.right < 1340f, "Wide content must leave readable side margins")
            saveDesignSnapshot("design-desktop-1440-light", onRoot(), 1440, 900)
            runOnIdle { appearance.update(AppearanceSettings(NightMode.DARK)) }
            saveDesignSnapshot("design-desktop-1440-dark", onRoot(), 1440, 900)
        } finally { model.close(); Localization.initialize("zh", null) }
    }
}

private fun saveDesignSnapshot(name: String, node: SemanticsNodeInteraction, width: Int, height: Int) {
    val image = node.captureToImage()
    assertEquals(width, image.width)
    assertEquals(height, image.height)
    val pixels = IntArray(width * height)
    image.readPixels(pixels)
    val buffered = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    buffered.setRGB(0, 0, width, height, pixels, 0, width)
    val output = File("build/reports/ui/$name.png")
    output.parentFile.mkdirs()
    ImageIO.write(buffered, "png", output)
}
