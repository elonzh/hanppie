package cn.elonzh.hanppie.ui.scripts

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cn.elonzh.hanppie.robot.lab.ScriptRunPhase
import cn.elonzh.hanppie.robot.lab.ui.*
import cn.elonzh.hanppie.robot.protocol.*
import cn.elonzh.hanppie.robot.session.*
import cn.elonzh.hanppie.ui.app.*
import cn.elonzh.hanppie.ui.design.WorkbenchTheme
import cn.elonzh.hanppie.ui.i18n.Localization
import cn.elonzh.hanppie.ui.i18n.uiText
import cn.elonzh.hanppie.resources.Res
import cn.elonzh.hanppie.resources.script_running
import cn.elonzh.hanppie.ui.settings.AppearanceController
import cn.elonzh.hanppie.ui.settings.AppearanceSettings
import cn.elonzh.hanppie.ui.settings.NightMode
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.*
import kotlin.test.*

/** Synthetic authored controls are rendered through the real ScriptPage and root controller. */
@OptIn(ExperimentalTestApi::class)
class ProgramPanelUiTest {
    private val owner = "0123456789abcdef0123456789abcdef"
    private fun key(type: LabWidgetType) = LabWidgetKey(type, 0)
    private fun controls(english: Boolean): LabUiState {
        val stage = LabWidget(key(LabWidgetType.STAGE), 1, LabWidgetLayout(width = 1920f, height = 1080f), attached = true)
        val widgets = listOf(
            stage,
            LabWidget(key(LabWidgetType.BUTTON), 2, LabWidgetLayout(-155f, 70f, 260f, 50f),
                stage.key, true, text = if (english) "Start scan" else "开始巡检",
                textStyle = LabUiTextStyle(LabUiColor(255, 255, 255, 255)), background = LabUiColor(39, 74, 99, 255)),
            LabWidget(key(LabWidgetType.TOGGLE), 3, LabWidgetLayout(155f, 70f, 260f, 50f),
                stage.key, true, text = if (english) "Headlight" else "照明"),
            LabWidget(key(LabWidgetType.SLIDER), 4, LabWidgetLayout(-155f, 0f, 260f, 50f),
                stage.key, true, maximum = 100, value = 40),
            LabWidget(key(LabWidgetType.DROPDOWN), 5, LabWidgetLayout(155f, 0f, 260f, 50f),
                stage.key, true, options = if (english) listOf("Route A", "Route B") else listOf("路线 A", "路线 B")),
            LabWidget(key(LabWidgetType.TEXT), 6, LabWidgetLayout(-155f, -70f, 260f, 50f),
                stage.key, true, text = if (english) "Ready" else "任务已就绪", backgroundVisible = false),
            LabWidget(key(LabWidgetType.INPUT), 7, LabWidgetLayout(155f, -70f, 260f, 50f),
                stage.key, true, hint = if (english) "Inspection ID" else "巡检编号"),
        )
        return LabUiState(owner, running = true, inputEnabled = true, widgets = widgets.associateBy { it.key })
    }

    private fun model(session: RobotSessionFixture): ConsoleModel {
        val runtime = object : RobotRuntime {
            override suspend fun discover(timeoutMillis: Long): List<DiscoveredRobot> = emptyList()
            override suspend fun waitForRouterPairing(appId: String): RouterPairing = error("unused")
            override suspend fun acknowledgeRouterPairing(pairing: RouterPairing, appId: String) {}
            override fun open(target: RobotTarget, onFrame: (DussFrame) -> Unit, onLog: (String) -> Unit,
                onLost: (RobotSession, String) -> Unit): RobotSession = session
        }
        return testConsoleModel(robotRuntime = runtime).also { model -> runBlocking {
            model.connect("127.0.0.1", "12345678")
            withTimeout(5_000) { while (!model.state.value.connected || model.state.value.busy) delay(5) }
            model.state.value = model.state.value.copy(scriptRunPhase = ScriptRunPhase.RUNNING,
                scriptMessage = uiText(Res.string.script_running))
        } }
    }

