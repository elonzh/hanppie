package cn.elonzh.hanppie.ui.robot.device

import cn.elonzh.hanppie.robot.telemetry.RobotSystemStatus
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cn.elonzh.hanppie.robot.product.*
import cn.elonzh.hanppie.robot.session.*
import cn.elonzh.hanppie.robot.telemetry.RobotHealth
import cn.elonzh.hanppie.ui.app.*
import cn.elonzh.hanppie.ui.design.TestWorkbenchTheme
import cn.elonzh.hanppie.ui.i18n.Localization
import cn.elonzh.hanppie.ui.i18n.AppLanguage
import cn.elonzh.hanppie.ui.i18n.tr
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.settings.SettingsPage
import cn.elonzh.hanppie.ui.settings.AppearanceController
import cn.elonzh.hanppie.ui.settings.AppearanceSettings
import cn.elonzh.hanppie.ui.settings.NightMode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

class RobotInformationUiTest {
    @get:Rule val rule = createComposeRule()
    @Test fun maintenanceQueriesRetryIndependentlyCancelWaitingAndClearOnDisconnect() {
        Localization.initialize("zh", null)
        val model = testConsoleModel(); val service = InformationFixture()
        val health = MutableStateFlow(RobotHealth(modules = listOf(WorkingRobotDevice(0x104, emptyList()))))
        service.version = { if (it == RobotVersionTarget.CAMERA) throw RobotRequestException(RequestProblem.TIMED_OUT) else "01.02.03.04" }
        model.state.value = model.state.value.copy(connected = true)
        model.robotInformation.attach(service, health)
        try {
            rule.setContent { TestWorkbenchTheme { SettingsPage(model, showRobotMaintenance = true) } }
            rule.onNodeWithTag("robot-info-toggle").performScrollTo().performClick()
            rule.onNodeWithTag("robot-info-value-activation").performScrollTo().assertTextEquals("尚未查询")
            rule.onNodeWithTag("robot-info-refresh").performScrollTo().performClick()
            rule.waitUntil(3000) { !model.robotInformation.state.value.busy }
            rule.onNodeWithTag("robot-info-value-SYSTEM").performScrollTo().assertTextEquals("01.02.03.04")
            rule.onNodeWithTag("robot-info-value-CAMERA").performScrollTo().assertTextContains("机器人未及时回复", substring = true)
            service.version = { "02.00.00.00" }
            rule.onNodeWithTag("robot-info-query-CAMERA").performScrollTo().performClick()
            rule.waitUntil(3000) { model.robotInformation.state.value.versions[RobotVersionTarget.CAMERA] is InformationValue.Value }
            rule.onNodeWithTag("robot-info-value-CAMERA").assertTextEquals("02.00.00.00")
            rule.onNodeWithTag("robot-info-value-SYSTEM").performScrollTo().assertTextEquals("01.02.03.04")
            service.version = { awaitCancellation() }
            rule.onNodeWithTag("robot-info-refresh").performScrollTo().performClick()
            rule.waitUntil(3000) { model.robotInformation.state.value.busy }
            rule.onNodeWithTag("robot-info-cancel").performScrollTo().performClick()
            rule.waitUntil(3000) { !model.robotInformation.state.value.busy }
            assertTrue(model.robotInformation.state.value.versions.values.all { it == InformationValue.Unread })
            rule.runOnIdle { service.systemStatus.value = RobotSystemStatus(null, 0, false, 123) }
            rule.waitUntil(3000) { model.robotInformation.state.value.usage == RobotSystemStatus(null, 0, false, 123) }
            rule.waitForIdle()
            rule.onNodeWithTag("robot-info-mileage").performScrollTo().assertTextEquals("累计里程：— 米")
            rule.onNodeWithTag("robot-info-driving").performScrollTo().assertTextEquals("累计驾驶时长：0 秒")
            rule.runOnIdle { model.robotInformation.clear(); model.state.value = model.state.value.copy(connected = false) }
            rule.onNodeWithText("连接机器人后可查看设备信息").performScrollTo().assertIsDisplayed()
            rule.onNodeWithTag("robot-info-toggle").assertDoesNotExist()
            rule.onNodeWithTag("robot-info-mileage").assertDoesNotExist()
        } finally { model.close() }
    }
}

