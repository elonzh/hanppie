package cn.elonzh.hanppie.ui.app

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.design.TestWorkbenchTheme
import cn.elonzh.hanppie.ui.guide.*
import cn.elonzh.hanppie.ui.i18n.Localization
import cn.elonzh.hanppie.ui.i18n.tr
import cn.elonzh.hanppie.ui.scripts.EditorDocument
import cn.elonzh.hanppie.ui.settings.*
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

/** Real Console routes and controllers. The home GPU renderer is omitted; no robot is connected. */
@OptIn(ExperimentalTestApi::class)
class UsageGuideUiTest {
    @Test fun compactLandscape() = scenario(844, 390, "zh", 1f, false)
    @Test fun mediumDarkLandscape() = scenario(1024, 768, "zh-Hant", 1f, true)
    @Test fun desktopLandscape() = scenario(1280, 720, "zh", 1f, false)
    @Test fun largeEnglishLandscape() = scenario(844, 390, "en", 1.4f, true)
    @Test fun largeJapaneseLandscape() = scenario(844, 390, "ja", 1.4f, true)

    @Test fun destinationActionsUseExistingRoutesWithoutStartingControl() = runDesktopComposeUiTest(width = 1024, height = 768) {
        Localization.initialize("zh", "zh")
        var opens = 0
        val connector = object : cn.elonzh.hanppie.robot.gamepad.GamepadConnector {
            override val available = true
            override fun accessories() = emptyList<cn.elonzh.hanppie.robot.gamepad.GamepadAccessory>()
            override suspend fun open(id: String): cn.elonzh.hanppie.robot.gamepad.GamepadTransport { opens++; error("Guide must not connect an accessory") }
        }
        val model = testConsoleModel(gamepadConnector = connector)
        data class DestinationCase(val topic: GuideTopic, val step: Int, val target: String, val page: String, val back: String?)
        val destinations = listOf(
            DestinationCase(GuideTopic.FIRST_CONNECTION, 0, "CONNECTION", "connection-guide-page", "connection-guide-back"),
            DestinationCase(GuideTopic.FIRST_CONNECTION, 1, "HEALTH", "settings-detail-robot", null),
            DestinationCase(GuideTopic.DRIVING, 2, "ROBOT", "device-page", null),
            DestinationCase(GuideTopic.MEDIA, 1, "ALBUM", "robot-album-page", "album-back"),
            DestinationCase(GuideTopic.PYTHON, 2, "SCRIPTS", "script-library", null),
            DestinationCase(GuideTopic.ACCESSORIES, 0, "GAMEPAD", "gamepad-page", "gamepad-back"),
            DestinationCase(GuideTopic.ACCESSORIES, 2, "ARM", "arm-page", "arm-back"),
            DestinationCase(GuideTopic.MAINTENANCE, 2, "ADDRESSING", "robot-addressing-page", "addressing-back"),
            DestinationCase(GuideTopic.MAINTENANCE, 3, "CALIBRATION", "robot-calibration-page", "calibration-back"),
            DestinationCase(GuideTopic.MAINTENANCE, 4, "FIRMWARE", "robot-firmware-page", "firmware-back"),
        )
        try {
            waitUntil(timeoutMillis = 5_000) { !model.settingsBusy.value }
            setContent { TestWorkbenchTheme { Console(model, remember { mutableStateOf(EditorDocument()) }) } }
            for (destination in destinations) {
                onNodeWithTag("home-user-guide").performClick()
                onNodeWithTag("usage-guide-topic-${destination.topic.name}").performScrollTo().performClick()
                repeat(destination.step) { onNodeWithTag("usage-guide-next").performClick() }
                onNodeWithTag("usage-guide-open-${destination.target}").performScrollTo().performClick()
                onNodeWithTag(destination.page).assertIsDisplayed()
                assertFalse(model.remoteEnabled.value)
                assertFalse(model.state.value.connected)
                if (destination.back != null) {
                    onNodeWithTag(destination.back).performClick()
                    onNodeWithTag("usage-guide-progress").assertTextEquals(tr(Res.string.guide_progress, destination.step + 1, destination.topic.steps.size))
                    onNodeWithTag("usage-guide-back").performClick()
                    onNodeWithTag("usage-guide-back").performClick()
                } else if (destination.target != "ROBOT") onNodeWithTag("navigate-0").performClick()
                onNodeWithTag("home-user-guide").assertIsDisplayed()
            }
            assertEquals(0, opens)
        } finally { model.close(); Localization.initialize("zh", null) }
    }

