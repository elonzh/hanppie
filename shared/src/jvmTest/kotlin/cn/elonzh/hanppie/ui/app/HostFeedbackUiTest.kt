package cn.elonzh.hanppie.ui.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cn.elonzh.hanppie.agent.runtime.openAgentRuntimeStorage
import cn.elonzh.hanppie.agent.skills.SkillLibrary
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.design.TestWorkbenchTheme
import cn.elonzh.hanppie.ui.i18n.Localization
import cn.elonzh.hanppie.ui.i18n.uiText
import cn.elonzh.hanppie.ui.scripts.EditorDocument
import cn.elonzh.hanppie.ui.settings.*
import kotlinx.coroutines.*
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.test.*

/** Real workbench/persistence failures in temporary storage; native file dialogs are not invoked. */
@OptIn(ExperimentalTestApi::class)
class HostFeedbackUiTest {
    @Test fun compact() = scenario(844, 390, 1f, false)
    @Test fun tabletDark() = scenario(1024, 768, 1f, true)
    @Test fun desktop() = scenario(1280, 720, 1f, false)
    @Test fun largeNineLanguages() = scenario(844, 390, 1.4f, true, allLanguages = true)

    private fun scenario(width: Int, height: Int, scale: Float, dark: Boolean, allLanguages: Boolean = false) =
        runDesktopComposeUiTest(width = width, height = height) {
            val temporary = Files.createTempDirectory("hanppie-host-feedback-")
            val base = MemorySettingsStore(); val failSave = AtomicBoolean(true); val failSpeech = AtomicBoolean(true); val saves = AtomicInteger(0)
            val store = object : SettingsStore by base {
                override suspend fun loadUi(): UiPreferences = error("synthetic EACCES")
                override suspend fun saveLanguage(language: String) {
                    saves.incrementAndGet()
                    if (failSave.get()) error("synthetic ENOSPC")
                    base.saveLanguage(language)
                }
                override suspend fun saveSpeechService(service: String) {
                    if (failSpeech.get()) error("synthetic SPEECH EIO")
                    base.saveSpeechService(service)
                }
            }
            val storageScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val storage = WorkbenchStorage(store, MemoryScriptRepository(),
                CompletableDeferred<SkillLibrary>(storageScope.coroutineContext[Job]),
                openAgentRuntimeStorage(temporary.resolve("runtime.db"), temporary.resolve("events")), storageScope)
            val model = testConsoleModel(settingsStore = store)
            val appearance = AppearanceController(AppearanceSettings(if (dark) NightMode.DARK else NightMode.LIGHT))
            lateinit var holder: WorkbenchViewModel
            val ready = AtomicBoolean(false)
            val closed = AtomicBoolean(false)
            val diagnostic = "synthetic EIO " + "long/file/path/".repeat(200)
            fun capture(name: String) {
                waitForIdle()
                val image = onNode(isRoot() and hasAnyDescendant(hasTestTag("navigate-4"))).captureToImage()
                val pixels = IntArray(image.width * image.height); image.readPixels(pixels)
                val output = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
                output.setRGB(0, 0, image.width, image.height, pixels, 0, image.width)
                val file = File("build/reports/ui/host-feedback-$width-$height-$scale-$name.png")
                file.parentFile.mkdirs(); ImageIO.write(output, "png", file)
            }
            fun feedback() {
                onAllNodesWithTag("host-feedback").assertCountEquals(1)
                onNodeWithTag("host-feedback-message").assertIsDisplayed().assertTextEquals(checkNotNull(holder.hostError).resolve())
                onNodeWithTag("host-feedback-dismiss").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            }
            try {
                setContent {
                    val workbench = remember { WorkbenchViewModel(model, storage, "en") }
                    SideEffect { holder = workbench; ready.set(true) }
                    TestWorkbenchTheme(appearance) {
                        CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                            Console(model, workbench.document, hostError = workbench.hostError, onHostError = workbench::updateHostError,
                                onExport = { workbench.updateHostError(uiText(Res.string.host_file_failed_value, diagnostic)) })
                        }
                    }
                }
                waitUntil(timeoutMillis = 5_000) { ready.get() && holder.uiPreferencesLoaded && !model.settingsBusy.value }
                feedback(); assertEquals(Res.string.host_preferences_load_failed_value, holder.hostError?.resource)
                capture("home-load-error")
                onNodeWithTag("host-feedback-dismiss").performClick(); assertNull(holder.hostError)
                onNodeWithTag("host-feedback").assertDoesNotExist()
                onNodeWithTag("navigate-4").performClick()
                runOnIdle { Localization.select("en") }
                waitUntil(timeoutMillis = 5_000) { holder.hostError?.resource == Res.string.host_preferences_save_failed_value }
                feedback(); capture("settings-save-error")
                if (allLanguages) for (language in listOf("zh", "zh-Hant", "en", "ja", "de", "es", "fr", "ko", "ru")) {
                    runOnIdle { Localization.select(language) }; feedback(); capture("preferences-$language")
                }
                runOnIdle { failSave.set(false); Localization.select("en") }
                waitUntil(timeoutMillis = 5_000) { holder.hostError == null }
                assertEquals("en", runBlocking { base.loadUi() }.language)
                onNodeWithTag("host-feedback").assertDoesNotExist(); capture("preferences-recovered")
                runOnIdle { holder.saveSpeechService("fixture") }
                waitUntil(timeoutMillis = 5_000) { holder.hostError?.resource == Res.string.host_preferences_save_failed_value }
                runOnIdle { Localization.select("en") }
                feedback(); assertTrue(checkNotNull(holder.hostError).arguments.contains("synthetic SPEECH EIO"))
                capture("preference-scope-error")
                runOnIdle { failSpeech.set(false); holder.saveSpeechService("fixture") }
                waitUntil(timeoutMillis = 5_000) { holder.hostError == null }
                runOnIdle { holder.document.value = EditorDocument(source = "print('kept')", path = "kept.py") }
                onNodeWithTag("navigate-1").performClick()
                onNodeWithTag("script-more").performClick(); onNodeWithTag("script-export").performClick()
                feedback(); onNodeWithTag("script-editor").assertIsDisplayed()
                assertEquals("print('kept')", holder.document.value.source); capture("script-file-error")
                assertTrue(onNodeWithTag("host-feedback-message").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].maxValue() > 0)
                onNodeWithTag("host-feedback-message").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100f) }
                capture("script-file-scrolled")
                onNodeWithTag("host-feedback-message").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -10_000f) }
                if (allLanguages) for (language in listOf("zh", "zh-Hant", "en", "ja", "de", "es", "fr", "ko", "ru")) {
                    runOnIdle { Localization.select(language) }; feedback(); onNodeWithTag("script-editor").assertIsDisplayed()
                    assertEquals(Res.string.host_file_failed_value, holder.hostError?.resource)
                    capture("file-$language")
                }
                onNodeWithTag("host-feedback-dismiss").performClick(); onNodeWithTag("host-feedback").assertDoesNotExist()
                assertEquals("print('kept')", holder.document.value.source)
                onNodeWithTag("navigate-2").performClick()
                runOnIdle { holder.updateHostError(uiText(Res.string.no_application_can_open_this_file)) }
                feedback(); capture("system-open-error")
                assertTrue(saves.get() >= 2)
            } finally {
                if (ready.get()) {
                    runOnIdle { holder.shutdown { closed.set(true) } }
                    waitUntil(timeoutMillis = 5_000) { closed.get() }
                } else { model.close(); storage.close() }
                temporary.toFile().deleteRecursively()
            }
        }
}