@OptIn(ExperimentalTestApi::class)
class RobotInformationLayoutTest {
    @Test fun compactLandscape() = layout(844, 390)
    @Test fun tabletLandscape() = layout(1024, 600, dark = true)
    @Test fun desktopLandscape() = layout(1280, 720)
    @Test fun nineLanguagesLargeText() = layout(844, 390, "en", 1.4f, dark = true)
    private fun layout(width: Int, height: Int, language: String = "zh", scale: Float = 1f, dark: Boolean = false) =
        runDesktopComposeUiTest(width = width, height = height) {
            Localization.initialize(language, null)
            val model = testConsoleModel(); val service = InformationFixture()
            var activationReads = 0
            service.systemStatus.value = RobotSystemStatus(1234567, 987654, false, 123)
            service.version = { if (it == RobotVersionTarget.SYSTEM_CAN) throw RobotRequestException(RequestProblem.TIMED_OUT) else "01.02.03.04" }
            service.activation = { activationReads++; false }
            model.state.value = model.state.value.copy(connected = true, robotProduct = RobotProduct(model = RobotModel.ROBOMASTER_S1))
            val moduleIds = RobotVersionTarget.entries.mapNotNull { it.componentId }.distinct()
            model.robotInformation.attach(service, MutableStateFlow(RobotHealth(modules = moduleIds.map { WorkingRobotDevice(it, emptyList()) })))
            val appearance = AppearanceController(AppearanceSettings(if (dark) NightMode.DARK else NightMode.LIGHT))
            val languages = if (scale > 1f) AppLanguage.entries else listOf(AppLanguage.SIMPLIFIED_CHINESE)
            fun picture(state: String) = save("info-actions-$width-$height-$scale-${Localization.languageTag}-$state", onNodeWithTag("info-layout"))
            fun inspect(tag: String, subject: String? = null) {
                val config = onNodeWithTag("robot-info-query-$tag").fetchSemanticsNode().config
                assertEquals(Role.Button, config[SemanticsProperties.Role])
                assertNotNull(config.getOrNull(SemanticsActions.OnClick))
                val state = onNodeWithTag("robot-info-value-$tag").fetchSemanticsNode().config[SemanticsProperties.Text].single().text
                assertEquals(state, config[SemanticsProperties.StateDescription])
                val description = config[SemanticsProperties.ContentDescription].single()
                if (subject != null) {
                    val failed = tag == "SYSTEM_CAN"
                    assertEquals(tr(if (failed) Res.string.robot_info_retry_target else Res.string.robot_info_query_target, subject), description)
                }
                assertFalse(description.contains("robot-info-"))
            }
            fun click(tag: String) = onNodeWithTag(tag).performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
            try {
                waitUntil(timeoutMillis = 3000) { !model.settingsBusy.value }
                setContent { TestWorkbenchTheme(appearance) { CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                    Box(Modifier.fillMaxSize().padding(16.dp).testTag("info-layout")) { SettingsPage(model, showRobotMaintenance = true) }
                } } }
                onNodeWithTag("robot-info-toggle").performScrollTo().assertIsDisplayed()
                picture("overview")
                click("robot-info-toggle")
                inspect("activation", tr(Res.string.robot_info_activation))
                inspect("SYSTEM", tr(Res.string.robot_info_system))
                assertTrue(service.reads.isEmpty()); assertEquals(0, activationReads)
                click("robot-info-refresh")
                waitUntil(timeoutMillis = 3000) { !model.robotInformation.state.value.busy }
                val reads = service.reads.size
                assertEquals(RobotVersionTarget.entries.size, reads); assertEquals(1, activationReads)
                for (locale in languages) {
                    runOnIdle { Localization.select(locale.preference) }
                    val tags = listOf("activation") + RobotVersionTarget.entries.map { it.name }
                    tags.forEach { inspect(it) }
                    val names = tags.map { onNodeWithTag("robot-info-query-$it").fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single() }
                    assertEquals(names.size, names.distinct().size)
                    inspect("activation", tr(Res.string.robot_info_activation))
                    inspect("SYSTEM", tr(Res.string.robot_info_system))
                    inspect("SYSTEM_CAN", tr(Res.string.robot_info_can))
                    onNodeWithTag("robot-info-activation").performScrollTo().assertIsDisplayed()
                    onNodeWithTag("robot-info-value-activation").assertTextEquals(tr(Res.string.robot_info_not_activated))
                    picture("activation")
                    onNodeWithTag("robot-info-SYSTEM_CAN").performScrollTo().assertIsDisplayed()
                    onNodeWithTag("robot-info-query-SYSTEM_CAN").assertIsEnabled()
                    picture("failure")
                    onNodeWithTag("robot-info-query-GIMBAL_MOTOR").performScrollTo().assertIsDisplayed()
                    assertTrue(onNodeWithTag("robot-info-query-GIMBAL_MOTOR").fetchSemanticsNode().boundsInRoot.height >= 48)
                    picture("module")
                }
                assertEquals(reads, service.reads.size); assertEquals(1, activationReads)
                val pending = CompletableDeferred<String>()
                service.version = { if (it == RobotVersionTarget.SYSTEM) pending.await() else "02.00.00.00" }
                click("robot-info-query-SYSTEM")
                waitUntil(timeoutMillis = 3000) { model.robotInformation.state.value.versions[RobotVersionTarget.SYSTEM] == InformationValue.Loading }
                for (locale in languages) {
                    runOnIdle { Localization.select(locale.preference) }
                    inspect("SYSTEM", tr(Res.string.robot_info_system))
                    onNodeWithTag("robot-info-query-SYSTEM").assertIsNotEnabled()
                        .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, tr(Res.string.robot_info_reading)))
                }
                onNodeWithTag("robot-info-query-SYSTEM").performScrollTo(); picture("loading")
                click("robot-info-cancel")
                waitUntil(timeoutMillis = 3000) { !model.robotInformation.state.value.busy }
                pending.complete("obsolete")
                onNodeWithTag("robot-info-query-SYSTEM").performScrollTo().assertIsEnabled()
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, tr(Res.string.robot_info_unread)))
                service.version = { "02.00.00.00" }
                click("robot-info-query-SYSTEM")
                waitUntil(timeoutMillis = 3000) { model.robotInformation.state.value.versions[RobotVersionTarget.SYSTEM] == InformationValue.Value("02.00.00.00") }
                inspect("SYSTEM", tr(Res.string.robot_info_system))
                assertEquals(reads + 2, service.reads.size); assertEquals(1, activationReads)
                runOnIdle { model.robotInformation.clear(); model.state.value = model.state.value.copy(connected = false) }
                onNodeWithTag("robot-info-query-SYSTEM").assertDoesNotExist()
            } finally { model.close(); Localization.initialize("zh", null) }
        }
    private fun save(name: String, node: SemanticsNodeInteraction) {
        val image = node.captureToImage(); val pixels = IntArray(image.width * image.height); image.readPixels(pixels)
        val out = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        out.setRGB(0, 0, image.width, image.height, pixels, 0, image.width)
        ImageIO.write(out, "png", File("build/reports/ui/$name.png").apply { parentFile.mkdirs() })
    }
}
