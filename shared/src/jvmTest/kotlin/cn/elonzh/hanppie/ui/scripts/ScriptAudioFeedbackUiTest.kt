package cn.elonzh.hanppie.ui.scripts

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.robot.lab.LabAudioClip
import cn.elonzh.hanppie.robot.lab.LabProgram
import cn.elonzh.hanppie.ui.app.*
import cn.elonzh.hanppie.ui.design.WorkbenchTheme
import cn.elonzh.hanppie.ui.i18n.Localization
import cn.elonzh.hanppie.ui.i18n.tr
import cn.elonzh.hanppie.ui.robot.audio.*
import cn.elonzh.hanppie.ui.settings.*
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/** Real script page/library; synthetic host files and codec, no file picker, audio device or robot. */
@OptIn(ExperimentalTestApi::class)
class ScriptAudioFeedbackUiTest {
    @Test fun compact() = scenario(844,390,1f,false)
    @Test fun tabletDark() = scenario(1024,768,1f,true)
    @Test fun desktop() = scenario(1280,720,1f,false)
    @Test fun largeText() = scenario(844,390,1.4f,true)

    private fun scenario(width: Int, height: Int, scale: Float, dark: Boolean) =
        runDesktopComposeUiTest(width = width, height = height) {
            Localization.initialize("en", "en")
            val repository = MemoryScriptRepository()
            val script = runBlocking { ScriptLibrary(repository).let { it.load(); it.create("audio fixture", "print('keep')") } }
            var huge = false
            val importer = object : LabAudioImporter {
                override suspend fun decode(name: String, bytes: ByteArray) =
                    DecodedLabAudio(1000,ByteArray(if (huge) LabProgram.MAX_DSP_BYTES else 600))
            }
            val model = testConsoleModel(scriptRepository = repository, audioImporter = importer)
            val document = mutableStateOf(EditorDocument.from(script))
            val appearance = AppearanceController(AppearanceSettings(if (dark) NightMode.DARK else NightMode.LIGHT))
            fun capture(name: String) {
                waitForIdle()
                val image = onNode(isRoot() and hasAnyDescendant(hasTestTag("script-audio-panel"))).captureToImage()
                val pixels=IntArray(image.width*image.height);image.readPixels(pixels)
                val bitmap=BufferedImage(image.width,image.height,BufferedImage.TYPE_INT_ARGB)
                bitmap.setRGB(0,0,image.width,image.height,pixels,0,image.width)
                val file=File("build/reports/ui/script-audio-feedback-$width-$height-$scale-$name.png")
                file.parentFile.mkdirs();ImageIO.write(bitmap,"png",file)
            }
            fun inspectError() {
                val node=onNodeWithTag("script-audio-error").performScrollTo().assertIsDisplayed()
                node.assertTextEquals(checkNotNull(model.scriptAudio.state.value.error).resolve())
                val bounds=node.fetchSemanticsNode().boundsInRoot
                assertTrue(bounds.width > 0 && bounds.height > 0)
                onNodeWithTag("script-audio-import").performScrollTo().assertIsEnabled().assertHeightIsAtLeast(48.dp)
                node.performScrollTo()
            }
            try {
                runBlocking {
                    model.scriptAudio.open(script.id)
                    repeat(LabAudioClip.MAX_CLIPS) { model.scriptAudio.import("clip$it.wav", byteArrayOf(1)) }
                }
                setContent {
                    val scope=rememberCoroutineScope()
                    CompositionLocalProvider(LocalDensity provides Density(1f,scale)) {
                        WorkbenchTheme(appearance) {
                            ScriptPage(model,document,compact = width < 1200,onImport = {},onExport = {},fileError = null,onFileError = {},
                                onImportAudio = { scope.launch { runCatching { model.scriptAudio.import("fixture.wav",byteArrayOf(1)) } } })
                        }
                    }
                }
                onNodeWithTag("script-audio-manage").performClick()
                onNodeWithTag("script-audio-import").performClick()
                waitUntil(timeoutMillis=5000) { model.scriptAudio.state.value.error?.resource == Res.string.script_audio_slots_full }
                inspectError();capture("slots")
                runBlocking { repeat(LabAudioClip.MAX_CLIPS) { model.scriptAudio.delete(it) } }
                runOnIdle { huge=true }
                onNodeWithTag("script-audio-import").performScrollTo().performClick()
                waitUntil(timeoutMillis=5000) { model.scriptAudio.state.value.error?.resource == Res.string.script_audio_budget_limit }
                val feedback=checkNotNull(model.scriptAudio.state.value.error)
                for (language in listOf("zh","zh-Hant","en","ja","de","es","fr","ko","ru")) {
                    runOnIdle { Localization.select(language) }
                    capture("budget-$language");inspectError()
                    assertEquals(feedback,model.scriptAudio.state.value.error)
                }
                runOnIdle { huge=false }
                onNodeWithText(tr(Res.string.close)).performClick()
                onNodeWithTag("script-audio-manage").performClick()
                waitUntil(timeoutMillis=5000) { model.scriptAudio.state.value.error == null }
                onNodeWithTag("script-audio-error").assertDoesNotExist();capture("reopened")
                onNodeWithTag("script-audio-import").performScrollTo().performClick()
                waitUntil(timeoutMillis=5000) { model.scriptAudio.state.value.error == null && model.scriptAudio.state.value.clips.size == 1 }
                onNodeWithTag("script-audio-error").assertDoesNotExist()
                onNodeWithText("fixture").assertIsDisplayed();capture("recovered")
                assertEquals("print('keep')",document.value.source)
                assertEquals(listOf(0),runBlocking { repository.audio(script.id) }.map { it.id })
                assertFalse(model.state.value.connected)
            } finally { model.close();Localization.initialize("zh",null) }
        }
}
