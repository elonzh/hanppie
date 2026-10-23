package cn.elonzh.hanppie.ui.robot.arm

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import cn.elonzh.hanppie.robot.extensions.arm.*
import cn.elonzh.hanppie.ui.app.*
import cn.elonzh.hanppie.ui.design.TestWorkbenchTheme
import cn.elonzh.hanppie.ui.i18n.Localization
import cn.elonzh.hanppie.ui.robot.remote.RemotePage
import cn.elonzh.hanppie.ui.scripts.EditorDocument
import kotlinx.coroutines.*
import org.junit.Test
import kotlin.test.*

internal fun armSession(service: ArmServiceFixture) = RobotSessionFixture().apply { arm = service }
internal fun armIdle(model: ConsoleModel) = !model.state.value.busy && !model.robotArm.state.value.busy

@OptIn(ExperimentalTestApi::class)
class RobotArmUiTest {
    @Test fun staleReportsWaitAutomaticallyAndFailedObservationHasAReadOnlyRetryWithoutResumingInput() = runDesktopComposeUiTest(width = 844, height = 390) {
        Localization.initialize("en", null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val service = ArmServiceFixture()
        val controller = RobotArmController(scope, { _, action -> action() }, { true }, { true })
        try {
            controller.attach(service, cn.elonzh.hanppie.robot.session.RobotTarget("127.0.0.1", "12345678")); controller.setActive(true)
            setContent { TestWorkbenchTheme { RobotArmControls(controller, true, true, Modifier.padding(20.dp)) } }
            val hold = onNodeWithTag("arm-hold-forward")
            hold.performTouchInput { down(center) }; waitUntil(timeoutMillis = 3000) { service.state.value.controlActive }
            runOnIdle { service.state.value = service.state.value.copy(positionFresh = false, clawFresh = false, controlArmed = false, controlActive = false) }
            hold.assertIsNotEnabled(); onNodeWithTag("arm-retry-status").assertDoesNotExist()
            onNodeWithTag("remote-arm-status").assertTextContains("Waiting for fresh component reports", substring = true)
            val inputCount = service.inputs.size
            runOnIdle { service.state.value = service.state.value.copy(observation = ArmObservation.FAILED) }
            waitUntil(timeoutMillis = 3000) { controller.state.value.task.observation == ArmObservation.FAILED }
            onNodeWithTag("arm-retry-status").assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick) { it() }
            assertEquals(listOf("observe:true", "read"), service.calls.takeLast(2), "retry state=${controller.state.value} calls=${service.calls}")
            waitUntil(timeoutMillis = 3000) { service.state.value.observation == ArmObservation.ACTIVE && !controller.state.value.busy }
            assertEquals(listOf("observe:true", "read"), service.calls.takeLast(2))
            runOnIdle { service.state.value = service.state.value.copy(positionFresh = true, clawFresh = true) }
            hold.assertIsEnabled(); assertEquals(inputCount, service.inputs.size); assertFalse(service.state.value.controlArmed)
            hold.performTouchInput { up(); down(center) }; waitUntil(timeoutMillis = 3000) { service.state.value.controlActive }
            hold.performTouchInput { up() }; waitUntil(timeoutMillis = 3000) { !service.state.value.controlArmed }
            assertTrue(service.calls.none { it.startsWith("bind:") || it.startsWith("mode:") })
        } finally { controller.clear(); scope.cancel() }
    }

