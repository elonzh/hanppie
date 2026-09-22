package cn.elonzh.hanppie.ui.robot.remote

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import cn.elonzh.hanppie.ui.app.ConsoleController
import cn.elonzh.hanppie.ui.app.testConsoleModel
import cn.elonzh.hanppie.ui.design.TestWorkbenchTheme
import kotlin.test.*
import org.junit.Rule
import org.junit.Test

class RemoteHudUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun telemetryWidthIsStableAcrossSignalChanges() {
        val model = testConsoleModel()
        try {
            rule.setContent { TestWorkbenchTheme {
                Box(Modifier.requiredSize(740.dp, 480.dp)) { RemotePage(model, videoContent = {}, manageSession = false) }
            } }
            val telemetry = rule.onNodeWithTag("remote-telemetry")
            val width = telemetry.fetchSemanticsNode().boundsInRoot.width
            for ((raw, label) in listOf(null to "未知", 9 to "弱", 29 to "中", 60 to "强", 100 to "强")) {
                rule.runOnIdle { model.state.value = model.state.value.copy(signalQuality = raw) }
                rule.onNodeWithTag("remote-signal").assertContentDescriptionEquals("信号强度 $label")
                rule.onNodeWithText(label).assertDoesNotExist()
                rule.onNodeWithTag("remote-signal").assertWidthIsEqualTo(20.dp)
                val signal = rule.onNodeWithTag("remote-signal").fetchSemanticsNode().boundsInRoot
                val heading = rule.onNodeWithTag("recenter-gimbal").fetchSemanticsNode().boundsInRoot
                assertEquals(16f, heading.left - signal.right)
                assertEquals(width, telemetry.fetchSemanticsNode().boundsInRoot.width)
            }
        } finally { model.close() }
    }

    @Test fun fireOnlyHighlightsWhilePressedAndInactiveButtonsKeepTheirColors() {
        val model = testConsoleModel()
        val controller = object : ConsoleController by model {
            override fun startFiring() = Unit
            override fun fireSelected() = Unit
        }
        try {
            rule.setContent { TestWorkbenchTheme {
                Box(Modifier.requiredSize(740.dp, 480.dp)) { RemotePage(controller, videoContent = {}, manageSession = false) }
            } }
            rule.runOnIdle { model.remoteEnabled.value = true }
            val fire = rule.onNodeWithContentDescription("红外开火")
            val ammo = rule.onNodeWithContentDescription("切换弹药")
            fun color(node: SemanticsNodeInteraction) = node.captureToImage().toPixelMap().let { it[it.width / 2 + 14, it.height / 2] }
            val idle = color(fire)
            val idleAmmo = color(ammo)
            val fireBounds = fire.fetchSemanticsNode().boundsInRoot
            val ammoBounds = ammo.fetchSemanticsNode().boundsInRoot
            assertEquals(ammoBounds.width, ammoBounds.height)
            assertTrue(ammoBounds.center.x > 740f * .6f, "Ammo should sit to the right of the muzzle")
            assertTrue(ammoBounds.bottom + 20f <= fireBounds.top || ammoBounds.right + 20f <= fireBounds.left, "Ammo should be separated from firing")
            fire.performTouchInput { down(center) }
            assertNotEquals(idle, color(fire))
            fire.performTouchInput { up() }
            assertEquals(idle, color(fire))
            rule.runOnIdle { model.setForeground(false) }
            fire.assertIsNotEnabled(); ammo.assertIsNotEnabled()
            assertEquals(idle, color(fire))
            assertEquals(idleAmmo, color(ammo))
            rule.runOnIdle { model.setForeground(true); model.remoteEnabled.value = true }
            val before = ammo.captureToImage().toPixelMap()
            ammo.performClick()
            assertTrue(model.gelSelected.value)
            val after = ammo.captureToImage().toPixelMap()
            assertTrue((0 until before.width).any { x -> (0 until before.height).any { y -> before[x,y] != after[x,y] } })
            rule.onNodeWithText("红外").assertDoesNotExist()
            rule.onNodeWithText("水弹").assertDoesNotExist()
        } finally { model.close() }
    }
}
