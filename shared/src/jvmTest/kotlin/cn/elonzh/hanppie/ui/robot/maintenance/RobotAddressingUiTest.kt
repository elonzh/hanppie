package cn.elonzh.hanppie.ui.robot.maintenance

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cn.elonzh.hanppie.robot.maintenance.*
import cn.elonzh.hanppie.robot.telemetry.RobotHealth
import cn.elonzh.hanppie.ui.app.*
import cn.elonzh.hanppie.ui.design.TestWorkbenchTheme
import cn.elonzh.hanppie.ui.i18n.Localization
import cn.elonzh.hanppie.ui.i18n.AppLanguage
import cn.elonzh.hanppie.ui.i18n.tr
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.settings.*
import cn.elonzh.hanppie.ui.scripts.EditorDocument
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

private fun addressingModel(service: AddressingServiceFixture): ConsoleModel {
    val session = RobotSessionFixture().apply { addressing = service; health.value = RobotHealth(modules = emptyList()) }
    return testConsoleModel(robotRuntime = AddressingRuntime(listOf(session)))
}

@OptIn(ExperimentalTestApi::class)
class RobotAddressingUiTest {
    @Test fun wheelStartRequiresConfirmationAndHoldReleaseNeverImpliesAPhysicalMatch() =
        runDesktopComposeUiTest(width = 1024, height = 600) {
            Localization.initialize("zh", null)
            val service = AddressingServiceFixture(); val model = addressingModel(service)
            try {
                setContent { TestWorkbenchTheme { Box(Modifier.fillMaxSize().padding(16.dp)) { RobotAddressingPage(model, onBack = {}) } } }
                model.connect("127.0.0.1", "12345678")
                waitUntil(timeoutMillis = 5000) { model.state.value.connected && !model.state.value.busy }
                onNodeWithTag("addressing-kind-wheels").performClick()
                onNodeWithTag("addressing-start").performClick(); onNodeWithTag("addressing-confirm-cancel").performClick()
                waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("addressing-confirm").fetchSemanticsNodes().isEmpty() }
                assertTrue(service.calls.none { it.startsWith("begin:") })
                onNodeWithTag("addressing-start").performClick(); onNodeWithTag("addressing-confirm").performClick()
                waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("addressing-confirm").fetchSemanticsNodes().isEmpty() }
                waitUntil(timeoutMillis = 3000) { service.calls.contains("begin:WHEELS") && !model.robotAddressing.state.value.busy && !model.state.value.busy }
                onNodeWithTag("addressing-verify").assertDoesNotExist()
                runOnIdle { service.state.value = service.state.value.copy(wheelStateCode = 1, wheelPercentRaw = 100) }
                waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("addressing-verify").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("addressing-verify").performClick()
                waitUntil(timeoutMillis = 3000) { !model.state.value.busy && !model.robotAddressing.state.value.busy && service.state.value.wheelTestReady }
                assertTrue(service.calls.none { it.startsWith("hold:") && !it.endsWith("null") })
                waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("addressing-wheel-hold-front_right").fetchSemanticsNodes().isNotEmpty() }
                val hold = onNodeWithTag("addressing-wheel-hold-front_right").performScrollTo()
                hold.performTouchInput { down(center) }
                waitUntil(timeoutMillis = 3000) { service.state.value.wheelHeld == AddressingWheel.FRONT_RIGHT }
                onNodeWithTag("addressing-wheel-confirm-front_right").assertIsNotEnabled()
                hold.performTouchInput { up() }
                waitUntil(timeoutMillis = 3000) { service.state.value.wheelHeld == null }
                onNodeWithTag("addressing-wheel-confirm-front_right").assertIsNotEnabled()
                hold.performKeyInput { keyDown(Key.Spacebar) }
                waitUntil(timeoutMillis = 3000) { service.state.value.wheelHeld == AddressingWheel.FRONT_RIGHT }
                hold.performKeyInput { keyUp(Key.Spacebar) }
                waitUntil(timeoutMillis = 3000) { service.state.value.wheelHeld == null }
                runOnIdle { service.state.value = service.state.value.copy(testedWheels = setOf(AddressingWheel.FRONT_RIGHT)) }
                onNodeWithTag("addressing-wheel-confirm-front_right").performClick()
                assertEquals(setOf(AddressingWheel.FRONT_RIGHT), service.state.value.verifiedWheels)
                onNodeWithTag("addressing-end").performClick(); onNodeWithTag("addressing-confirm-cancel").performClick()
                waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("addressing-confirm").fetchSemanticsNodes().isEmpty() }
                assertTrue(service.calls.none { it == "end" })
                service.failEnd = true
                onNodeWithTag("addressing-end").performClick(); onNodeWithTag("addressing-confirm").performClick()
                waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("addressing-confirm").fetchSemanticsNodes().isEmpty() }
                waitUntil(timeoutMillis = 3000) { !model.robotAddressing.state.value.busy && !model.state.value.busy }
                onNodeWithTag("addressing-problem").assertIsDisplayed()
                assertTrue(model.robotAddressing.state.value.blocksControl)
                service.failEnd = false
                onNodeWithTag("addressing-end").performClick(); onNodeWithTag("addressing-confirm").performClick()
                waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("addressing-confirm").fetchSemanticsNodes().isEmpty() }
                waitUntil(timeoutMillis = 3000) { !model.robotAddressing.state.value.busy && !model.state.value.busy }
                onNodeWithTag("addressing-result").assertTextEquals("设备已应答结束请求")
            } finally { model.close() }
        }

    @Test fun candidateChangesInvalidateTheWritePromptAndVerificationWaitsForANewReport() =
        runDesktopComposeUiTest(width = 1024, height = 600) {
            Localization.initialize("zh", null)
            val service = AddressingServiceFixture(); val model = addressingModel(service)
            try {
                setContent { TestWorkbenchTheme { Box(Modifier.fillMaxSize().padding(16.dp)) { RobotAddressingPage(model, onBack = {}) } } }
                model.connect("127.0.0.1", "12345678")
                waitUntil(timeoutMillis = 5000) { model.state.value.connected && !model.state.value.busy }
                model.robotAddressing.begin(ModuleAddressingKind.SERVO)
                waitUntil(timeoutMillis = 3000) { service.state.value.phase == AddressingPhase.DISCOVERING && !model.state.value.busy }
                runOnIdle { service.state.value = service.state.value.copy(candidateId = 4, reservedIds = setOf(1, 2), inventoryKnown = true) }
                waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("addressing-id-3").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("addressing-id-1").assertIsNotEnabled(); onNodeWithTag("addressing-id-2").assertIsNotEnabled()
                onNodeWithTag("addressing-id-4").assertDoesNotExist()
                onNodeWithTag("addressing-id-3").performClick(); onNodeWithTag("addressing-assign").performClick()
                onNodeWithTag("addressing-confirm").assertIsDisplayed()
                runOnIdle { service.state.value = service.state.value.copy(candidateId = null) }
                waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("addressing-confirm").fetchSemanticsNodes().isEmpty() }
                onNodeWithTag("addressing-confirm").assertDoesNotExist()
                assertTrue(service.calls.none { it.startsWith("assign:") })
                runOnIdle { service.state.value = service.state.value.copy(candidateId = 4) }
                waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("addressing-id-3").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("addressing-id-3").performClick(); onNodeWithTag("addressing-assign").performClick()
                onNodeWithTag("addressing-confirm").performClick()
                waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("addressing-confirm").fetchSemanticsNodes().isEmpty() }
                waitUntil(timeoutMillis = 3000) { service.calls.contains("assign:3") && !model.state.value.busy }
                onNodeWithTag("addressing-verify").performScrollTo().performClick()
                waitUntil(timeoutMillis = 3000) { service.state.value.phase == AddressingPhase.VERIFYING && !model.state.value.busy }
                onNodeWithTag("addressing-identify-3").assertDoesNotExist()
                runOnIdle { service.state.value = service.state.value.copy(inventoryKnown = true, onlineIds = setOf(3)) }
                waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("addressing-identify-3").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("addressing-module-confirm-3").assertIsNotEnabled()
                onNodeWithTag("addressing-identify-3").performClick()
                waitUntil(timeoutMillis = 3000) { service.calls.contains("identify:3") && !model.state.value.busy }
                onNodeWithTag("addressing-module-confirm-3").assertIsNotEnabled()
                runOnIdle { service.state.value = service.state.value.copy(identifiedIds = setOf(3)) }
                onNodeWithTag("addressing-module-confirm-3").performClick()
                assertEquals(setOf(3), service.state.value.verifiedIds)
            } finally { model.close() }
        }

    @Test fun armorStepChangesInvalidateSkipAndAdapterGuideUsesTheOnboardPattern() =
        runDesktopComposeUiTest(width = 1024, height = 600) {
            Localization.initialize("zh", null)
            val service = AddressingServiceFixture(); val model = addressingModel(service)
            try {
                setContent { TestWorkbenchTheme { Box(Modifier.fillMaxSize().padding(16.dp)) { RobotAddressingPage(model, onBack = {}) } } }
                model.connect("127.0.0.1", "12345678")
                waitUntil(timeoutMillis = 5000) { model.state.value.connected && !model.state.value.busy }
                model.robotAddressing.begin(ModuleAddressingKind.ARMOR)
                waitUntil(timeoutMillis = 3000) { service.state.value.phase == AddressingPhase.ADDRESSING && !model.state.value.busy }
                onNodeWithTag("addressing-armor-next").assertTextContains("后装甲", substring = true)
                onNodeWithTag("addressing-armor-skip").performClick()
                runOnIdle { service.state.value = service.state.value.copy(armorSteps = 1) }
                waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("addressing-confirm").fetchSemanticsNodes().isEmpty() }
                onNodeWithTag("addressing-confirm").assertDoesNotExist()
                onNodeWithTag("addressing-armor-next").assertTextContains("前装甲", substring = true)
                assertTrue(service.calls.none { it == "skip" })
                model.robotAddressing.end()
                waitUntil(timeoutMillis = 3000) { !model.robotAddressing.state.value.busy && !model.state.value.busy }
                onNodeWithTag("addressing-choose-another").performClick()
                onNodeWithTag("addressing-kind-sensor_adapter").performScrollTo().performClick()
                onNodeWithTag("addressing-start").performClick()
                waitUntil(timeoutMillis = 3000) { service.state.value.kind == ModuleAddressingKind.SENSOR_ADAPTER && !model.state.value.busy }
                onNodeWithTag("addressing-confirm").assertDoesNotExist()
                onNodeWithTag("addressing-adapter-id-6").performClick()
                onNodeWithTag("addressing-adapter-led-2").assertContentDescriptionEquals("指示灯 2 应亮")
                onNodeWithTag("addressing-adapter-led-4").assertContentDescriptionEquals("指示灯 4 应亮")
                onNodeWithTag("addressing-adapter-led-1").assertContentDescriptionEquals("指示灯 1 应灭")
                onNodeWithTag("addressing-adapter-confirm").assertIsNotEnabled()
                runOnIdle { service.state.value = service.state.value.copy(inventoryKnown = true, onlineIds = setOf(6)) }
                onNodeWithTag("addressing-adapter-confirm").performScrollTo().performClick()
                assertEquals(setOf(6), service.state.value.verifiedIds)
                onNodeWithTag("addressing-end").performClick()
                waitUntil(timeoutMillis = 3000) { !model.robotAddressing.state.value.busy && !model.state.value.busy &&
                    onAllNodesWithTag("addressing-result").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("addressing-result").assertTextEquals("编号引导已关闭")
                onNodeWithTag("addressing-confirm").assertDoesNotExist()
            } finally { model.close() }
        }

    @Test fun reconnectShowsTheOriginalTargetAndRecoveryOnlyRunsAfterConfirmation() =
        runDesktopComposeUiTest(width = 1024, height = 600) {
            Localization.initialize("zh", null)
            val old = AddressingServiceFixture(); val fresh = AddressingServiceFixture()
            val sessions = listOf(old, fresh).map { source -> RobotSessionFixture().apply {
                addressing = source; health.value = RobotHealth(modules = emptyList())
            } }
            val model = testConsoleModel(robotRuntime = AddressingRuntime(sessions))
            try {
                setContent { TestWorkbenchTheme { Box(Modifier.fillMaxSize().padding(16.dp)) { RobotAddressingPage(model, onBack = {}) } } }
                model.connect("127.0.0.1", "12345678")
                waitUntil(timeoutMillis = 5000) { model.state.value.connected && !model.state.value.busy }
                model.robotAddressing.begin(ModuleAddressingKind.TOF)
                waitUntil(timeoutMillis = 3000) { serviceIdle(model) && old.state.value.blocksControl }
                model.pauseConnection()
                waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("addressing-reconnect").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("127.0.0.1", substring = true).assertExists()
                onNodeWithTag("addressing-reconnect").performClick()
                waitUntil(timeoutMillis = 5000) { model.state.value.connected && serviceIdle(model) }
                assertTrue(fresh.calls.none { it.startsWith("recover:") || it.startsWith("begin:") || it.startsWith("assign:") })
                onNodeWithTag("addressing-recover").performClick(); onNodeWithTag("addressing-confirm-cancel").performClick()
                waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("addressing-confirm").fetchSemanticsNodes().isEmpty() }
                assertTrue(fresh.calls.none { it.startsWith("recover:") })
                onNodeWithTag("addressing-recover").performClick(); onNodeWithTag("addressing-confirm").performClick()
                waitUntil(timeoutMillis = 3000) { model.robotAddressing.state.value.recovery == null && serviceIdle(model) }
                assertEquals(listOf("recover:TOF"), fresh.calls.filterNot { it.startsWith("hold:") })
                assertFalse(model.remoteEnabled.value)
            } finally { model.close() }
        }

    @Test fun settingsEntryOwnsNavigationUntilTheUserEndsOrCancelsTheBackRequest() =
        runDesktopComposeUiTest(width = 1024, height = 600) {
            Localization.initialize("zh", null)
            val service = AddressingServiceFixture(); val model = addressingModel(service)
            try {
                setContent { TestWorkbenchTheme { Console(model, remember { mutableStateOf(EditorDocument()) }) } }
                model.connect("127.0.0.1", "12345678")
                waitUntil(timeoutMillis = 5000) { model.state.value.connected && !model.state.value.busy }
                onNodeWithTag("navigate-4").performClick()
                onNodeWithTag("settings-category-robot").performClick()
                onNodeWithTag("settings-addressing-open").performScrollTo().performClick()
                onNodeWithTag("robot-addressing-page").assertIsDisplayed()
                onNodeWithTag("navigate-0").assertDoesNotExist(); onNodeWithTag("bottom-navigation").assertDoesNotExist()
                model.robotAddressing.begin(ModuleAddressingKind.TOF)
                waitUntil(timeoutMillis = 3000) { service.state.value.phase == AddressingPhase.DISCOVERING && !model.state.value.busy }
                onNodeWithTag("addressing-back").performClick(); onNodeWithTag("addressing-confirm-cancel").performClick()
                waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("addressing-confirm").fetchSemanticsNodes().isEmpty() }
                onNodeWithTag("robot-addressing-page").assertIsDisplayed(); assertTrue(service.calls.none { it == "end" })
                onNodeWithTag("addressing-back").performClick(); onNodeWithTag("addressing-confirm").performClick()
                waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("addressing-confirm").fetchSemanticsNodes().isEmpty() }
                waitUntil(timeoutMillis = 3000) { !model.robotAddressing.state.value.busy && !model.state.value.busy }
                onNodeWithTag("robot-addressing-page").assertDoesNotExist()
                onNodeWithTag("settings-addressing-open").assertExists()
                assertEquals(1, service.calls.count { it == "end" })
            } finally { model.close() }
        }
}