    @Test fun shortLandscape() = scenario(844, 390, "zh", 1f, false)
    @Test fun mediumDarkLandscape() = scenario(1024, 600, "zh", 1f, true)
    @Test fun desktopLandscape() = scenario(1280, 720, "zh", 1f, false)
    @Test fun largeEnglishLandscape() = scenario(844, 390, "en", 1.4f, false)

    private fun scenario(width: Int, height: Int, language: String, scale: Float, dark: Boolean) =
        runDesktopComposeUiTest(width = width, height = height) {
            Localization.initialize(language, language)
            val session = RobotSessionFixture()
            val model = model(session)
            val sample = controls(language == "en")
            val document = mutableStateOf(EditorDocument(source = "def start():\n    pass\n", title = if (language == "en") "Inspection" else "巡检面板"))
            val appearance = AppearanceController(AppearanceSettings(if (dark) NightMode.DARK else NightMode.LIGHT))
            val prefix = "program-panel-$width-$height-$language-${if (dark) "dark" else "light"}"
            fun capture(suffix: String) {
                waitForIdle()
                val image = onRoot().captureToImage()
                val pixels = IntArray(image.width * image.height); image.readPixels(pixels)
                val output = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
                output.setRGB(0, 0, image.width, image.height, pixels, 0, image.width)
                val file = File("build/reports/ui/$prefix-$suffix.png"); file.parentFile.mkdirs()
                ImageIO.write(output, "png", file)
            }
            try {
                setContent {
                    WorkbenchTheme(appearance) {
                        val density = LocalDensity.current
                        CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                            Box(Modifier.fillMaxSize().padding(16.dp)) {
                                ScriptPage(model, document, compact = width < 1100, onImport = {}, onExport = {},
                                    fileError = null, onFileError = {})
                            }
                        }
                    }
                }
                onNodeWithTag("program-panel-open").assertDoesNotExist()
                onNodeWithTag("script-editor").assertIsDisplayed()
                runOnIdle { session.labUiState.value = sample }
                waitUntil(timeoutMillis = 5_000) { model.labUi.value.widgets.size == 7 }
                onNodeWithTag("program-panel-open").assertIsDisplayed()
                capture("entry")
                onNodeWithTag("program-panel-open").performClick()
                onNodeWithTag("program-panel-stop").assertIsDisplayed()
                onNodeWithTag("program-panel-back").assertIsDisplayed()
                onNodeWithTag("program-input-0").assertIsDisplayed()
                capture("controls")
                // Device attributes are presentation, never user intent.
                assertTrue(session.labUiEvents.isEmpty())
                onNodeWithTag("program-toggle-0").performClick()
                assertEquals(LabUiValue.Flag(true), session.labUiEvents.last().value)
                onNodeWithTag("program-input-0").performTextInput("R1")
                assertEquals(LabUiValue.Text("R1"), session.labUiEvents.last().value)
                onNodeWithTag("program-dropdown-0").performClick()
                onNodeWithTag("program-option-0-1").performClick()
                assertEquals(LabUiValue.Integer(1), session.labUiEvents.last().value)
                onNodeWithTag("program-panel-monitor").performClick()
                waitUntil(timeoutMillis = 5_000) { session.labUiVisibility.lastOrNull() == (owner to false) }
                onNodeWithTag("script-status-strip").assertIsDisplayed()
                onNodeWithText(if (language == "en") "Close" else "关闭").performClick()
                runOnIdle { session.labUiState.value = sample.copy(inputEnabled = false, issue = LabUiIssue.SEND_FAILED) }
                waitUntil(timeoutMillis = 5_000) { model.labUi.value.issue == LabUiIssue.SEND_FAILED && !model.labUi.value.inputEnabled }
                onNodeWithTag("program-input-0").assertIsNotEnabled()
                capture("error")
                onNodeWithTag("program-panel-back").performClick()
                onNodeWithTag("script-editor").assertIsDisplayed()
                assertEquals(owner to false, session.labUiVisibility.last())
                onNodeWithTag("program-panel-open").performClick()
                runOnIdle { session.labUiState.value = sample.copy(widgets = emptyMap()) }
                waitUntil(timeoutMillis = 5_000) { model.labUi.value.widgets.isEmpty() }
                onNodeWithText(if (language == "en") "The program has no visible controls." else "程序暂无可见控件。").assertIsDisplayed()
                onNodeWithTag("program-panel-stop").assertIsDisplayed()
                capture("empty")
                onNodeWithTag("program-panel-stop").performClick()
                waitUntil(timeoutMillis = 5_000) { session.labStopCount.get() == 1 && model.labUi.value.runId == null }
                onNodeWithTag("script-editor").assertIsDisplayed()
            } finally { model.close(); Localization.initialize("zh", null) }
        }

    @Test fun pointerKeyboardCancellationAndByteLimitAreRealInputBoundaries() =
        runDesktopComposeUiTest(width = 1024, height = 600) {
            Localization.initialize("zh", "zh")
            val session = RobotSessionFixture()
            val model = model(session)
            val sample = controls(false)
            val document = mutableStateOf(EditorDocument(source = "pass"))
            try {
                session.labUiState.value = sample
                setContent { WorkbenchTheme {
                    ScriptPage(model, document, compact = false, onImport = {}, onExport = {}, fileError = null, onFileError = {})
                } }
                waitUntil(timeoutMillis = 5_000) { model.labUi.value.runId == owner }
                onNodeWithTag("program-panel-open").performClick()
                onNodeWithTag("program-slider-0").performTouchInput { down(centerLeft); moveTo(centerRight); up() }
                waitUntil(timeoutMillis = 5_000) { session.labUiEvents.any { it.key.type == LabWidgetType.SLIDER && it.final } }
                assertEquals(LabUiValue.Integer(100), session.labUiEvents.last().value)
                session.labUiEvents.clear()
                val button = onNodeWithTag("program-widget-1-0")
                button.performTouchInput { down(center) }
                waitUntil(timeoutMillis = 5_000) { session.labUiEvents.any { it.event == 1 } }
                button.performTouchInput { up() }
                waitUntil(timeoutMillis = 5_000) { session.labUiEvents.size == 3 }
                assertEquals(listOf(1, 2, 0), session.labUiEvents.map { it.event })
                button.performKeyInput { keyDown(Key.Spacebar); keyUp(Key.Spacebar) }
                assertEquals(listOf(1, 2, 0), session.labUiEvents.takeLast(3).map { it.event })
                val beforeCancel = session.labUiEvents.size
                button.performTouchInput { down(center) }
                waitUntil(timeoutMillis = 5_000) { session.labUiEvents.size > beforeCancel }
                // Desktop's synthetic cancel is a no-op. Leaving the target exercises a real cancellation.
                button.performTouchInput { moveTo(Offset(-100f, -100f)); up() }
                waitUntil(timeoutMillis = 5_000) { session.labUiEvents.size >= beforeCancel + 2 }
                assertEquals(listOf(1, 2), session.labUiEvents.takeLast(2).map { it.event })
                button.performTouchInput { down(center) }
                runOnIdle { model.setForeground(false) }
                waitUntil(timeoutMillis = 5_000) { !model.foregroundState.value }
                button.performTouchInput { up() }
                assertEquals(owner to false, session.labUiVisibility.last())
                val mark = session.labUiEvents.size
                runOnIdle { model.setForeground(true) }
                waitForIdle(); assertEquals(mark, session.labUiEvents.size)
                onNodeWithTag("program-input-0").performTextInput("你".repeat(85))
                assertEquals(LabUiValue.Text("你".repeat(85)), session.labUiEvents.last().value)
                onNodeWithTag("program-input-0").performTextInput("你")
                onNodeWithTag("program-panel-message").assertTextEquals("输入内容过长，请缩短后重试。")
                assertEquals(LabUiValue.Text("你".repeat(85)), session.labUiEvents.last().value)
                val fixed = sample.widgets.getValue(key(LabWidgetType.SLIDER)).copy(
                    minimum = Int.MAX_VALUE, maximum = Int.MAX_VALUE, value = Int.MAX_VALUE)
                runOnIdle { session.labUiState.value = sample.copy(widgets = sample.widgets + (fixed.key to fixed)) }
                waitUntil(timeoutMillis = 5_000) { model.labUi.value.widgets[fixed.key]?.minimum == Int.MAX_VALUE }
                onNodeWithTag("program-slider-0").assertIsNotEnabled()
            } finally { model.close(); Localization.initialize("zh", null) }
        }
}