    private fun scenario(width: Int, height: Int, language: String, scale: Float, dark: Boolean) =
        runDesktopComposeUiTest(width = width, height = height) {
            Localization.initialize(language, language)
            val model = testConsoleModel()
            val document = mutableStateOf(EditorDocument(source = "print('retained draft')"))
            val appearance = AppearanceController(AppearanceSettings(if (dark) NightMode.DARK else NightMode.LIGHT))
            val prefix = "guides-$width-$height-$language-$scale-${if (dark) "dark" else "light"}"
            fun capture(name: String) {
                waitForIdle()
                val image = onRoot().captureToImage()
                val pixels = IntArray(image.width * image.height); image.readPixels(pixels)
                val bitmap = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
                bitmap.setRGB(0, 0, image.width, image.height, pixels, 0, image.width)
                val file = File("build/reports/ui/$prefix-$name.png"); file.parentFile.mkdirs(); ImageIO.write(bitmap, "png", file)
            }
            fun step(topic: GuideTopic, index: Int) {
                waitForIdle()
                onNodeWithTag("usage-guide-progress").assertTextEquals(tr(Res.string.guide_progress, index + 1, topic.steps.size))
                onNodeWithTag("usage-guide-step-title").assertTextEquals(tr(topic.steps[index].title))
                onNodeWithTag("usage-guide-step-text").assertTextEquals(tr(topic.steps[index].body))
                onNodeWithTag("usage-guide-next").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
                onNodeWithTag("usage-guide-previous").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            }
            fun assertCompactDestinationVisible(target: GuideDestination) {
                if (width == 844 && language == "zh") {
                    onNodeWithTag("usage-guide-more").assertDoesNotExist()
                    val action = onNodeWithTag("usage-guide-open-${target.name}").assertIsDisplayed()
                    assertTrue(action.fetchSemanticsNode().boundsInRoot.height >= 52f, "Destination must retain its full visible target")
                }
            }
            try {
                waitUntil(timeoutMillis = 5_000) { !model.settingsBusy.value }
                setContent {
                    CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                        TestWorkbenchTheme(appearance) { Console(model, document) }
                    }
                }
                onNodeWithTag("home-user-guide").assertIsDisplayed(); capture("home")
                onNodeWithTag("home-user-guide").performClick()
                onNodeWithTag("usage-guide-page").assertIsDisplayed(); capture("index")
                for (topic in GuideTopic.entries) {
                    onNodeWithTag("usage-guide-topic-${topic.name}").performScrollTo().performClick()
                    step(topic, 0); onNodeWithTag("usage-guide-previous").assertIsNotEnabled()
                    for (index in topic.steps.indices) {
                        if (index > 0) onNodeWithTag("usage-guide-next").performClick()
                        step(topic, index)
                        if (topic == GuideTopic.FIRST_CONNECTION && index == 0) {
                            capture("first-step")
                            assertCompactDestinationVisible(GuideDestination.CONNECTION)
                            if (language == "en" || language == "ja") {
                                onNodeWithTag("usage-guide-more").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
                                onNodeWithTag("usage-guide-open-CONNECTION").assertIsDisplayed()
                                capture("first-step-scrolled")
                            }
                            onNodeWithTag("usage-guide-open-CONNECTION").performScrollTo().performClick()
                            onNodeWithTag("connection-guide-page").assertIsDisplayed(); capture("connection-handoff")
                            onNodeWithTag("connection-guide-back").performClick(); step(topic, index)
                        }
                        if (topic == GuideTopic.ACCESSORIES && index == 0) onNodeWithTag("usage-guide-open-GAMEPAD").assertDoesNotExist()
                        if ((topic == GuideTopic.DRIVING || topic == GuideTopic.TRACKING || topic == GuideTopic.MAINTENANCE) && index == topic.steps.lastIndex) {
                            capture(topic.name.lowercase())
                            assertCompactDestinationVisible(topic.steps[index].destination!!)
                            if (language == "en" || language == "ja") {
                                onNodeWithTag("usage-guide-more").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
                                onNodeWithTag("usage-guide-open-${topic.steps[index].destination!!.name}").assertIsDisplayed()
                                capture("${topic.name.lowercase()}-scrolled")
                            }
                        }
                    }
                    onNodeWithTag("usage-guide-restart").assertIsDisplayed().performClick(); step(topic, 0)
                    onNodeWithTag("usage-guide-next").performClick(); step(topic, 1)
                    onNodeWithTag("usage-guide-previous").performClick(); step(topic, 0)
                    onNodeWithTag("usage-guide-back").performClick()
                    onNodeWithTag("usage-guide-progress").assertDoesNotExist()
                }
                onNodeWithTag("usage-guide-back").performClick()
                onNodeWithTag("home-user-guide").assertIsDisplayed()
                onNodeWithTag("navigate-4").performClick()
                onNodeWithTag("settings-category-about").performScrollTo().performClick()
                onNodeWithTag("about-user-guide").assertIsDisplayed(); capture("about-entry")
                onNodeWithTag("about-user-guide").performClick()
                onNodeWithTag("usage-guide-topic-FIRST_CONNECTION").assertIsDisplayed()
                onNodeWithTag("usage-guide-back").performClick()
                onNodeWithTag("about-user-guide").assertIsDisplayed()
                assertEquals("print('retained draft')", document.value.source)
                assertFalse(model.remoteEnabled.value)
                assertFalse(model.state.value.connected)
                assertTrue(model.remoteInput.value.all { it == 0.0 })
            } finally { model.close(); Localization.initialize("zh", null) }
        }
}
