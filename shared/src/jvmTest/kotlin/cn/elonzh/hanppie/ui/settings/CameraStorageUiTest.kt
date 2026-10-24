package cn.elonzh.hanppie.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cn.elonzh.hanppie.robot.media.*
import cn.elonzh.hanppie.robot.session.*
import cn.elonzh.hanppie.ui.app.testConsoleModel
import cn.elonzh.hanppie.ui.design.TestWorkbenchTheme
import cn.elonzh.hanppie.ui.i18n.Localization
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

private class StorageFixture : RobotCameraService {
    val card = CameraStorageStatus(true, 0, 32768, 16384, 5000, 3600, false, false, 0)
    override val state = MutableStateFlow(CameraState(100, timeSynced = true, storage = card))
    var formats = 0
    var formatError: RequestProblem? = null
    override suspend fun takePhoto() { error("unused") }
    override suspend fun setRecording(active: Boolean) { error("unused") }
    override suspend fun formatStorage() {
        formats++
        formatError?.let { throw RobotRequestException(it) }
    }
    override suspend fun setPhotoSize(size: cn.elonzh.hanppie.robot.media.CameraPhotoSize) = error("unused")
    override suspend fun setTransmissionRate(rate: cn.elonzh.hanppie.robot.media.VideoTransmissionRate) = error("unused")
    override suspend fun switchCamera(index: Int) = error("unused")
    override suspend fun requestKeyFrame() = error("unused")
    override suspend fun setZoom(hundredths: Int, smooth: Boolean) = error("unused")
    override suspend fun getAntiFlicker(): Int = error("unused")
    override suspend fun setAntiFlicker(mode: AntiFlicker): Int = error("unused")
    override suspend fun getDate(): CameraDate? = error("unused")
    override suspend fun syncClock(): CameraDate = error("unused")
}

class CameraStorageUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun maintenanceEntryRequiresConfirmationAndShowsAckIndependentlyFromFormatting() {
        Localization.initialize("zh", null)
        val model = testConsoleModel()
        val camera = StorageFixture()
        model.state.value = model.state.value.copy(connected = true)
        model.robotCamera.attach(camera)
        try {
            rule.setContent { TestWorkbenchTheme { SettingsPage(model) } }
            rule.onNodeWithTag("settings-category-robot").performScrollTo().performClick()
            rule.onNodeWithTag("settings-save").assertDoesNotExist()
            rule.onNodeWithTag("camera-storage-space").assertTextEquals("可用 16384 MB / 共 32768 MB")
            rule.onNodeWithTag("camera-storage-format").performScrollTo().performClick()
            rule.onNodeWithText("存储卡上的全部文件将被删除，无法撤销。请先保存需要的文件。").assertIsDisplayed()
            rule.onNodeWithTag("camera-storage-format-cancel").performClick()
            rule.runOnIdle { assertEquals(0, camera.formats) }
            rule.onNodeWithTag("camera-storage-format").performClick()
            rule.onNodeWithTag("camera-storage-format-confirm").performClick()
            rule.waitUntil(3_000) { camera.formats == 1 }
            rule.onNodeWithTag("camera-storage-format-result").performScrollTo().assertTextEquals("格式化请求已确认")
            rule.onNodeWithTag("camera-storage-status").performScrollTo().assertTextEquals("存储卡可用")
            rule.runOnIdle { camera.state.value = camera.state.value.copy(storage = camera.card.copy(stateCode = 5)) }
            waitForStatus("正在格式化存储卡…")
            rule.onNodeWithTag("camera-storage-format").assertIsNotEnabled()
            rule.runOnIdle { camera.state.value = camera.state.value.copy(storage = camera.card.copy(inserted = false)) }
            waitForStatus("未插入存储卡")
            rule.onNodeWithTag("camera-storage-space").assertDoesNotExist()
            rule.onNodeWithTag("camera-storage-status").assertTextEquals("未插入存储卡")
            rule.runOnIdle { model.robotCamera.clear(); model.state.value = model.state.value.copy(connected = false) }
            waitForStatus("连接机器人后可查看存储卡并格式化")
            rule.onNodeWithTag("camera-storage-format").assertDoesNotExist()
            rule.onNodeWithTag("camera-storage-format-result").assertDoesNotExist()
        } finally { model.close() }
    }

    @Test fun confirmationIsInvalidatedByBusyMissingStaleOrReboundStorageAndUncertainResultDoesNotRetry() {
        Localization.initialize("zh", null)
        val model = testConsoleModel()
        val camera = StorageFixture()
        model.state.value = model.state.value.copy(connected = true)
        model.robotCamera.attach(camera)
        try {
            rule.setContent { TestWorkbenchTheme { SettingsPage(model) } }
            rule.onNodeWithTag("settings-category-robot").performScrollTo().performClick()
            for (invalid in listOf(camera.card.copy(recording = true), camera.card.copy(shooting = true),
                camera.card.copy(inserted = false), null)) {
                rule.runOnIdle { camera.state.value = camera.state.value.copy(storage = camera.card) }
                waitForStatus("存储卡可用")
                rule.onNodeWithTag("camera-storage-format").performScrollTo().performClick()
                rule.runOnIdle { camera.state.value = camera.state.value.copy(storage = invalid) }
                waitForConfirmationToClose()
                rule.onNodeWithTag("camera-storage-format").assertIsNotEnabled()
            }
            rule.runOnIdle { camera.state.value = camera.state.value.copy(storage = camera.card) }
            waitForStatus("存储卡可用")
            rule.onNodeWithTag("camera-storage-format").performClick()
            rule.runOnIdle { model.robotCamera.attach(camera) }
            waitForConfirmationToClose()
            rule.runOnIdle { assertEquals(0, camera.formats); camera.formatError = RequestProblem.TIMED_OUT }
            rule.onNodeWithTag("camera-storage-format").performClick()
            rule.onNodeWithTag("camera-storage-format-confirm").performClick()
            rule.waitUntil(3_000) { camera.formats == 1 }
            rule.onNodeWithTag("camera-storage-format-result").performScrollTo()
                .assertTextEquals("未确认格式化请求的结果，请先检查存储卡状态。")
            rule.runOnIdle { camera.state.value = camera.state.value.copy(storage = camera.card.copy(stateCode = 8)) }
            waitForStatus("存储卡已满")
            rule.onNodeWithTag("camera-storage-status").performScrollTo().assertTextEquals("存储卡已满")
            rule.onNodeWithTag("camera-storage-format").assertIsEnabled()
            rule.runOnIdle { camera.state.value = camera.state.value.copy(storage = camera.card.copy(stateCode = 2)) }
            waitForStatus("存储卡异常")
            rule.runOnIdle { assertEquals(1, camera.formats) }
        } finally { model.close() }
    }

    private fun waitForStatus(text: String) {
        rule.waitUntil(3_000) {
            rule.onAllNodes(hasTestTag("camera-storage-status") and hasText(text)).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("camera-storage-status").assertTextEquals(text)
    }

    private fun waitForConfirmationToClose() {
        rule.waitUntil(3_000) { rule.onAllNodes(hasTestTag("camera-storage-format-confirm")).fetchSemanticsNodes().isEmpty() }
    }
}