    @Test fun bindingAndLoadChangesNeedConfirmationAndAcknowledgementDoesNotInventModeReports() = runDesktopComposeUiTest(width = 1024, height = 600) {
        Localization.initialize("zh", null)
        val service = ArmServiceFixture(); val model = testConsoleModel(robotRuntime = AddressingRuntime(listOf(armSession(service))))
        try {
            setContent { TestWorkbenchTheme { RobotArmPage(model, onBack = {}, onAddressing = {}, onCalibration = {}) } }
            model.connect("127.0.0.1", "12345678"); waitUntil(timeoutMillis = 5000) { model.state.value.connected && armIdle(model) }
            model.robotArm.setActive(true); waitUntil(timeoutMillis = 3000) { service.state.value.observation == ArmObservation.ACTIVE && armIdle(model) }
            onNodeWithTag("arm-main-3").performScrollTo().performClick()
            onNodeWithTag("arm-identify-main").performClick(); waitUntil(timeoutMillis = 3000) { service.calls.contains("identify:3") && armIdle(model) }
            onNodeWithTag("arm-bind").performScrollTo().performClick(); onNodeWithTag("arm-confirm-cancel").performClick()
            waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("arm-confirm").fetchSemanticsNodes().isEmpty() }
            assertFalse(service.calls.any { it.startsWith("bind:") })
            onNodeWithTag("arm-bind").performClick(); onNodeWithTag("arm-confirm").performClick()
            waitUntil(timeoutMillis = 3000) { service.state.value.binding == ArmServoBinding(3, 2) && armIdle(model) }
            onNodeWithTag("arm-load-on").performScrollTo().performClick(); onNodeWithTag("arm-confirm").performClick()
            waitUntil(timeoutMillis = 3000) { service.state.value.loadModeAcknowledged == true && armIdle(model) }
            assertEquals(0, service.state.value.loadModeRaw)
            onNodeWithTag("arm-load-result").performScrollTo().assertTextContains("等待新的模式报告", substring = true)
            runOnIdle { service.state.value = service.state.value.copy(loadModeRaw = 1, loadModeMatchesRequest = true) }
            waitUntil(timeoutMillis = 3000) { model.robotArm.state.value.task.loadModeRaw == 1 }
            onNodeWithTag("arm-load-status").assertTextContains("带载已启用", substring = true)
            onNodeWithTag("arm-uninstall").performScrollTo().performClick(); onNodeWithTag("arm-confirm").performClick()
            waitUntil(timeoutMillis = 3000) { service.state.value.binding?.uninstalled == true && armIdle(model) }
            assertEquals(listOf("mode:ON_LOAD"), service.calls.filter { it.startsWith("mode:") })
        } finally { model.close() }
    }

    @Test fun reportChangesAndNewConnectionsInvalidateOpenConfirmations() = runDesktopComposeUiTest(width = 1024, height = 600) {
        Localization.initialize("zh", null)
        val old = ArmServiceFixture(); val fresh = ArmServiceFixture()
        val model = testConsoleModel(robotRuntime = AddressingRuntime(listOf(armSession(old), armSession(fresh))))
        try {
            setContent { TestWorkbenchTheme { RobotArmPage(model, onBack = {}, onAddressing = {}, onCalibration = {}) } }
            model.connect("127.0.0.1", "12345678"); waitUntil(timeoutMillis = 5000) { model.state.value.connected && armIdle(model) }
            model.robotArm.setActive(true); waitUntil(timeoutMillis = 3000) { old.state.value.observation == ArmObservation.ACTIVE && armIdle(model) }
            onNodeWithTag("arm-load-on").performScrollTo().performClick()
            runOnIdle { old.state.value = old.state.value.copy(loadModeRaw = 1) }
            waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("arm-confirm").fetchSemanticsNodes().isEmpty() }
            assertFalse(old.calls.any { it.startsWith("mode:") })
            onNodeWithTag("arm-uninstall").performScrollTo().performClick()
            runOnIdle { old.state.value = old.state.value.copy(servoIds = setOf(1, 2)) }
            waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("arm-confirm").fetchSemanticsNodes().isEmpty() }
            onNodeWithTag("arm-uninstall").performClick()
            model.pauseConnection(); model.connect("127.0.0.2", "12345678")
            waitUntil(timeoutMillis = 5000) { model.state.value.connectedAddress == "127.0.0.2" && armIdle(model) }
            waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("arm-confirm").fetchSemanticsNodes().isEmpty() }
            assertFalse(fresh.calls.any { it.startsWith("bind:") || it.startsWith("mode:") })
        } finally { model.close() }
    }

    @Test fun actualMaintenanceRouteOwnsObservationAndLeavingAWriteRequiresAnExplicitDecision() = runDesktopComposeUiTest(width = 1024, height = 600) {
        Localization.initialize("zh", null)
        val service = ArmServiceFixture().apply { onWrite = { awaitCancellation() } }
        val model = testConsoleModel(robotRuntime = AddressingRuntime(listOf(armSession(service))))
        try {
            setContent { TestWorkbenchTheme { Console(model, remember { mutableStateOf(EditorDocument()) }) } }
            model.connect("127.0.0.1", "12345678"); waitUntil(timeoutMillis = 5000) { model.state.value.connected && armIdle(model) }
            assertTrue(service.calls.none { it == "observe:true" })
            onNodeWithTag("navigate-4").performClick(); onNodeWithTag("settings-category-robot").performClick()
            onNodeWithTag("settings-arm-open").performScrollTo().performClick()
            waitUntil(timeoutMillis = 3000) { service.state.value.observation == ArmObservation.ACTIVE && armIdle(model) }
            onNodeWithTag("arm-page").assertIsDisplayed(); onNodeWithTag("navigate-0").assertDoesNotExist()
            onNodeWithTag("arm-guide-toggle").performScrollTo().performClick()
            onNodeWithTag("arm-addressing").performScrollTo().performClick()
            onNodeWithTag("robot-addressing-page").assertIsDisplayed()
            waitUntil(timeoutMillis = 3000) { service.state.value.observation == ArmObservation.OFF }
            onNodeWithTag("addressing-back").performClick()
            waitUntil(timeoutMillis = 3000) { service.state.value.observation == ArmObservation.ACTIVE && armIdle(model) }
            onNodeWithTag("arm-calibration").performScrollTo().performClick()
            onNodeWithTag("robot-calibration-page").assertIsDisplayed()
            onNodeWithTag("calibration-back").performClick()
            waitUntil(timeoutMillis = 3000) { service.state.value.observation == ArmObservation.ACTIVE && armIdle(model) }
            onNodeWithTag("arm-main-3").performScrollTo().performClick(); onNodeWithTag("arm-bind").performScrollTo().performClick()
            onNodeWithTag("arm-confirm").performClick(); waitUntil(timeoutMillis = 3000) { model.robotArm.state.value.changing }
            onNodeWithTag("arm-back").performClick(); onNodeWithTag("arm-confirm-cancel").performClick()
            waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("arm-confirm").fetchSemanticsNodes().isEmpty() }
            assertTrue(model.robotArm.state.value.changing)
            onNodeWithTag("arm-back").performClick(); onNodeWithTag("arm-confirm").performClick()
            waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("arm-page").fetchSemanticsNodes().isEmpty() && armIdle(model) }
            assertNotNull(model.robotArm.state.value.interruptedAddress)
            assertEquals(1, service.calls.count { it.startsWith("bind:") })
            assertFalse(model.remoteEnabled.value)
            waitUntil(timeoutMillis = 3000) { service.state.value.observation == ArmObservation.OFF }
        } finally { model.close() }
    }

    @Test fun aGripperAloneCanBeControlledWithoutInventingAnArmInstallation() = runDesktopComposeUiTest(width = 844, height = 390) {
        Localization.initialize("en", null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val service = ArmServiceFixture().apply { state.value = state.value.copy(armOnline = false, binding = null, servoIds = emptySet(), clawStatusRaw = 243) }
        val controller = RobotArmController(scope, { _, action -> action() }, { true }, { true })
        try {
            controller.attach(service, cn.elonzh.hanppie.robot.session.RobotTarget("127.0.0.1", "12345678")); controller.setActive(true)
            setContent { TestWorkbenchTheme { RobotArmControls(controller, true, true, Modifier.padding(20.dp)) } }
            onNodeWithTag("arm-hold-forward").assertIsNotEnabled()
            onNodeWithTag("arm-hold-open").assertIsEnabled().performClick()
            assertEquals(1, service.calls.count { it == "begin" }); assertEquals(1.0, service.inputs.last().third)
            waitUntil(timeoutMillis = 3000) { !service.state.value.controlArmed }
            assertEquals(243, service.state.value.clawStatusRaw)
            assertTrue(service.calls.none { it == "read" || it.startsWith("bind:") || it.startsWith("mode:") })
        } finally { controller.clear(); scope.cancel() }
    }

    @Test fun holdsStopOnReleaseRevocationAndDisableAndAccessibilityActivationIsBounded() = runDesktopComposeUiTest(width = 844, height = 390) {
        Localization.initialize("en", null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val service = ArmServiceFixture()
        val controller = RobotArmController(scope, { _, action -> action() }, { true }, { true })
        var enabled by mutableStateOf(true)
        try {
            controller.attach(service, cn.elonzh.hanppie.robot.session.RobotTarget("127.0.0.1", "12345678")); controller.setActive(true)
            setContent { TestWorkbenchTheme { RobotArmControls(controller, enabled, true, Modifier.padding(20.dp)) } }
            val hold = onNodeWithTag("arm-hold-forward")
            hold.performTouchInput { down(center) }; waitUntil(timeoutMillis = 3000) { service.state.value.controlActive }
            hold.performTouchInput { up() }; waitUntil(timeoutMillis = 3000) { !service.state.value.controlArmed }
            assertEquals(1, service.calls.count { it == "begin" }, "Pointer release must not add a click pulse")
            hold.requestFocus(); hold.performKeyInput { keyDown(Key.Spacebar) }; waitUntil(timeoutMillis = 3000) { service.state.value.controlActive }
            runOnIdle { enabled = false }; waitUntil(timeoutMillis = 3000) { !service.state.value.controlArmed }
            runOnIdle { enabled = true }; hold.performKeyInput { advanceEventTime(800) }
            assertFalse(service.state.value.controlArmed, "A repeated key-down after disable must not resume a held key")
            hold.performKeyInput { keyUp(Key.Spacebar) }
            val previous = service.calls.count { it == "begin" }; hold.performClick()
            assertEquals(previous + 1, service.calls.count { it == "begin" })
            waitUntil(timeoutMillis = 3000) { !service.state.value.controlArmed }
            assertTrue(controller.state.value.held.isEmpty()); assertFalse(service.calls.any { it.startsWith("mode:") })
        } finally { controller.clear(); scope.cancel() }
    }

    @Test fun switchingRemoteInputSurfacesClearsOldKeysAndNeverReplaysThem() = runDesktopComposeUiTest(width = 1024, height = 600) {
        Localization.initialize("zh", null)
        val service = ArmServiceFixture(); val session = armSession(service)
        val model = testConsoleModel(robotRuntime = AddressingRuntime(listOf(session)))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            model.connect("127.0.0.1", "12345678"); waitUntil(timeoutMillis = 5000) { model.state.value.connected && armIdle(model) }
            model.robotArm.setActive(true); waitUntil(timeoutMillis = 3000) { service.state.value.observation == ArmObservation.ACTIVE && armIdle(model) }
            scope.launch { while (isActive) { model.videoFrames.publish(model.state.value.connectedAddress, ImageBitmap(2, 2)); delay(100) } }
            waitUntil(timeoutMillis = 3000) { model.videoFrames.isFresh(model.state.value.connectedAddress) }
            model.enableRemote(); waitUntil(timeoutMillis = 3000) { model.remoteEnabled.value && !model.state.value.busy }
            setContent { TestWorkbenchTheme { RemotePage(model, videoContent = { Box(Modifier.fillMaxSize()) }, manageSession = false) } }
            val surface = onNodeWithTag("remote-surface"); surface.requestFocus()
            surface.performKeyInput { keyDown(Key.W) }; waitUntil(timeoutMillis = 3000) { model.remoteInput.value[0] != 0.0 }
            onNodeWithTag("remote-arm-toggle").performClick(); waitUntil(timeoutMillis = 3000) { model.remoteInput.value.all { it == 0.0 } }
            onNodeWithTag("remote-touch-left").assertDoesNotExist(); onNodeWithTag("remote-ammo").assertDoesNotExist()
            val hold = onNodeWithTag("arm-hold-open")
            hold.performTouchInput { down(center) }; waitUntil(timeoutMillis = 3000) { service.state.value.controlActive }
            hold.performTouchInput { up() }; waitUntil(timeoutMillis = 3000) { !service.state.value.controlArmed }
            assertTrue(model.remoteEnabled.value)
            onNodeWithTag("remote-arm-toggle").performClick()
            surface.requestFocus(); surface.performKeyInput { advanceEventTime(800) }
            assertTrue(model.remoteInput.value.all { it == 0.0 })
            surface.performKeyInput { keyUp(Key.W); keyDown(Key.W) }
            waitUntil(timeoutMillis = 3000) { model.remoteInput.value[0] != 0.0 }
            surface.performKeyInput { keyUp(Key.W) }
            assertEquals(0, session.gelCount.get()); assertEquals(0, session.infraredCount.get())
        } finally { scope.cancel(); model.close() }
    }
}
