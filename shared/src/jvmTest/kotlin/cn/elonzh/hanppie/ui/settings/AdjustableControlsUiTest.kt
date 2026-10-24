package cn.elonzh.hanppie.ui.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.app.testConsoleModel
import cn.elonzh.hanppie.ui.design.TestWorkbenchTheme
import cn.elonzh.hanppie.ui.i18n.*
import cn.elonzh.hanppie.ui.robot.remote.input.*
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

/** Real settings, not a semantic-only wrapper; no native reader, sensor or robot is opened. */
@OptIn(ExperimentalTestApi::class)
class AdjustableControlsUiTest {
    @Test fun compact() = scenario(844, 390, 1f, false)
    @Test fun tabletDark() = scenario(1024, 600, 1f, true)
    @Test fun desktop() = scenario(1280, 720, 1f, false)
    @Test fun largeNineLanguages() = scenario(844, 390, 1.4f, true)

    private fun scenario(width: Int, height: Int, scale: Float, dark: Boolean) =
        runDesktopComposeUiTest(width = width, height = height) {
            Localization.initialize("en", "en")
            val model = testConsoleModel()
            val appearance = AppearanceController(AppearanceSettings(if (dark) NightMode.DARK else NightMode.LIGHT))
            val pointer = object : RelativePointer {
                override fun capture(onReady: () -> Unit, onMotion: (Double, Double) -> Unit,
                    onButton: (Boolean, Boolean) -> Unit, onLost: () -> Unit): AutoCloseable = error("No native pointer in settings")
            }
            val gyro = object : AimGyroscope {
                override val available = true
                override fun start(): Unit = error("No native sensor in settings")
                override fun read(): GyroscopeReading? = error("No native sensor in settings")
                override fun stop() = Unit
            }
            fun picture(name: String, tag: String) {
                waitForIdle()
                val image = onNode(isRoot() and hasAnyDescendant(hasTestTag(tag))).captureToImage()
                val pixels = IntArray(image.width * image.height); image.readPixels(pixels)
                val bitmap = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
                bitmap.setRGB(0, 0, image.width, image.height, pixels, 0, image.width)
                val path = File("build/reports/ui/adjustable-keyboard-$width-$height-$scale-$name.png")
                path.parentFile.mkdirs(); ImageIO.write(bitmap, "png", path)
            }
            fun category(id: String) {
                if (onAllNodesWithTag("settings-back").fetchSemanticsNodes().isNotEmpty()) onNodeWithTag("settings-back").performClick()
                onNodeWithTag("settings-category-$id").performScrollTo().performClick()
            }
            fun slider(tag: String, label: String, current: Float, range: ClosedFloatingPointRange<Float>, description: String) {
                val config = onNodeWithTag(tag).performScrollTo().assertIsDisplayed().fetchSemanticsNode().config
                assertEquals(listOf(label), config[SemanticsProperties.ContentDescription])
                assertEquals(description, config[SemanticsProperties.StateDescription])
                assertEquals(current, config[SemanticsProperties.ProgressBarRangeInfo].current, .01f)
                assertEquals(range, config[SemanticsProperties.ProgressBarRangeInfo].range)
                assertNotNull(config.getOrNull(SemanticsActions.SetProgress))
            }
            fun adjust(tag: String, target: Float, accepted: Boolean? = null) {
                onNodeWithTag(tag).performSemanticsAction(SemanticsActions.SetProgress) { action ->
                    val result = action(target)
                    if (accepted != null) assertEquals(accepted, result)
                }
            }
            try {
                waitUntil(timeoutMillis = 5_000) { !model.settingsBusy.value }
                model.controlSettings.value = model.controlSettings.value.copy(inputSensitivities = InputSensitivities(
                    screen = InputSensitivity(SensitivityPreset.CUSTOM)))
                setContent { TestWorkbenchTheme(appearance) {
                    CompositionLocalProvider(LocalDensity provides Density(1f, scale), LocalRelativePointer provides pointer,
                        LocalAimGyroscope provides { gyro }) {
                        SettingsPage(model, Modifier.fillMaxSize().padding(20.dp))
                    }
                } }
                category("media")
                val volume = model.mediaSettings.value.speakerVolume
                slider("speaker-volume-slider", tr(Res.string.speaker_volume), volume.toFloat(), 0f..100f, "$volume%")
                adjust("speaker-volume-slider", 61f)
                slider("speaker-volume-slider", tr(Res.string.speaker_volume), 61f, 0f..100f, "61%")
                val volumeSlider = onNodeWithTag("speaker-volume-slider")
                volumeSlider.requestFocus(); volumeSlider.assertIsFocused()
                volumeSlider.performKeyInput { pressKey(Key.DirectionRight) }
                assertEquals(62, model.mediaSettings.value.speakerVolume)
                volumeSlider.performKeyInput { pressKey(Key.MoveHome) }
                assertEquals(0, model.mediaSettings.value.speakerVolume)
                volumeSlider.performKeyInput { pressKey(Key.PageUp) }
                assertEquals(10, model.mediaSettings.value.speakerVolume)
                volumeSlider.performKeyInput { pressKey(Key.MoveEnd); pressKey(Key.DirectionRight) }
                assertEquals(100, model.mediaSettings.value.speakerVolume)
                volumeSlider.performKeyInput { pressKey(Key.PageDown) }
                assertEquals(90, model.mediaSettings.value.speakerVolume)
                volumeSlider.performKeyInput { keyDown(Key.CtrlLeft); pressKey(Key.DirectionLeft); keyUp(Key.CtrlLeft) }
                assertEquals(90, model.mediaSettings.value.speakerVolume)
                adjust("speaker-volume-slider", 61f)
                picture("media", "settings-page")
                runOnIdle { model.settingsBusy.value = true }
                onNodeWithTag("speaker-volume-slider").assertIsNotEnabled()
                assertNotEquals(true, volumeSlider.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused))
                adjust("speaker-volume-slider", 90f, false)
                assertEquals(61, model.mediaSettings.value.speakerVolume)
                runOnIdle { model.settingsBusy.value = false }
                adjust("speaker-volume-slider", Float.NaN, false)
                category("control")
                val axes = listOf(InputAxis.FORWARD to Res.string.input_axis_forward, InputAxis.LATERAL to Res.string.input_axis_lateral,
                    InputAxis.YAW to Res.string.input_axis_yaw, InputAxis.PITCH to Res.string.input_axis_pitch)
                for ((axis, label) in axes) {
                    val tag = "input-axis-${axis.name}"
                    val value = model.controlSettings.value.inputSensitivities.screen.custom[axis]
                    slider(tag, tr(label), value.toFloat(), 0f..100f, value.toString())
                    adjust(tag, 73f)
                    assertEquals(73, model.controlSettings.value.inputSensitivities.screen.custom[axis])
                }
                for ((tag, label) in listOf("aim-mouse-sensitivity" to Res.string.aim_mouse_sensitivity,
                    "aim-gyro-sensitivity" to Res.string.aim_gyro_sensitivity)) {
                    onNodeWithTag(tag).performScrollTo(); adjust(tag, 64f)
                    slider(tag, tr(label), 64f, 0f..100f, "64")
                }
                onNodeWithTag("input-axis-FORWARD").performScrollTo().requestFocus()
                onNodeWithTag("input-axis-FORWARD").performKeyInput { pressKey(Key.Tab) }
                onNodeWithTag("input-axis-LATERAL").assertIsFocused()
                    .performKeyInput { keyDown(Key.ShiftLeft); pressKey(Key.Tab); keyUp(Key.ShiftLeft) }
                onNodeWithTag("input-axis-FORWARD").assertIsFocused().performKeyInput { pressKey(Key.DirectionUp) }
                assertEquals(AxisSensitivity(74, 73, 73, 73), model.controlSettings.value.inputSensitivities.screen.custom)
                onNodeWithTag("aim-gyro-sensitivity").performScrollTo().requestFocus()
                onNodeWithTag("aim-gyro-sensitivity").performKeyInput { pressKey(Key.DirectionDown) }
                assertEquals(63, model.controlSettings.value.aimInput.gyroSensitivity)
                assertEquals(64, model.controlSettings.value.aimInput.mouseSensitivity)
                picture("control", "settings-page")
                val kept = model.controlSettings.value
                runOnIdle { model.settingsBusy.value = true }
                for (tag in listOf("input-axis-FORWARD", "aim-mouse-sensitivity", "aim-gyro-sensitivity")) {
                    onNodeWithTag(tag).performScrollTo().assertIsNotEnabled(); adjust(tag, 10f, false)
                }
                assertEquals(kept, model.controlSettings.value)
                runOnIdle { model.settingsBusy.value = false }
                category("lights")
                val original = model.controlSettings.value.remoteLeds.standby
                onNodeWithTag("remote-led-standby").performScrollTo().performClick()
                val languages = if (scale > 1f) AppLanguage.entries else listOf(AppLanguage.ENGLISH)
                for (language in languages) {
                    runOnIdle { Localization.select(language.preference) }
                    adjust("led-hue", 180f)
                    adjust("led-saturation", 100f)
                    adjust("led-brightness", 50f)
                    slider("led-hue", tr(Res.string.color_hue), 180f, 0f..360f, "180°")
                    slider("led-saturation", tr(Res.string.color_saturation), 100f, 0f..100f, "100%")
                    slider("led-brightness", tr(Res.string.color_brightness), 50f, 0f..100f, "50%")
                    onNodeWithTag("led-hue").requestFocus()
                    onNodeWithTag("led-hue").performKeyInput { pressKey(Key.Tab) }
                    onNodeWithTag("led-saturation").assertIsFocused().performKeyInput { pressKey(Key.Tab) }
                    onNodeWithTag("led-brightness").assertIsFocused().performKeyInput { pressKey(Key.DirectionUp) }
                    slider("led-brightness", tr(Res.string.color_brightness), 51f, 0f..100f, "51%")
                    adjust("led-brightness", 50f)
                    assertEquals(original, model.controlSettings.value.remoteLeds.standby)
                    onNodeWithTag("led-color-apply").performScrollTo().assertIsDisplayed()
                    picture("color-${language.preference}", "led-color-apply")
                    // Repeated actions at the same value must not dispatch another adjustment.
                    adjust("led-brightness", 50f, false)
                    adjust("led-brightness", 60f)
                }
                onNodeWithText(tr(Res.string.cancel)).performScrollTo().performClick()
                assertEquals(original, model.controlSettings.value.remoteLeds.standby)
                onNodeWithTag("remote-led-standby").performScrollTo().performClick()
                adjust("led-hue", 180f); adjust("led-saturation", 100f); adjust("led-brightness", 50f)
                onNodeWithTag("led-color-apply").performScrollTo().performClick()
                assertEquals(RobotLedColor(0, 128, 128), model.controlSettings.value.remoteLeds.standby)
                assertFalse(model.state.value.connected)
            } finally { model.close(); Localization.initialize("zh", "zh") }
        }
}
