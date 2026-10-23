package cn.elonzh.hanppie.ui.robot.maintenance

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cn.elonzh.hanppie.robot.maintenance.firmware.*
import cn.elonzh.hanppie.robot.product.WorkingRobotDevice
import cn.elonzh.hanppie.robot.telemetry.*
import cn.elonzh.hanppie.ui.app.*
import cn.elonzh.hanppie.ui.design.TestWorkbenchTheme
import cn.elonzh.hanppie.ui.i18n.Localization
import cn.elonzh.hanppie.ui.scripts.EditorDocument
import kotlinx.coroutines.awaitCancellation
import kotlinx.io.Buffer
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

private fun firmwareSession(service: FirmwareServiceFixture, externalPower: Boolean = false) = RobotSessionFixture().apply {
    firmware = service
    health.value = if (externalPower) RobotHealth(modules = emptyList()) else RobotHealth(
        modules = (listOf(0x030a) + (0x0314..0x0317)).map { WorkingRobotDevice(it, emptyList()) },
        battery = BatteryTelemetry(0, 0, 0, 75))
}
private fun chooseFirmware(model: ConsoleModel, name: String = "local.tar") {
    val bytes = firmwareUiArchive()
    model.robotFirmware.selectPackage(name, { bytes.size.toLong() }) { Buffer().apply { write(bytes) } }
}
private fun firmwareIdle(model: ConsoleModel) = !model.state.value.busy && !model.robotFirmware.state.value.busy

