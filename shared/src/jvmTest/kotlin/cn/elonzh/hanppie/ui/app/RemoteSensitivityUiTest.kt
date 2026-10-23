package cn.elonzh.hanppie.ui.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import cn.elonzh.hanppie.ui.design.WorkbenchTheme
import cn.elonzh.hanppie.ui.i18n.Localization
import cn.elonzh.hanppie.ui.robot.remote.RemotePage
import cn.elonzh.hanppie.ui.settings.*
import kotlin.math.abs
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class RemoteSensitivityUiTest {
    @Test fun sourceGainsReachActualDriveAndProfileChangesRetireHeldKeysAndPointers() = runDesktopComposeUiTest(width = 1280, height = 720) {
        Localization.initialize("zh", "zh")
        val session = RobotSessionFixture(); val model = testConsoleModel(robotRuntime = powerRuntime(listOf(session)))
        try {
            model.connect("127.0.0.1", "12345678")
            waitUntil(timeoutMillis = 5_000) { model.state.value.connected && !model.state.value.busy && !model.settingsBusy.value }
            model.controlSettings.value = model.controlSettings.value.copy(inputSensitivities = InputSensitivities(
                keyboard = InputSensitivity(SensitivityPreset.CUSTOM, AxisSensitivity(25, 50, 25, 25))))
            setContent { WorkbenchTheme { Box(Modifier.fillMaxSize()) { RemotePage(model) } } }
            model.enableRemote()
            waitUntil(timeoutMillis = 5_000) { model.remoteEnabled.value }
            onNodeWithTag("remote-surface").requestFocus()
            onNodeWithTag("remote-surface").performKeyInput { keyDown(Key.W); keyDown(Key.DirectionUp) }
            waitUntil(timeoutMillis = 5_000) { abs(model.remoteInput.value[0] - .325) < 1e-6 && model.remoteInput.value[3] == 45.0 }
            runOnIdle { model.controlSettings.value = model.controlSettings.value.copy(inputSensitivities = InputSensitivities()) }
            waitUntil(timeoutMillis = 5_000) { model.remoteInput.value.all { it == 0.0 } }
            mainClock.advanceTimeBy(200); waitForIdle()
            assertTrue(model.remoteInput.value.all { it == 0.0 })
            onNodeWithTag("remote-surface").performKeyInput { keyUp(Key.W); keyUp(Key.DirectionUp); keyDown(Key.W) }
            waitUntil(timeoutMillis = 5_000) { abs(model.remoteInput.value[0] - .65) < 1e-6 }
            onNodeWithTag("remote-surface").performKeyInput { keyUp(Key.W) }
            waitUntil(timeoutMillis = 5_000) { model.remoteInput.value.all { it == 0.0 } }
            // The cockpit reveals screen sticks after pointer use, preserving its keyboard layout.
            onNodeWithTag("remote-surface").performTouchInput { click(center) }
            waitForIdle()
            onNodeWithContentDescription("底盘 摇杆").performTouchInput { down(center); moveTo(center + Offset(0f, -width * .18f)) }
            waitUntil(timeoutMillis = 5_000) { abs(model.remoteInput.value[0] - .325) < .01 }
            runOnIdle { model.controlSettings.value = model.controlSettings.value.copy(inputSensitivities = InputSensitivities(
                screen = InputSensitivity(SensitivityPreset.CUSTOM, AxisSensitivity(100, 50, 50, 50)))) }
            waitUntil(timeoutMillis = 5_000) { model.remoteInput.value.all { it == 0.0 } }
            onNodeWithContentDescription("底盘 摇杆").performTouchInput { moveTo(center + Offset(0f, -width * .2f)); up() }
            mainClock.advanceTimeBy(200); waitForIdle()
            assertTrue(model.remoteInput.value.all { it == 0.0 })
            onNodeWithContentDescription("底盘 摇杆").performTouchInput { down(center); moveTo(center + Offset(0f, -width * .18f)) }
            waitUntil(timeoutMillis = 5_000) { abs(model.remoteInput.value[0] - .65) < .01 }
            onNodeWithContentDescription("底盘 摇杆").performTouchInput { up() }
        } finally { model.close() }
    }
}