@OptIn(ExperimentalTestApi::class)
class CameraStorageLayoutTest {
    @Test fun compactLandscape() = layout(844, 390)
    @Test fun tabletLandscape() = layout(1024, 600)
    @Test fun desktopLandscape() = layout(1280, 720)
    @Test fun englishLargeText() = layout(844, 390, "en", 1.4f)

    private fun layout(width: Int, height: Int, language: String = "zh", fontScale: Float = 1f) =
        runDesktopComposeUiTest(width = width, height = height) {
            Localization.initialize(language, null)
            val model = testConsoleModel()
            model.state.value = model.state.value.copy(connected = true)
            model.robotCamera.attach(StorageFixture())
            try {
                setContent { TestWorkbenchTheme {
                    CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                        Box(Modifier.fillMaxSize().testTag("storage-layout").padding(16.dp)) { SettingsPage(model) }
                    }
                } }
                onNodeWithTag("settings-category-robot").performScrollTo().performClick()
                waitUntil(timeoutMillis = 3_000) { onAllNodes(hasTestTag("camera-storage-space")).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("camera-storage-format").performScrollTo().assertIsDisplayed().assertIsEnabled()
                assertTrue(onNodeWithTag("camera-storage-format").fetchSemanticsNode().boundsInRoot.height >= 48)
                snapshot("storage-$width-$height-$language", onNodeWithTag("storage-layout"))
                if (width == 844) {
                    onNodeWithTag("camera-storage-format").performClick()
                    onNodeWithTag("camera-storage-format-confirm").assertIsDisplayed()
                    onNodeWithTag("camera-storage-format-cancel").assertIsDisplayed()
                    snapshot("storage-confirm-$width-$height-$language",
                        onNode(isRoot() and hasAnyDescendant(hasTestTag("camera-storage-format-confirm"))))
                }
            } finally { model.close(); Localization.initialize("zh", null) }
        }

    private fun snapshot(name: String, node: SemanticsNodeInteraction) {
        val image = node.captureToImage()
        val pixels = IntArray(image.width * image.height); image.readPixels(pixels)
        val out = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        out.setRGB(0, 0, image.width, image.height, pixels, 0, image.width)
        val file = File("build/reports/ui/$name.png").apply { parentFile.mkdirs() }
        ImageIO.write(out, "png", file)
    }
}