@OptIn(ExperimentalTestApi::class)
class RobotAddressingLayoutTest {
    @Test fun compactLandscape() = layout(844, 390)
    @Test fun tabletLandscape() = layout(1024, 600, dark = true)
    @Test fun desktopLandscape() = layout(1280, 720)
    @Test fun largeSupportedLanguages() { AppLanguage.entries.forEach { layout(844, 390, it.preference, 1.4f) } }
    private fun layout(width: Int, height: Int, language: String = "zh", scale: Float = 1f, dark: Boolean = false) =
        runDesktopComposeUiTest(width = width, height = height) {
            Localization.initialize(language, null)
            val service = AddressingServiceFixture(); val model = addressingModel(service)
            val prefix = "addressing-$width-$height-$language-$scale-${if (dark) "dark" else "light"}"
            try {
                setContent { TestWorkbenchTheme(AppearanceController(AppearanceSettings(if (dark) NightMode.DARK else NightMode.LIGHT))) { CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                    Box(Modifier.fillMaxSize().padding(16.dp).testTag("addressing-layout")) { RobotAddressingPage(model, onBack = {}) }
                } } }
                model.connect("127.0.0.1", "12345678")
                waitUntil(timeoutMillis = 5000) { model.state.value.connected && !model.state.value.busy }
                onNodeWithTag("addressing-kind-wheels").performScrollTo().performClick()
                onNodeWithTag("addressing-start").performScrollTo().assertIsDisplayed()
                val headerBounds = onNodeWithTag("addressing-header").fetchSemanticsNode().boundsInRoot
                saveAddressing("$prefix-prepare", onNodeWithTag("addressing-layout"))
                onNodeWithTag("addressing-start").performClick()
                onNodeWithTag("addressing-confirm").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
                saveAddressing("$prefix-start", onNode(isRoot() and hasAnyDescendant(hasTestTag("addressing-confirm"))))
                onNodeWithTag("addressing-confirm-cancel").performClick()
                waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("addressing-confirm").fetchSemanticsNodes().isEmpty() }
                runOnIdle { service.state.value = RobotAddressingState(ModuleAddressingKind.WHEELS, AddressingPhase.VERIFYING, wheelTestReady = true) }
                waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("addressing-wheel-hold-front_right").fetchSemanticsNodes().isNotEmpty() }
                val hold = onNodeWithTag("addressing-wheel-hold-front_right").performScrollTo().assertIsDisplayed()
                assertTrue(hold.fetchSemanticsNode().boundsInRoot.height >= 48)
                saveAddressing("$prefix-wheels", onNodeWithTag("addressing-layout"))
                onNodeWithTag("addressing-wheel-hold-rear_right").performScrollTo().assertIsDisplayed()
                onNodeWithTag("addressing-end").assertIsDisplayed()
                runOnIdle { service.state.value = RobotAddressingState(ModuleAddressingKind.SERVO, AddressingPhase.DISCOVERING,
                    inventoryKnown = true, candidateId = 4, reservedIds = setOf(1, 2)) }
                waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("addressing-id-3").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("addressing-id-3").performScrollTo().performClick()
                onNodeWithTag("addressing-assign").performScrollTo().assertIsDisplayed()
                onNodeWithTag("addressing-header").assertIsDisplayed()
                assertEquals(headerBounds, onNodeWithTag("addressing-header").fetchSemanticsNode().boundsInRoot)
                onNodeWithText(tr(Res.string.addressing_title)).assertIsDisplayed()
                saveAddressing("$prefix-servo", onNodeWithTag("addressing-layout"))
                runOnIdle { service.state.value = RobotAddressingState(ModuleAddressingKind.SENSOR_ADAPTER, AddressingPhase.VERIFYING,
                    inventoryKnown = true, onlineIds = setOf(6)) }
                waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("addressing-adapter-id-6").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("addressing-adapter-id-6").performScrollTo().performClick()
                onNodeWithTag("addressing-adapter-led-4").performScrollTo().assertIsDisplayed()
                onNodeWithTag("addressing-header").assertIsDisplayed()
                assertEquals(headerBounds, onNodeWithTag("addressing-header").fetchSemanticsNode().boundsInRoot)
                onNodeWithText(tr(Res.string.addressing_title)).assertIsDisplayed()
                saveAddressing("$prefix-adapter", onNodeWithTag("addressing-layout"))
                onNodeWithTag("addressing-adapter-confirm").performScrollTo().assertIsDisplayed()
                runOnIdle { service.state.value = RobotAddressingState(ModuleAddressingKind.WHEELS, AddressingPhase.UNCERTAIN, problem = AddressingProblem.RESULT_UNKNOWN) }
                waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("addressing-problem").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("addressing-problem").performScrollTo().assertIsDisplayed()
                onNodeWithTag("addressing-end").performClick(); onNodeWithTag("addressing-confirm").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
                saveAddressing("$prefix-end", onNode(isRoot() and hasAnyDescendant(hasTestTag("addressing-confirm"))))
                onNodeWithTag("addressing-confirm-cancel").performClick()
                waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("addressing-confirm").fetchSemanticsNodes().isEmpty() }
                model.pauseConnection()
                waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("addressing-reconnect").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("addressing-reconnect").performScrollTo().assertIsDisplayed()
                saveAddressing("$prefix-recovery", onNodeWithTag("addressing-layout"))
            } finally { model.close(); Localization.initialize("zh", null) }
        }
}

private fun serviceIdle(model: ConsoleModel): Boolean = !model.state.value.busy && !model.robotAddressing.state.value.busy

private fun saveAddressing(name: String, node: SemanticsNodeInteraction) {
    val image = node.captureToImage(); val pixels = IntArray(image.width * image.height); image.readPixels(pixels)
    val output = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
    output.setRGB(0, 0, image.width, image.height, pixels, 0, image.width)
    ImageIO.write(output, "png", File("build/reports/ui/$name.png").apply { parentFile.mkdirs() })
}
