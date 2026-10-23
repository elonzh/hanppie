package cn.elonzh.hanppie.ui.robot.maintenance

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cn.elonzh.hanppie.robot.maintenance.*
import cn.elonzh.hanppie.robot.product.WorkingRobotDevice
import cn.elonzh.hanppie.robot.session.RequestProblem
import cn.elonzh.hanppie.robot.session.RobotRequestException
import cn.elonzh.hanppie.robot.telemetry.RobotHealth
import cn.elonzh.hanppie.ui.app.*
import cn.elonzh.hanppie.ui.design.TestWorkbenchTheme
import cn.elonzh.hanppie.ui.i18n.Localization
import cn.elonzh.hanppie.ui.scripts.EditorDocument
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

private fun calibrationSession(service: CalibrationServiceFixture) = RobotSessionFixture().apply {
    calibration = service
    health.value = RobotHealth(modules = listOf(WorkingRobotDevice(0x0400, emptyList()), WorkingRobotDevice(0x031e, emptyList())))
}

@OptIn(ExperimentalTestApi::class)
class RobotCalibrationUiTest {
    @Test fun fivePoseReportsAndForegroundPauseRequireAnExplicitGimbalContinuation() =
        runDesktopComposeUiTest(width = 1024, height = 600) {
            Localization.initialize("zh", null)
            val service = CalibrationServiceFixture(); val model = testConsoleModel(robotRuntime = AddressingRuntime(listOf(calibrationSession(service))))
            try {
                setContent { TestWorkbenchTheme { Box(Modifier.fillMaxSize().padding(16.dp)) { RobotCalibrationPage(model, onBack = {}) } } }
                model.connect("127.0.0.1", "12345678")
                waitUntil(timeoutMillis = 5000) { model.state.value.connected && !model.state.value.busy }
                onNodeWithTag("calibration-kind-chassis_and_gimbal").performClick()
                onNodeWithTag("calibration-start").performClick(); onNodeWithTag("calibration-confirm-cancel").performClick()
                waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("calibration-confirm").fetchSemanticsNodes().isEmpty() }
                assertTrue(service.calls.isEmpty())
                onNodeWithTag("calibration-start").performClick(); onNodeWithTag("calibration-confirm").performClick()
                waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("calibration-confirm").fetchSemanticsNodes().isEmpty() && !model.state.value.busy }
                runOnIdle { service.state.value = service.state.value.copy(imuStatusRaw = 3, imuProgressRaw = 20, imuSideRaw = 4, finishedMaskRaw = 1) }
                waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("calibration-current-pose").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("calibration-current-pose").assertTextContains("水平", substring = true)
                onNodeWithTag("calibration-pose-guidance").assertTextContains("前端朝下", substring = true)
                onNodeWithTag("calibration-side-horizontal").onChildren().filter(hasText("已报告完成")).assertCountEquals(1)
                runOnIdle {
                    model.setForeground(false)
                    service.state.value = service.state.value.copy(phase = CalibrationPhase.WAITING_LEVEL, levelReady = false)
                    model.setForeground(true)
                }
                waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("calibration-continue-gimbal").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("calibration-continue-gimbal").assertIsNotEnabled()
                assertTrue(service.calls.none { it == "gimbal" })
                runOnIdle { service.state.value = service.state.value.copy(levelReady = true) }
                onNodeWithTag("calibration-continue-gimbal").performClick()
                waitUntil(timeoutMillis = 3000) { service.calls.contains("gimbal") && !model.state.value.busy }
                onNodeWithTag("calibration-stop").assertDoesNotExist()
                runOnIdle { service.state.value = service.state.value.copy(gimbalProgressRaw = 100, gimbalStatusRaw = 1) }
                onNodeWithTag("calibration-result").assertDoesNotExist()
                runOnIdle { service.state.value = service.state.value.copy(phase = CalibrationPhase.SUCCEEDED, gimbalStatusRaw = 0) }
                waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("calibration-result").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("calibration-result").assertTextEquals("机器人已报告校准完成")
            } finally { model.close() }
        }

    @Test fun armPlacementConfirmationAndExplicitRetryNeverTurnAnAckIntoPhysicalSuccess() =
        runDesktopComposeUiTest(width = 1024, height = 600) {
            Localization.initialize("zh", null)
            val service = CalibrationServiceFixture().apply { onBegin = { throw RobotRequestException(RequestProblem.TIMED_OUT) } }
            val model = testConsoleModel(robotRuntime = AddressingRuntime(listOf(calibrationSession(service))))
            try {
                setContent { TestWorkbenchTheme { Box(Modifier.fillMaxSize().padding(16.dp)) { RobotCalibrationPage(model, onBack = {}) } } }
                model.connect("127.0.0.1", "12345678")
                waitUntil(timeoutMillis = 5000) { model.state.value.connected && !model.state.value.busy }
                onNodeWithTag("calibration-kind-arm").performClick()
                onNodeWithText("1. 向后转动一级臂至舵机限位。").assertIsDisplayed()
                onNodeWithText("2. 保持一级臂不动，向下转动二级臂至舵机限位。").assertIsDisplayed()
                onNodeWithTag("calibration-start").performClick(); onNodeWithTag("calibration-confirm-cancel").performClick()
                waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("calibration-confirm").fetchSemanticsNodes().isEmpty() }
                assertTrue(service.calls.isEmpty())
                onNodeWithTag("calibration-start").performClick(); onNodeWithTag("calibration-confirm").performClick()
                waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("calibration-confirm").fetchSemanticsNodes().isEmpty() && !model.state.value.busy }
                onNodeWithTag("calibration-arm-retry").performScrollTo().performClick()
                onNodeWithTag("calibration-confirm-cancel").performClick()
                waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("calibration-confirm").fetchSemanticsNodes().isEmpty() }
                assertEquals(listOf("begin:ARM"), service.calls)
                onNodeWithTag("calibration-arm-retry").performClick(); onNodeWithTag("calibration-confirm").performClick()
                waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("calibration-confirm").fetchSemanticsNodes().isEmpty() && !model.state.value.busy }
                onNodeWithTag("calibration-result").assertTextEquals("机器人已应答机械臂校准请求")
                assertEquals(listOf("begin:ARM", "retry-arm"), service.calls)
                onNodeWithTag("calibration-stop").assertDoesNotExist()
            } finally { model.close() }
        }

    @Test fun recoveryRequiresTheOriginalTargetObservationAndSeparateRestartConfirmation() =
        runDesktopComposeUiTest(width = 1024, height = 600) {
            Localization.initialize("zh", null)
            val old = CalibrationServiceFixture(); val wrong = CalibrationServiceFixture(); val fresh = CalibrationServiceFixture()
            val runtime = AddressingRuntime(listOf(old, wrong, fresh).map(::calibrationSession))
            val model = testConsoleModel(robotRuntime = runtime)
            try {
                setContent { TestWorkbenchTheme { Box(Modifier.fillMaxSize().padding(16.dp)) { RobotCalibrationPage(model, onBack = {}) } } }
                model.connect("127.0.0.1", "12345678")
                waitUntil(timeoutMillis = 5000) { model.state.value.connected && !model.state.value.busy }
                model.robotCalibration.begin(CalibrationKind.GIMBAL)
                waitUntil(timeoutMillis = 3000) { serviceIdle(model) && old.state.value.phase == CalibrationPhase.GIMBAL }
                model.pauseConnection()
                waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("calibration-reconnect").fetchSemanticsNodes().isNotEmpty() }
                model.connect("127.0.0.2", "12345678")
                waitUntil(timeoutMillis = 5000) { model.state.value.connected && !model.state.value.busy }
                onNodeWithTag("calibration-observe").assertDoesNotExist(); assertTrue(wrong.calls.isEmpty())
                model.disconnect(); onNodeWithTag("calibration-reconnect").performClick()
                waitUntil(timeoutMillis = 5000) { model.state.value.connected && !model.state.value.busy }
                assertEquals(runtime.targets[0], runtime.targets[2]); assertTrue(fresh.calls.isEmpty())
                onNodeWithTag("calibration-observe").performClick(); onNodeWithTag("calibration-confirm-cancel").performClick()
                waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("calibration-confirm").fetchSemanticsNodes().isEmpty() }
                assertTrue(fresh.calls.isEmpty())
                onNodeWithTag("calibration-observe").performClick(); onNodeWithTag("calibration-confirm").performClick()
                waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("calibration-confirm").fetchSemanticsNodes().isEmpty() && serviceIdle(model) }
                assertEquals(listOf("observe"), fresh.calls)
                runOnIdle { fresh.state.value = fresh.state.value.copy(gimbalStatusRaw = 1) }
                onNodeWithTag("calibration-restarted").performScrollTo().assertIsNotEnabled()
                runOnIdle { fresh.state.value = fresh.state.value.copy(gimbalStatusRaw = null) }
                waitUntil(timeoutMillis = 3000) { runCatching { onNodeWithTag("calibration-restarted").assertIsEnabled() }.isSuccess }
                onNodeWithTag("calibration-restarted").performClick(); onNodeWithTag("calibration-confirm-cancel").performClick()
                waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("calibration-confirm").fetchSemanticsNodes().isEmpty() }
                assertEquals(listOf("observe"), fresh.calls)
                onNodeWithTag("calibration-restarted").performClick(); onNodeWithTag("calibration-confirm").performClick()
                waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("calibration-confirm").fetchSemanticsNodes().isEmpty() && model.robotCalibration.state.value.recovery == null }
                onNodeWithTag("calibration-result").assertTextEquals("你已确认原机器人完成重启")
                assertEquals(listOf("observe", "restart-confirmed"), fresh.calls)
            } finally { model.close() }
        }

    @Test fun anUnconfirmedImuStopKeepsTheFlowAndOffersAnExplicitRetry() =
        runDesktopComposeUiTest(width = 1024, height = 600) {
            Localization.initialize("zh", null)
            val service = CalibrationServiceFixture().apply { onCancel = { throw RobotRequestException(RequestProblem.TIMED_OUT) } }
            val model = testConsoleModel(robotRuntime = AddressingRuntime(listOf(calibrationSession(service))))
            try {
                setContent { TestWorkbenchTheme { Box(Modifier.fillMaxSize().padding(16.dp)) { RobotCalibrationPage(model, onBack = {}) } } }
                model.connect("127.0.0.1", "12345678")
                waitUntil(timeoutMillis = 5000) { model.state.value.connected && !model.state.value.busy }
                model.robotCalibration.begin(CalibrationKind.CHASSIS_AND_GIMBAL)
                waitUntil(timeoutMillis = 3000) { serviceIdle(model) && service.state.value.phase == CalibrationPhase.CHASSIS }
                onNodeWithTag("calibration-stop").performClick(); onNodeWithTag("calibration-confirm").performClick()
                waitUntil(timeoutMillis = 3000) { serviceIdle(model) && onAllNodesWithTag("calibration-confirm").fetchSemanticsNodes().isEmpty() }
                onNodeWithTag("calibration-problem").assertExists(); onNodeWithTag("calibration-result").assertDoesNotExist()
                assertTrue(model.robotCalibration.state.value.blocksControl)
                service.onCancel = {}
                onNodeWithTag("calibration-stop").performClick(); onNodeWithTag("calibration-confirm").performClick()
                waitUntil(timeoutMillis = 3000) { serviceIdle(model) && onAllNodesWithTag("calibration-result").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("calibration-result").assertTextEquals("机器人已应答 IMU 停止请求")
                assertEquals(2, service.calls.count { it == "cancel" }); assertFalse(model.remoteEnabled.value)
            } finally { model.close() }
        }

    @Test fun realMaintenanceNavigationLeavesTheUncancellableGimbalRunningAndImuExitWaitsForAck() =
        runDesktopComposeUiTest(width = 1024, height = 600) {
            Localization.initialize("zh", null)
            val service = CalibrationServiceFixture(); val model = testConsoleModel(robotRuntime = AddressingRuntime(listOf(calibrationSession(service))))
            try {
                setContent { TestWorkbenchTheme { Console(model, remember { mutableStateOf(EditorDocument()) }) } }
                model.connect("127.0.0.1", "12345678")
                waitUntil(timeoutMillis = 5000) { model.state.value.connected && !model.state.value.busy }
                onNodeWithTag("navigate-4").performClick(); onNodeWithTag("settings-category-robot").performClick()
                onNodeWithTag("settings-calibration-open").performScrollTo().performClick()
                onNodeWithTag("robot-calibration-page").assertIsDisplayed(); onNodeWithTag("navigate-0").assertDoesNotExist()
                model.robotCalibration.begin(CalibrationKind.GIMBAL)
                waitUntil(timeoutMillis = 3000) { serviceIdle(model) && service.state.value.phase == CalibrationPhase.GIMBAL }
                onNodeWithTag("calibration-back").performClick(); onNodeWithTag("calibration-confirm-cancel").performClick()
                waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("calibration-confirm").fetchSemanticsNodes().isEmpty() }
                onNodeWithTag("robot-calibration-page").assertIsDisplayed()
                onNodeWithTag("calibration-back").performClick(); onNodeWithTag("calibration-confirm").performClick()
                waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("robot-calibration-page").fetchSemanticsNodes().isEmpty() }
                assertTrue(service.calls.none { it == "cancel" }); assertTrue(model.robotCalibration.state.value.blocksControl)
                model.enableRemote(); waitUntil(timeoutMillis = 3000) { !model.state.value.busy }; assertFalse(model.remoteEnabled.value)
                onNodeWithTag("settings-calibration-open").performScrollTo().performClick()
                runOnIdle { service.state.value = service.state.value.copy(phase = CalibrationPhase.SUCCEEDED) }
                waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("calibration-result").fetchSemanticsNodes().isNotEmpty() }
                model.robotCalibration.begin(CalibrationKind.CHASSIS_AND_GIMBAL)
                waitUntil(timeoutMillis = 3000) { serviceIdle(model) && service.state.value.phase == CalibrationPhase.CHASSIS }
                onNodeWithTag("calibration-back").performClick(); onNodeWithTag("calibration-confirm").performClick()
                waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("robot-calibration-page").fetchSemanticsNodes().isEmpty() && serviceIdle(model) }
                assertEquals(1, service.calls.count { it == "cancel" })
                assertFalse(model.robotCalibration.state.value.blocksControl)
            } finally { model.close() }
        }
}