@OptIn(ExperimentalTestApi::class)
class RobotFirmwareUiTest {
    @Test fun fileAndDeviceMetadataNeedPowerAndMissingMotorConsentBeforeInstallation() = runDesktopComposeUiTest(width = 1024, height = 600) {
        Localization.initialize("zh", null)
        val service = FirmwareServiceFixture(); val model = testConsoleModel(robotRuntime = AddressingRuntime(listOf(firmwareSession(service, true))))
        try {
            setContent { TestWorkbenchTheme { Box(Modifier.fillMaxSize().padding(16.dp)) {
                RobotFirmwarePage(model, onBack = {}, onSelectFile = { chooseFirmware(model) })
            } } }
            onNodeWithTag("firmware-select").performScrollTo().performClick()
            waitUntil(timeoutMillis = 5000) { model.robotFirmware.state.value.file != null }
            onNodeWithTag("firmware-install").assertIsNotEnabled()
            model.connect("127.0.0.1", "12345678"); waitUntil(timeoutMillis = 5000) { model.state.value.connected && firmwareIdle(model) }
            onNodeWithTag("firmware-refresh").performScrollTo().performClick()
            waitUntil(timeoutMillis = 3000) { model.robotFirmware.state.value.task.configuration != null && firmwareIdle(model) }
            onNodeWithTag("firmware-motors-consent").performScrollTo().performClick()
            onNodeWithTag("firmware-install").assertIsNotEnabled()
            onNodeWithTag("firmware-power-consent").performScrollTo().performClick()
            onNodeWithTag("firmware-install").assertIsEnabled().performClick()
            onNodeWithTag("firmware-confirm-cancel").performClick()
            waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("firmware-confirm").fetchSemanticsNodes().isEmpty() }
            assertFalse("install" in service.calls)
            onNodeWithTag("firmware-install").performClick(); onNodeWithTag("firmware-confirm").performClick()
            waitUntil(timeoutMillis = 3000) { service.state.value.phase == FirmwarePhase.INSTALLING && firmwareIdle(model) }
            assertTrue(model.robotFirmware.state.value.blocksControl); assertEquals(1, service.calls.count { it == "install" })
            onNodeWithTag("firmware-install").assertDoesNotExist(); onNodeWithTag("firmware-interrupt").assertDoesNotExist()
        } finally { model.close() }
    }

    @Test fun fileConfigurationAndPowerChangesInvalidateAnOpenInstallConfirmation() = runDesktopComposeUiTest(width = 1024, height = 600) {
        Localization.initialize("zh", null)
        val service = FirmwareServiceFixture(); val session = firmwareSession(service)
        val model = testConsoleModel(robotRuntime = AddressingRuntime(listOf(session)))
        try {
            setContent { TestWorkbenchTheme { Box(Modifier.fillMaxSize().padding(16.dp)) { RobotFirmwarePage(model, onBack = {}) } } }
            model.connect("127.0.0.1", "12345678"); waitUntil(timeoutMillis = 5000) { model.state.value.connected && firmwareIdle(model) }
            model.robotFirmware.refresh(); chooseFirmware(model)
            waitUntil(timeoutMillis = 3000) { model.robotFirmware.state.value.file != null && model.robotFirmware.state.value.task.configuration != null && firmwareIdle(model) }
            onNodeWithTag("firmware-install").performClick()
            runOnIdle { chooseFirmware(model, "replacement.tar") }
            waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("firmware-confirm").fetchSemanticsNodes().isEmpty() && firmwareIdle(model) }
            onNodeWithTag("firmware-install").performClick()
            runOnIdle { service.state.value = service.state.value.copy(configuration = service.configuration.copy(deviceId = "other-product")) }
            waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("firmware-confirm").fetchSemanticsNodes().isEmpty() }
            onNodeWithTag("firmware-install").assertIsNotEnabled()
            onNodeWithTag("firmware-compatibility").performScrollTo().assertTextContains("产品标识", substring = true)
            runOnIdle { service.state.value = service.state.value.copy(configuration = service.configuration) }
            onNodeWithTag("firmware-install").performClick()
            runOnIdle { session.health.value = session.health.value.copy(battery = BatteryTelemetry(0, 0, 0, 30)) }
            waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("firmware-confirm").fetchSemanticsNodes().isEmpty() }
            onNodeWithTag("firmware-install").assertIsNotEnabled(); assertFalse("install" in service.calls)
        } finally { model.close() }
    }

    @Test fun progressAndTransferAcceptanceNeverReplaceDeviceSuccessAndVersionReadback() = runDesktopComposeUiTest(width = 1024, height = 600) {
        Localization.initialize("zh", null)
        val service = FirmwareServiceFixture(); val model = testConsoleModel(robotRuntime = AddressingRuntime(listOf(firmwareSession(service))))
        try {
            setContent { TestWorkbenchTheme { Box(Modifier.fillMaxSize().padding(16.dp)) { RobotFirmwarePage(model, onBack = {}) } } }
            model.connect("127.0.0.1", "12345678"); waitUntil(timeoutMillis = 5000) { model.state.value.connected && firmwareIdle(model) }
            chooseFirmware(model); waitUntil(timeoutMillis = 3000) { model.robotFirmware.state.value.file != null }
            val expected = model.robotFirmware.state.value.file!!.inspection
            runOnIdle { service.state.value = RobotFirmwareState(FirmwarePhase.INSTALLING, expectedPackage = expected, loaderMayBeActive = true,
                transferAcceptedCode = 0xf7, report = FirmwareUpgradeReport(3, 100, 0), reportAgeSeconds = 0) }
            onNodeWithTag("firmware-native-progress").assertTextContains("100%", substring = true)
            onNodeWithTag("firmware-result").assertDoesNotExist(); onNodeWithTag("firmware-interrupt").assertDoesNotExist()
            runOnIdle { service.state.value = service.state.value.copy(phase = FirmwarePhase.REPORTED_SUCCESS, report = FirmwareUpgradeReport(4, 1, 0)) }
            onNodeWithTag("firmware-result").assertDoesNotExist()
            service.configuration = service.configuration.copy(version = "00.06.0520")
            service.onRefresh = { service.state.value = service.state.value.copy(problem = FirmwareProblem.READBACK_MISMATCH, installedMatchesTarget = false) }
            onNodeWithTag("firmware-refresh").assertIsDisplayed().performClick()
            waitUntil(timeoutMillis = 3000) { service.state.value.installedMatchesTarget == false && firmwareIdle(model) }
            onNodeWithTag("firmware-problem").performScrollTo().assertTextContains("回读版本", substring = true)
            onNodeWithTag("firmware-result").assertDoesNotExist(); assertTrue(model.robotFirmware.state.value.blocksControl)
            service.configuration = service.configuration.copy(version = "00.06.0521")
            service.onRefresh = { service.state.value = service.state.value.copy(phase = FirmwarePhase.VERIFIED, loaderMayBeActive = false, problem = null, installedMatchesTarget = true) }
            onNodeWithTag("firmware-refresh").performClick()
            waitUntil(timeoutMillis = 3000) { !model.robotFirmware.state.value.blocksControl && firmwareIdle(model) }
            onNodeWithTag("firmware-result").performScrollTo().assertTextContains("设备成功报告", substring = true)
            assertFalse(model.remoteEnabled.value)
        } finally { model.close() }
    }

    @Test fun recoveryNeedsOriginalRobotObservationFreshReadAndSeparateNormalOperationConfirmation() = runDesktopComposeUiTest(width = 1024, height = 600) {
        Localization.initialize("zh", null)
        val old = FirmwareServiceFixture(); val wrong = FirmwareServiceFixture(); val fresh = FirmwareServiceFixture()
        val model = testConsoleModel(robotRuntime = AddressingRuntime(listOf(old, wrong, fresh).map { firmwareSession(it) }))
        try {
            setContent { TestWorkbenchTheme { Box(Modifier.fillMaxSize().padding(16.dp)) { RobotFirmwarePage(model, onBack = {}) } } }
            model.connect("127.0.0.1", "12345678"); waitUntil(timeoutMillis = 5000) { model.state.value.connected && firmwareIdle(model) }
            runOnIdle { old.state.value = RobotFirmwareState(FirmwarePhase.INSTALLING, loaderMayBeActive = true) }
            waitUntil(timeoutMillis = 3000) { model.robotFirmware.state.value.blocksControl }
            model.disconnect(); waitUntil(timeoutMillis = 3000) { !model.state.value.connected && !model.state.value.busy }
            model.connect("127.0.0.2", "12345678"); waitUntil(timeoutMillis = 5000) { model.state.value.connected && firmwareIdle(model) }
            onNodeWithTag("firmware-disconnect").performScrollTo().performClick()
            waitUntil(timeoutMillis = 3000) { !model.state.value.connected && !model.state.value.busy }
            onNodeWithTag("firmware-reconnect").performScrollTo().performClick()
            waitUntil(timeoutMillis = 5000) { model.state.value.connected && firmwareIdle(model) }
            assertTrue(wrong.calls.isEmpty()); assertTrue(fresh.calls.isEmpty())
            onNodeWithTag("firmware-observe").performScrollTo().performClick(); onNodeWithTag("firmware-confirm").performClick()
            waitUntil(timeoutMillis = 3000) { fresh.calls == listOf("observe") && firmwareIdle(model) && onAllNodesWithTag("firmware-confirm").fetchSemanticsNodes().isEmpty() }
            onNodeWithTag("firmware-restarted").performScrollTo().assertIsNotEnabled()
            onNodeWithTag("firmware-refresh").performScrollTo().performClick()
            waitUntil(timeoutMillis = 3000) { serviceConfigurationReady(model) }
            onNodeWithTag("firmware-restarted").performScrollTo().performClick()
            runOnIdle { fresh.state.value = fresh.state.value.copy(report = FirmwareUpgradeReport(3, 50, 0)) }
            waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("firmware-confirm").fetchSemanticsNodes().isEmpty() }
            onNodeWithTag("firmware-restarted").assertIsNotEnabled()
            runOnIdle { fresh.state.value = fresh.state.value.copy(report = null) }
            waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("firmware-restarted").fetchSemanticsNodes().any { SemanticsProperties.Disabled !in it.config } }
            onNodeWithTag("firmware-restarted").performClick(); onNodeWithTag("firmware-confirm").performClick()
            waitUntil(timeoutMillis = 3000) { model.robotFirmware.state.value.recovery == null && firmwareIdle(model) }
            assertEquals(listOf("observe", "refresh", "refresh", "confirm-restart"), fresh.calls)
            onNodeWithTag("firmware-result").performScrollTo().assertTextContains("不代表固件安装成功", substring = true)
        } finally { model.close() }
    }

    @Test fun realMaintenanceEntryUsesFileCallbackAndBackStopsOnlyLocalTransferBeforeLeaving() = runDesktopComposeUiTest(width = 1024, height = 600) {
        Localization.initialize("zh", null)
        val service = FirmwareServiceFixture().apply { onInstall = { awaitCancellation() } }
        val model = testConsoleModel(robotRuntime = AddressingRuntime(listOf(firmwareSession(service))))
        var selected = 0
        try {
            setContent { TestWorkbenchTheme { Console(model, remember { mutableStateOf(EditorDocument()) },
                onImportRobotFirmware = { selected++; chooseFirmware(model) }) } }
            model.connect("127.0.0.1", "12345678"); waitUntil(timeoutMillis = 5000) { model.state.value.connected && firmwareIdle(model) }
            onNodeWithTag("navigate-4").performClick(); onNodeWithTag("settings-category-robot").performClick()
            onNodeWithTag("settings-firmware-open").performScrollTo()
            saveFirmware("firmware-entry-1024-600-zh", onRoot())
            onNodeWithTag("settings-firmware-open").performClick()
            onNodeWithTag("robot-firmware-page").assertIsDisplayed(); onNodeWithTag("navigate-0").assertDoesNotExist()
            onNodeWithTag("firmware-refresh").performScrollTo().performClick()
            waitUntil(timeoutMillis = 3000) { serviceConfigurationReady(model) }
            onNodeWithTag("firmware-select").performScrollTo().performClick()
            waitUntil(timeoutMillis = 3000) { model.robotFirmware.state.value.file != null && firmwareIdle(model) }
            assertEquals(1, selected)
            saveFirmware("firmware-console-1024-600-zh", onRoot())
            onNodeWithTag("firmware-install").performClick(); onNodeWithTag("firmware-confirm").performClick()
            waitUntil(timeoutMillis = 3000) { service.state.value.canInterruptTransfer && onAllNodesWithTag("firmware-confirm").fetchSemanticsNodes().isEmpty() }
            onNodeWithTag("firmware-back").performClick(); onNodeWithTag("firmware-confirm-cancel").performClick()
            waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("firmware-confirm").fetchSemanticsNodes().isEmpty() }
            assertFalse("interrupt" in service.calls)
            onNodeWithTag("firmware-back").performClick(); onNodeWithTag("firmware-confirm").performClick()
            waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("robot-firmware-page").fetchSemanticsNodes().isEmpty() && firmwareIdle(model) }
            assertEquals(1, service.calls.count { it == "interrupt" }); assertTrue(model.robotFirmware.state.value.blocksControl)
            onNodeWithTag("settings-firmware-open").performScrollTo().performClick()
            onNodeWithTag("firmware-interrupt").assertDoesNotExist()
            onNodeWithTag("firmware-back").performClick(); onNodeWithTag("firmware-confirm").performClick()
            waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("robot-firmware-page").fetchSemanticsNodes().isEmpty() }
            assertEquals(1, service.calls.count { it == "interrupt" }); assertFalse(model.remoteEnabled.value)
        } finally { model.close() }
    }
}

