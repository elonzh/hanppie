package cn.elonzh.hanppie.ui.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.design.TestWorkbenchTheme
import cn.elonzh.hanppie.ui.i18n.AppLanguage
import cn.elonzh.hanppie.ui.i18n.Localization
import cn.elonzh.hanppie.ui.i18n.tr
import cn.elonzh.hanppie.ui.robot.audio.DecodedLabAudio
import cn.elonzh.hanppie.ui.robot.audio.LabAudioImporter
import cn.elonzh.hanppie.ui.scripts.*
import cn.elonzh.hanppie.ui.settings.*
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/** Real settings and script pages; shared semantics and actions only, not an OS screen-reader run. */
@OptIn(ExperimentalTestApi::class)
class AccessibilityLabelsUiTest {
    @Test fun compact() = scenario(844, 390, 1f, false)
    @Test fun tabletDark() = scenario(1024, 768, 1f, true)
    @Test fun desktop() = scenario(1280, 720, 1f, false)
    @Test fun largeNineLanguages() = scenario(844, 390, 1.4f, true)

    private fun scenario(width: Int, height: Int, scale: Float, dark: Boolean) =
        runDesktopComposeUiTest(width = width, height = height) {
            Localization.initialize("en", "en")
            val repository = MemoryScriptRepository()
            val saved = StoredScript("local-id", "Kept program", "print('keep')", 1, 1)
            runBlocking { repository.insert(saved) }
            val importer = object : LabAudioImporter {
                override suspend fun decode(name: String, bytes: ByteArray) = DecodedLabAudio(1_000, ByteArray(600))
            }
            val model = testConsoleModel(scriptRepository = repository, audioImporter = importer)
            val appearance = AppearanceController(AppearanceSettings(if (dark) NightMode.DARK else NightMode.LIGHT))
            var scripts by mutableStateOf(false)
            val document = mutableStateOf(EditorDocument())
            fun picture(name: String, tag: String? = null) {
                waitForIdle()
                val content = tag?.let(::hasTestTag) ?: hasText(tr(Res.string.restore_default_settings_question))
                val image = onNode(isRoot() and hasAnyDescendant(content)).captureToImage()
                val pixels = IntArray(image.width * image.height); image.readPixels(pixels)
                val bitmap = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
                bitmap.setRGB(0, 0, image.width, image.height, pixels, 0, image.width)
                val path = File("build/reports/ui/accessibility-labels-$width-$height-$scale-$name.png")
                path.parentFile.mkdirs(); ImageIO.write(bitmap, "png", path)
            }
            fun cleanDescriptions() {
                val descriptions = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription),
                    useUnmergedTree = true).fetchSemanticsNodes().flatMap { it.config[SemanticsProperties.ContentDescription] }
                val internal = Regex("^(script-(?:name|audio-name|open-.*|preset-.*)|.*-selector|night-mode-.*|language-.*|restore-default-settings|remote-led-.*|Hanppie Icon)$")
                assertTrue(descriptions.none { internal.matches(it) }, descriptions.joinToString())
            }
            fun category(id: String) {
                if (onAllNodesWithTag("settings-back").fetchSemanticsNodes().isNotEmpty()) onNodeWithTag("settings-back").performClick()
                onNodeWithTag("settings-category-$id").performScrollTo().performClick()
            }
            fun categories() {
                if (onAllNodesWithTag("settings-back").fetchSemanticsNodes().isNotEmpty()) onNodeWithTag("settings-back").performClick()
                for ((id, title) in listOf(
                    "general" to Res.string.general_settings, "model" to Res.string.model_service,
                    "display" to Res.string.display_settings, "media" to Res.string.audio_video,
                    "robot" to Res.string.robot_maintenance, "control" to Res.string.control,
                    "lights" to Res.string.remote_led_colors, "shortcuts" to Res.string.keyboard_shortcuts,
                    "about" to Res.string.about,
                )) {
                    val node = onNodeWithTag("settings-category-$id").assertTextContains(tr(title)).assertHasClickAction()
                    assertEquals(Role.Button, node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role), id)
                    assertNull(node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription), id)
                }
                onNodeWithTag("settings-category-general").performScrollTo()
            }
            fun field(tag: String, label: String) {
                val node = onNodeWithTag(tag).assertIsDisplayed().assertTextContains(label)
                val config = node.fetchSemanticsNode().config
                assertNull(config.getOrNull(SemanticsProperties.ContentDescription))
                assertNotNull(config.getOrNull(SemanticsActions.SetText))
                cleanDescriptions()
            }
            fun option(tag: String) = onNode(hasAnyAncestor(hasTestTag(tag)) and hasClickAction())
            try {
                waitUntil(timeoutMillis = 5_000) { !model.settingsBusy.value && model.scriptLibrary.state.value.initialized }
                runBlocking { model.scriptAudio.open(saved.id); model.scriptAudio.import("Kept sound.wav", byteArrayOf(1)) }
                setContent {
                    TestWorkbenchTheme(appearance) {
                        CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                            if (scripts) ScriptPage(model, document, compact = width < 1200,
                                onImport = {}, onExport = {}, onHostError = {})
                            else SettingsPage(model, Modifier.fillMaxSize().padding(20.dp))
                        }
                    }
                }
                categories(); picture("categories", "settings-page")
                category("general")
                onNodeWithTag("language-selector").assertTextContains(tr(Res.string.language))
                    .assertTextContains("English").assertHasClickAction()
                onNodeWithTag("night-mode-selector").performScrollTo().assertTextContains(tr(Res.string.night_mode))
                cleanDescriptions(); picture("settings", "settings-page")
                onNodeWithTag("language-selector").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
                option("language-en").assertIsSelected().assertTextContains("English")
                assertEquals(Role.RadioButton, option("language-en").fetchSemanticsNode().config[SemanticsProperties.Role])
                cleanDescriptions(); picture("options", "language-en")
                option("language-en").performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
                if (scale > 1f) for (language in AppLanguage.entries) {
                    runOnIdle { Localization.select(language.preference) }
                    onNodeWithTag("language-selector").assertTextContains(tr(Res.string.language)).assertTextContains(language.nativeName)
                    cleanDescriptions(); picture("settings-${language.preference}", "settings-page")
                    categories(); picture("categories-${language.preference}", "settings-page")
                    category("general")
                }
                runOnIdle { Localization.select("en") }
                category("model")
                onNodeWithTag("model-picker").performScrollTo().performClick()
                val originalModel = model.modelSettings.value.model
                onNodeWithTag("model-option-$originalModel")
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).assertIsSelected()
                val alternative = onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
                    and SemanticsMatcher.expectValue(SemanticsProperties.Selected, false)).onFirst()
                val alternativeId = alternative.fetchSemanticsNode().config[SemanticsProperties.Text].first().text
                alternative.performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
                assertEquals(alternativeId, model.modelSettings.value.model)
                onNodeWithTag("model-picker").performScrollTo().performClick()
                onNodeWithTag("model-option-$originalModel").assertIsNotSelected()
                onNodeWithTag("model-option-$alternativeId").assertIsSelected()
                picture("models", "model-filter")
                onNodeWithTag("model-option-$originalModel").performScrollTo().performClick()
                category("lights")
                onNodeWithTag("remote-led-standby").performScrollTo().assertTextContains(tr(Res.string.remote_led_standby))
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, model.controlSettings.value.remoteLeds.standby.hex))
                cleanDescriptions(); picture("lights", "settings-page")
                category("about")
                onNodeWithContentDescription("Hanppie Icon").assertDoesNotExist(); cleanDescriptions()
                val repositoryLink = onNode(hasText(tr(Res.string.about_repository)) and hasClickAction())
                repositoryLink.performScrollTo().assertHeightIsAtLeast(48.dp)
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                picture("about", "about-settings-content")
                if (onAllNodesWithTag("settings-back").fetchSemanticsNodes().isNotEmpty()) onNodeWithTag("settings-back").performClick()
                onNodeWithTag("restore-default-settings").assertIsDisplayed().assertTextContains(tr(Res.string.restore_defaults))
                    .performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
                cleanDescriptions(); picture("restore")
                onNodeWithText(tr(Res.string.cancel)).performClick()

                runOnIdle { scripts = true }
                onNodeWithTag("script-card-${saved.id}").performScrollTo().assertTextContains(saved.name)
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                val preset = model.scriptLibrary.state.value.presets.first()
                onNodeWithTag("script-preset-${preset.id}").performScrollTo().assertTextContains(preset.name)
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                cleanDescriptions(); picture("library", "script-library")
                onNodeWithTag("script-card-${saved.id}").performScrollTo()
                assertEquals(tr(Res.string.more_actions), onNodeWithTag("script-card-${saved.id}").fetchSemanticsNode()
                    .config[SemanticsActions.OnLongClick].label)
                onNodeWithTag("script-card-${saved.id}").performSemanticsAction(SemanticsActions.OnLongClick) { assertTrue(it()) }
                for (tag in listOf("script-rename-${saved.id}", "script-delete-${saved.id}")) {
                    onNodeWithTag(tag).assertHeightIsAtLeast(48.dp)
                        .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).assertHasClickAction()
                }
                picture("script-actions", "script-rename-${saved.id}")
                if (scale > 1f) for (language in AppLanguage.entries) {
                    runOnIdle { Localization.select(language.preference) }
                    onNodeWithTag("script-rename-${saved.id}").assertTextContains(tr(Res.string.rename_script))
                    onNodeWithTag("script-delete-${saved.id}").assertTextContains(tr(Res.string.delete_script))
                    picture("script-actions-${language.preference}", "script-rename-${saved.id}")
                }
                runOnIdle { Localization.select("en") }
                onNodeWithTag("script-rename-${saved.id}").performClick()
                field("script-name", tr(Res.string.script_name)); picture("script-name", "script-name")
                if (scale > 1f) for (language in AppLanguage.entries) {
                    runOnIdle { Localization.select(language.preference) }
                    field("script-name", tr(Res.string.script_name)); picture("name-${language.preference}", "script-name")
                }
                runOnIdle { Localization.select("en") }
                onNodeWithTag("script-name").performTextReplacement("Draft name")
                onNodeWithText(tr(Res.string.cancel)).performClick()
                assertEquals(saved.name, model.scriptLibrary.state.value.scripts.single().name)
                onNodeWithTag("script-more").performClick()
                for (tag in listOf("script-import", "script-export", "script-delete")) {
                    onNodeWithTag(tag).assertHeightIsAtLeast(48.dp)
                        .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).assertHasClickAction()
                }
                picture("script-files", "script-import")
                onNodeWithText(tr(Res.string.close)).performClick()
                onNodeWithTag("script-audio-manage").performClick()
                onNodeWithTag("script-audio-rename-0").performScrollTo().performClick()
                field("script-audio-name", tr(Res.string.script_audio_rename)); picture("audio-name", "script-audio-name")
                onNodeWithTag("script-audio-name").performTextReplacement("Draft sound")
                onNodeWithText(tr(Res.string.cancel)).performClick()
                assertEquals("Kept sound", model.scriptAudio.state.value.clips.single().name)
                assertEquals(saved.source, document.value.source); assertFalse(model.state.value.connected)
            } finally { model.close(); Localization.initialize("zh", "zh") }
        }
}
