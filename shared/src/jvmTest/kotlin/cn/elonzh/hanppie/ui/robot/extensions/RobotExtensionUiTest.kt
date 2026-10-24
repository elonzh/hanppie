package cn.elonzh.hanppie.ui.robot.extensions

import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import cn.elonzh.hanppie.robot.extensions.*
import cn.elonzh.hanppie.robot.product.*
import cn.elonzh.hanppie.ui.app.*
import cn.elonzh.hanppie.ui.design.TestWorkbenchTheme
import cn.elonzh.hanppie.ui.i18n.Localization
import cn.elonzh.hanppie.ui.i18n.AppLanguage
import cn.elonzh.hanppie.ui.i18n.tr
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.scripts.EditorDocument
import kotlinx.coroutines.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

internal fun extensionIdle(model: ConsoleController) = !model.state.value.busy && !model.robotExtensions.state.value.busy

@OptIn(ExperimentalTestApi::class)
class RobotExtensionUiTest {
    @Test fun dataUsesEachFamiliesFieldsValidityAndExistingVersionSnapshots(): Unit = runDesktopComposeUiTest(width = 1024, height = 600) {
        Localization.initialize("zh", null)
        val service = ExtensionServiceFixture(); val versions = CopyOnWriteArrayList<RobotVersionTarget>()
        val model = testConsoleModel(robotRuntime = AddressingRuntime(listOf(extensionSession(service, versions))))
        try {
            setContent { TestWorkbenchTheme { RobotExtensionPage(model, onBack = {}, onAddressing = {}) } }
            model.connect("127.0.0.1", "12345678"); waitUntil(timeoutMillis = 5000) { model.state.value.connected && extensionIdle(model) }
            model.robotExtensions.setActive(true)
            onNodeWithTag("extensions-module-SERVO_4").performScrollTo().performClick()
            waitUntil(timeoutMillis = 3000) { extensionIdle(model) && service.state.value.observation == ExtensionObservation.ACTIVE }
            service.sample(); waitUntil(timeoutMillis = 3000) { model.robotExtensions.state.value.task.fresh }
            onNodeWithTag("extensions-module-SERVO_4").assertIsSelected()
            onNodeWithTag("extensions-servo-angle").performScrollTo().assertTextEquals("角度：0.0°")
            onNodeWithTag("extensions-servo-speed").assertTextEquals("速度原值：-42")
            onNodeWithTag("extensions-raw-toggle").performScrollTo().performClick()
            onNodeWithTag("extensions-raw").performScrollTo().assertTextContains("有效标志 1", substring = true)
            waitUntil(timeoutMillis = 3000) { versions.contains(RobotVersionTarget.SERVO_4) }
            onNodeWithTag("robot-info-value-extension-SERVO_4").performScrollTo().assertTextEquals("01.02.03.04")
            val count = versions.size
            for (locale in AppLanguage.entries) {
                runOnIdle { Localization.select(locale.preference) }
                val action = onNodeWithTag("robot-info-query-extension-SERVO_4").performScrollTo().fetchSemanticsNode().config
                assertEquals(listOf(tr(Res.string.robot_info_query_target,
                    "${tr(Res.string.module_servo, 4)} · ${tr(Res.string.extensions_version)}")), action[SemanticsProperties.ContentDescription])
                assertEquals("01.02.03.04", action[SemanticsProperties.StateDescription])
            }
            assertEquals(count, versions.size)
            runOnIdle { Localization.select("zh") }
            onNodeWithTag("robot-info-query-extension-SERVO_4").performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
            waitUntil(timeoutMillis = 3000) { versions.size == count + 1 }
            assertEquals(RobotVersionTarget.SERVO_4, versions.last())
            runOnIdle { service.state.value = service.state.value.copy(readings = mapOf(RobotComponent.SERVO_4 to ServoReading(RobotComponent.SERVO_4, false, 32767, -999))) }
            waitUntil(timeoutMillis = 3000) { model.robotExtensions.state.value.task.readings[RobotComponent.SERVO_4] is ServoReading &&
                (model.robotExtensions.state.value.task.readings[RobotComponent.SERVO_4] as ServoReading).valid == false }
            onNodeWithTag("extensions-servo-invalid").performScrollTo().assertIsDisplayed()
            onNodeWithTag("extensions-servo-angle").assertDoesNotExist(); onNodeWithTag("extensions-servo-speed").assertDoesNotExist()
            onNodeWithTag("extensions-raw").performScrollTo().assertTextContains("角度 -999", substring = true)
            onNodeWithTag("extensions-family-TOF").performScrollTo().performClick()
            onNodeWithTag("extensions-module-TOF_2").performScrollTo().performClick()
            onNodeWithTag("robot-info-query-extension-TOF_2").performScrollTo().assert(SemanticsMatcher.expectValue(
                SemanticsProperties.ContentDescription, listOf(tr(Res.string.robot_info_query_target,
                    "${tr(Res.string.module_tof, 2)} · ${tr(Res.string.extensions_version)}"))))
            waitUntil(timeoutMillis = 3000) { extensionIdle(model) && service.state.value.kind == ExtensionKind.TOF }; service.sample()
            waitUntil(timeoutMillis = 3000) { model.robotExtensions.state.value.task.fresh &&
                model.robotExtensions.state.value.task.readings[RobotComponent.TOF_2] is TofReading }
            onNodeWithTag("extensions-tof-distance").performScrollTo().assertTextEquals("距离：1234 mm")
            onNodeWithTag("extensions-raw-toggle").performScrollTo().performClick()
            onNodeWithTag("extensions-raw").performScrollTo().assertTextEquals("命令 254 · 方向 171 · 标志 128")
            onNodeWithTag("extensions-family-ADAPTER").performScrollTo().performClick()
            onNodeWithTag("extensions-module-SENSOR_ADAPTER_6").performScrollTo().performClick()
            waitUntil(timeoutMillis = 3000) { extensionIdle(model) && service.state.value.kind == ExtensionKind.ADAPTER }; service.sample()
            waitUntil(timeoutMillis = 3000) { model.robotExtensions.state.value.task.fresh &&
                model.robotExtensions.state.value.task.readings[RobotComponent.SENSOR_ADAPTER_6] is AdapterReading }
            onNodeWithTag("extensions-adapter-port1").performScrollTo().assertTextEquals("端口 1：IO 1 · ADC 1023")
            onNodeWithTag("extensions-adapter-port2").assertTextEquals("端口 2：IO 255 · ADC 65535")
            waitUntil(timeoutMillis = 3000) { versions.contains(RobotVersionTarget.ADAPTER_6) }
            onNodeWithTag("robot-info-query-extension-ADAPTER_6").performScrollTo().assert(SemanticsMatcher.expectValue(
                SemanticsProperties.ContentDescription, listOf(tr(Res.string.robot_info_query_target,
                    "${tr(Res.string.module_adapter, 6)} · ${tr(Res.string.extensions_version)}"))))
            runOnIdle { service.state.value = service.state.value.copy(online = service.state.value.online!! - RobotComponent.SENSOR_ADAPTER_6) }
            waitUntil(timeoutMillis = 3000) { model.robotExtensions.state.value.selected == null && extensionIdle(model) }
            onNodeWithTag("extensions-adapter-port1").assertDoesNotExist(); onNodeWithTag("extensions-error").performScrollTo().assertIsDisplayed()
            assertTrue(service.calls.none { it.startsWith("identify:") })
        } finally { model.close() }
    }
    @Test fun failedReceptionHasAReadOnlyRetryAndStaleReportsRemainMarkedUntilNewDataArrives(): Unit = runDesktopComposeUiTest(width = 844, height = 390) {
        Localization.initialize("en", null)
        val service = ExtensionServiceFixture().apply { onObserve = { throw ExtensionOperationException(ExtensionProblem.REQUEST_REJECTED) } }
        val model = testConsoleModel(robotRuntime = AddressingRuntime(listOf(extensionSession(service))))
        try {
            setContent { TestWorkbenchTheme { RobotExtensionPage(model, onBack = {}, onAddressing = {}) } }
            model.connect("127.0.0.1", "12345678"); waitUntil(timeoutMillis = 5000) { model.state.value.connected && extensionIdle(model) }
            model.robotExtensions.setActive(true); onNodeWithTag("extensions-module-SERVO_1").performScrollTo().performClick()
            waitUntil(timeoutMillis = 3000) { extensionIdle(model) && model.robotExtensions.state.value.error != null }
            onNodeWithTag("extensions-retry").performScrollTo().assertIsEnabled()
            service.onObserve = {}; onNodeWithTag("extensions-retry").performClick()
            waitUntil(timeoutMillis = 3000) { extensionIdle(model) && service.state.value.observation == ExtensionObservation.ACTIVE }
            onNodeWithTag("extensions-data-status").performScrollTo().assertTextContains("Waiting for a new reading", substring = true)
            service.sample(); waitUntil(timeoutMillis = 3000) { model.robotExtensions.state.value.task.fresh }
            runOnIdle { service.state.value = service.state.value.copy(fresh = false, ageSeconds = 4) }
            waitUntil(timeoutMillis = 3000) { !model.robotExtensions.state.value.task.fresh }
            onNodeWithTag("extensions-data-status").assertTextContains("Reading is stale", substring = true)
            onNodeWithTag("extensions-retry").assertDoesNotExist()
            model.setForeground(false); waitUntil(timeoutMillis = 3000) { extensionIdle(model) && service.state.value.observation == ExtensionObservation.OFF }
            onNodeWithTag("extensions-retry").performScrollTo().assertIsNotEnabled()
            model.setForeground(true); waitUntil(timeoutMillis = 3000) { extensionIdle(model) && service.state.value.observation == ExtensionObservation.ACTIVE }
            assertTrue(service.calls.none { it.startsWith("identify:") })
        } finally { model.close() }
    }
    @Test fun actualSettingsAndAddressingNavigationOwnObservationAndLeavingIdentificationCleansUpQuietly(): Unit = runDesktopComposeUiTest(width = 1024, height = 600) {
        Localization.initialize("zh", null)
        val service = ExtensionServiceFixture(); val model = testConsoleModel(robotRuntime = AddressingRuntime(listOf(extensionSession(service))))
        try {
            setContent { TestWorkbenchTheme { Console(model, remember { mutableStateOf(EditorDocument()) }) } }
            model.connect("127.0.0.1", "12345678"); waitUntil(timeoutMillis = 5000) { model.state.value.connected && extensionIdle(model) }
            onNodeWithTag("navigate-4").performClick(); onNodeWithTag("settings-category-robot").performClick()
            onNodeWithTag("settings-extensions-open").performScrollTo()
            saveExtension("extensions-entry-settings", onNode(isRoot() and hasAnyDescendant(hasTestTag("settings-extensions-open"))))
            onNodeWithTag("settings-extensions-open").performClick()
            onNodeWithTag("extensions-page").assertIsDisplayed(); onNodeWithTag("navigate-0").assertDoesNotExist()
            mainClock.advanceTimeBy(500); waitForIdle()
            saveExtension("extensions-entry-console", onNodeWithTag("extensions-page"))
            onNodeWithTag("extensions-module-SERVO_1").performScrollTo().performClick()
            waitUntil(timeoutMillis = 3000) { extensionIdle(model) && service.state.value.observation == ExtensionObservation.ACTIVE }
            onNodeWithTag("extensions-identify").performScrollTo().performClick()
            waitUntil(timeoutMillis = 3000) { extensionIdle(model) && service.state.value.identifying != null }
            onNodeWithTag("extensions-identifying").performScrollTo().assertIsDisplayed()
            onNodeWithTag("extensions-stop-identify").performScrollTo().performClick()
            waitUntil(timeoutMillis = 3000) { service.state.value.identifying == null }
            onNodeWithTag("extensions-addressing").performScrollTo().performClick()
            onNodeWithTag("robot-addressing-page").assertIsDisplayed()
            waitUntil(timeoutMillis = 3000) { service.state.value.observation == ExtensionObservation.OFF }
            onNodeWithTag("addressing-back").performClick()
            waitUntil(timeoutMillis = 3000) { extensionIdle(model) && service.state.value.observation == ExtensionObservation.ACTIVE }
            assertEquals(1, service.calls.count { it.startsWith("identify:") })
            service.onIdentify = { awaitCancellation() }; onNodeWithTag("extensions-identify").performScrollTo().performClick()
            waitUntil(timeoutMillis = 3000) { model.state.value.busy && model.robotExtensions.state.value.changing }
            onNodeWithTag("extensions-back").performClick()
            waitUntil(timeoutMillis = 3000) { extensionIdle(model) && service.state.value.observation == ExtensionObservation.OFF }
            assertNull(service.state.value.identifying); onNodeWithTag("extensions-page").assertDoesNotExist()
        } finally { model.close() }
    }
}