private fun serviceConfigurationReady(model: ConsoleModel) = model.robotFirmware.state.value.task.configuration != null && firmwareIdle(model)

@OptIn(ExperimentalTestApi::class)
class RobotFirmwareLayoutTest {
    @Test fun compactLandscape() = layout(844, 390)
    @Test fun tabletLandscape() = layout(1024, 600)
    @Test fun desktopLandscape() = layout(1280, 720)
    @Test fun englishLargeText() = layout(844, 390, "en", 1.4f)
    private fun layout(width: Int, height: Int, language: String = "zh", scale: Float = 1f) = runDesktopComposeUiTest(width = width, height = height) {
        Localization.initialize(language, null)
        val service = FirmwareServiceFixture(); val fresh = FirmwareServiceFixture()
        val model = testConsoleModel(robotRuntime = AddressingRuntime(listOf(firmwareSession(service), firmwareSession(fresh))))
        val prefix = "firmware-$width-$height-$language"
        File("build/reports/ui").listFiles()?.filter { it.name.startsWith(prefix) }?.forEach { it.delete() }
        try {
            setContent { TestWorkbenchTheme { CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                Box(Modifier.fillMaxSize().padding(16.dp).testTag("firmware-layout")) {
                    RobotFirmwarePage(model, onBack = {}, onSelectFile = { chooseFirmware(model) })
                }
            } } }
            saveFirmware("$prefix-offline", onNodeWithTag("firmware-layout"))
            model.connect("127.0.0.1", "12345678"); waitUntil(timeoutMillis = 5000) { model.state.value.connected && firmwareIdle(model) }
            model.robotFirmware.refresh(); chooseFirmware(model)
            waitUntil(timeoutMillis = 3000) { serviceConfigurationReady(model) && model.robotFirmware.state.value.file != null }
            runOnIdle {
                val selected = model.robotFirmware.state.value.file!!
                val manifest = selected.inspection.manifest.let { original -> original.copy(modules = List(27) { index ->
                    original.modules.single().copy(id = (0x2401 + index).toString(16), fileName = "module-$index.sig")
                }) }
                service.configuration = manifest; service.state.value = service.state.value.copy(configuration = manifest)
                model.robotFirmware.state.value = model.robotFirmware.state.value.copy(file = selected.copy(inspection = selected.inspection.copy(manifest = manifest)))
            }
            onNodeWithTag("firmware-install").assertIsDisplayed().assertIsEnabled()
            val header = onNodeWithTag("firmware-header").fetchSemanticsNode().boundsInRoot
            saveFirmware("$prefix-ready", onNodeWithTag("firmware-layout"))
            onNodeWithTag("firmware-file-version").performScrollTo().assertIsDisplayed()
            onNodeWithTag("firmware-modules").performScrollTo().performClick()
            onNodeWithTag("firmware-module-241b-hw02").performScrollTo().assertIsDisplayed()
            saveFirmware("$prefix-package", onNodeWithTag("firmware-layout"))
            assertEquals(header, onNodeWithTag("firmware-header").fetchSemanticsNode().boundsInRoot)
            onNodeWithTag("firmware-install").performClick(); onNodeWithTag("firmware-confirm").assertIsDisplayed()
            saveFirmware("$prefix-install", onNode(isRoot() and hasAnyDescendant(hasTestTag("firmware-confirm"))))
            onNodeWithTag("firmware-confirm-cancel").performClick()
            waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("firmware-confirm").fetchSemanticsNodes().isEmpty() }
            val expected = model.robotFirmware.state.value.file!!.inspection.copy(archiveSize = 146831360)
            runOnIdle { service.state.value = RobotFirmwareState(FirmwarePhase.TRANSFERRING, busy = true, expectedPackage = expected,
                uploadedBytes = 73415680, loaderMayBeActive = true) }
            waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("firmware-upload-progress").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("firmware-upload-progress").performScrollTo().assertIsDisplayed()
            saveFirmware("$prefix-transfer", onNodeWithTag("firmware-layout"))
            onNodeWithTag("firmware-interrupt").performClick(); onNodeWithTag("firmware-confirm").assertIsDisplayed()
            saveFirmware("$prefix-interrupt", onNode(isRoot() and hasAnyDescendant(hasTestTag("firmware-confirm"))))
            onNodeWithTag("firmware-confirm-cancel").performClick()
            waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("firmware-confirm").fetchSemanticsNodes().isEmpty() }
            runOnIdle { service.state.value = service.state.value.copy(phase = FirmwarePhase.INSTALLING, busy = false,
                report = FirmwareUpgradeReport(3, 100, 0), reportAgeSeconds = 0) }
            waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("firmware-native-progress").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("firmware-native-progress").performScrollTo().assertIsDisplayed()
            onNodeWithTag("firmware-result").assertDoesNotExist(); onNodeWithTag("firmware-interrupt").assertDoesNotExist()
            saveFirmware("$prefix-installing", onNodeWithTag("firmware-layout"))
            runOnIdle { service.state.value = service.state.value.copy(phase = FirmwarePhase.REPORTED_SUCCESS, report = FirmwareUpgradeReport(4, 1, 0)) }
            onNodeWithTag("firmware-phase").performScrollTo().assertIsDisplayed()
            onNodeWithTag("firmware-refresh").assertIsDisplayed()
            saveFirmware("$prefix-reported", onNodeWithTag("firmware-layout"))
            model.disconnect(); waitUntil(timeoutMillis = 3000) { !model.state.value.connected && !model.state.value.busy }
            onNodeWithTag("firmware-reconnect").performScrollTo().assertIsDisplayed()
            saveFirmware("$prefix-disconnected", onNodeWithTag("firmware-layout"))
            onNodeWithTag("firmware-reconnect").performClick(); waitUntil(timeoutMillis = 5000) { model.state.value.connected && firmwareIdle(model) }
            onNodeWithTag("firmware-observe").performScrollTo().performClick(); onNodeWithTag("firmware-confirm").assertIsDisplayed()
            saveFirmware("$prefix-observe", onNode(isRoot() and hasAnyDescendant(hasTestTag("firmware-confirm"))))
            onNodeWithTag("firmware-confirm").performClick()
            waitUntil(timeoutMillis = 3000) { fresh.state.value.observingInterrupted && firmwareIdle(model) && onAllNodesWithTag("firmware-confirm").fetchSemanticsNodes().isEmpty() }
            model.robotFirmware.refresh(); waitUntil(timeoutMillis = 3000) { serviceConfigurationReady(model) }
            onNodeWithTag("firmware-restarted").performScrollTo().performClick(); onNodeWithTag("firmware-confirm").assertIsDisplayed()
            saveFirmware("$prefix-restarted", onNode(isRoot() and hasAnyDescendant(hasTestTag("firmware-confirm"))))
            onNodeWithTag("firmware-confirm-cancel").performClick()
            waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("firmware-confirm").fetchSemanticsNodes().isEmpty() }
            runOnIdle { fresh.state.value = fresh.state.value.copy(phase = FirmwarePhase.UNCERTAIN, problem = FirmwareProblem.REPORT_STALE) }
            waitUntil(timeoutMillis = 3000) { onAllNodesWithTag("firmware-problem").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("firmware-problem").performScrollTo().assertIsDisplayed()
            saveFirmware("$prefix-stale", onNodeWithTag("firmware-layout"))
            assertTrue(onNodeWithTag("firmware-back").fetchSemanticsNode().boundsInRoot.height >= 48)
        } finally { model.close() }
    }
}

private fun saveFirmware(name: String, node: SemanticsNodeInteraction) {
    val image = node.captureToImage(); val pixels = IntArray(image.width * image.height); image.readPixels(pixels)
    val output = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
    output.setRGB(0, 0, image.width, image.height, pixels, 0, image.width)
    ImageIO.write(output, "png", File("build/reports/ui/$name.png").apply { parentFile.mkdirs() })
}