private fun serviceIdle(model: ConsoleModel): Boolean = !model.state.value.busy && !model.robotCalibration.state.value.busy

@OptIn(ExperimentalTestApi::class)
class RobotCalibrationLayoutTest {
    @Test fun compactLandscape() = layout(844, 390)
    @Test fun tabletLandscape() = layout(1024, 600)
    @Test fun desktopLandscape() = layout(1280, 720)
    @Test fun englishLargeText() = layout(844, 390, "en", 1.4f)
    private fun layout(width: Int, height: Int, language: String = "zh", scale: Float = 1f) =
        runDesktopComposeUiTest(width = width, height = height) {
            Localization.initialize(language, null)
            val service = CalibrationServiceFixture(); val fresh = CalibrationServiceFixture()
            val model = testConsoleModel(robotRuntime = AddressingRuntime(listOf(calibrationSession(service), calibrationSession(fresh))))
            val prefix = "calibration-$width-$height-$language"
            try {
                setContent { TestWorkbenchTheme { CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                    Box(Modifier.fillMaxSize().padding(16.dp).testTag("calibration-layout")) { RobotCalibrationPage(model, onBack = {}) }
                } } }
                model.connect("127.0.0.1", "12345678")
                waitUntil(timeoutMillis = 5000) { model.state.value.connected && !model.state.value.busy }
                onNodeWithTag("calibration-kind-chassis_and_gimbal").performClick()
                onNodeWithTag("calibration-start").performScrollTo().assertIsDisplayed()
                val header = onNodeWithTag("calibration-header").fetchSemanticsNode().boundsInRoot
                saveCalibration("$prefix-prepare", onNodeWithTag("calibration-layout"))
                onNodeWithTag("calibration-start").performClick()
                onNodeWithTag("calibration-confirm").assertIsDisplayed()
                saveCalibration("$prefix-start", onNode(isRoot() and hasAnyDescendant(hasTestTag("calibration-confirm"))))
                onNodeWithTag("calibration-confirm-cancel").performClick()
                waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("calibration-confirm").fetchSemanticsNodes().isEmpty() }
                runOnIdle { service.state.value = RobotCalibrationState(CalibrationKind.CHASSIS_AND_GIMBAL, CalibrationPhase.CHASSIS,
                    imuStatusRaw = 3, imuProgressRaw = 25, imuSideRaw = 4, finishedMaskRaw = 1) }
                waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("calibration-current-pose").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("calibration-current-pose").performScrollTo().assertIsDisplayed()
                saveCalibration("$prefix-poses", onNodeWithTag("calibration-layout"))
                onNodeWithTag("calibration-stop").performClick(); onNodeWithTag("calibration-confirm").assertIsDisplayed()
                saveCalibration("$prefix-stop", onNode(isRoot() and hasAnyDescendant(hasTestTag("calibration-confirm"))))
                onNodeWithTag("calibration-confirm-cancel").performClick()
                waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("calibration-confirm").fetchSemanticsNodes().isEmpty() }
                onNodeWithTag("calibration-side-right").performScrollTo().assertIsDisplayed()
                onNodeWithTag("calibration-stop").assertIsDisplayed()
                assertTrue(onNodeWithTag("calibration-stop").fetchSemanticsNode().boundsInRoot.height >= 48)
                assertEquals(header, onNodeWithTag("calibration-header").fetchSemanticsNode().boundsInRoot)
                runOnIdle { service.state.value = service.state.value.copy(phase = CalibrationPhase.WAITING_LEVEL, levelReady = true) }
                waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("calibration-continue-gimbal").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("calibration-continue-gimbal").performScrollTo().assertIsDisplayed()
                saveCalibration("$prefix-level", onNodeWithTag("calibration-layout"))
                runOnIdle { service.state.value = RobotCalibrationState(CalibrationKind.GIMBAL, CalibrationPhase.UNCERTAIN,
                    problem = CalibrationProblem.GIMBAL_TIMEOUT, gimbalMayBeActive = true, gimbalStatusRaw = 1, gimbalProgressRaw = 66) }
                waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("calibration-problem").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("calibration-problem").performScrollTo().assertIsDisplayed()
                saveCalibration("$prefix-timeout", onNodeWithTag("calibration-layout"))
                onNodeWithTag("calibration-restarted").assertDoesNotExist()
                onNodeWithTag("calibration-restart-help").performScrollTo().assertIsDisplayed()
                onNodeWithTag("calibration-restart-help").assertTextContains(
                    if (language == "zh") "观测上次任务" else "Observe the previous task", substring = true)
                saveCalibration("$prefix-timeout-recovery", onNodeWithTag("calibration-layout"))
                runOnIdle { service.state.value = RobotCalibrationState() }
                waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("calibration-choose-another").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("calibration-choose-another").performScrollTo().performClick()
                onNodeWithTag("calibration-kind-arm").performScrollTo().performClick()
                onNodeWithTag("calibration-start").performScrollTo().assertIsDisplayed()
                saveCalibration("$prefix-arm", onNodeWithTag("calibration-layout"))
                onNodeWithTag("calibration-start").performClick()
                onNodeWithTag("calibration-confirm").assertIsDisplayed()
                saveCalibration("$prefix-arm-confirm", onNode(isRoot() and hasAnyDescendant(hasTestTag("calibration-confirm"))))
                onNodeWithTag("calibration-confirm-cancel").performClick()
                waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("calibration-confirm").fetchSemanticsNodes().isEmpty() }
                runOnIdle { service.state.value = RobotCalibrationState(CalibrationKind.GIMBAL, CalibrationPhase.UNCERTAIN,
                    problem = CalibrationProblem.REQUEST_UNKNOWN, gimbalMayBeActive = true) }
                model.pauseConnection()
                waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("calibration-reconnect").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("calibration-reconnect").performScrollTo().assertIsDisplayed()
                saveCalibration("$prefix-recovery", onNodeWithTag("calibration-layout"))
                onNodeWithTag("calibration-reconnect").performClick()
                waitUntil(timeoutMillis = 5000) { model.state.value.connected && serviceIdle(model) }
                onNodeWithTag("calibration-observe").performScrollTo().performClick(); onNodeWithTag("calibration-confirm").performClick()
                waitUntil(timeoutMillis = 3000) { serviceIdle(model) && onAllNodesWithTag("calibration-confirm").fetchSemanticsNodes().isEmpty() }
                onNodeWithTag("calibration-restarted").performScrollTo().performClick(); onNodeWithTag("calibration-confirm").assertIsDisplayed()
                saveCalibration("$prefix-restart", onNode(isRoot() and hasAnyDescendant(hasTestTag("calibration-confirm"))))
                onNodeWithTag("calibration-confirm-cancel").performClick()
                waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("calibration-confirm").fetchSemanticsNodes().isEmpty() }
            } finally { model.close(); Localization.initialize("zh", null) }
        }
}

private fun saveCalibration(name: String, node: SemanticsNodeInteraction) {
    val image = node.captureToImage(); val pixels = IntArray(image.width * image.height); image.readPixels(pixels)
    val output = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
    output.setRGB(0, 0, image.width, image.height, pixels, 0, image.width)
    ImageIO.write(output, "png", File("build/reports/ui/$name.png").apply { parentFile.mkdirs() })
}
