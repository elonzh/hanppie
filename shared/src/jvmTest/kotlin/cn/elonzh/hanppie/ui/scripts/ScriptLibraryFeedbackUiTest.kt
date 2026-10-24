package cn.elonzh.hanppie.ui.scripts

import cn.elonzh.hanppie.ui.i18n.AppLanguage
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.app.*
import cn.elonzh.hanppie.ui.design.WorkbenchTheme
import cn.elonzh.hanppie.ui.i18n.Localization
import cn.elonzh.hanppie.ui.settings.*
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/** Real script editor and library with synthetic storage failures; no network or robot. */
@OptIn(ExperimentalTestApi::class)
class ScriptLibraryFeedbackUiTest {
    @Test fun compact() = scenario(844,390,1f,false)
    @Test fun tabletDark() = scenario(1024,768,1f,true)
    @Test fun desktop() = scenario(1280,720,1f,false)
    @Test fun largeText() = scenario(844,390,1.4f,true)

    private fun scenario(width: Int,height: Int,scale: Float,dark: Boolean) =
        runDesktopComposeUiTest(width=width,height=height) {
            Localization.initialize("en","en")
            val store=MemoryScriptRepository()
            val original=StoredScript("saved-id","kept","print('saved')",1,1)
            runBlocking { store.insert(original) }
            val failLoad=AtomicBoolean(true);val failWrite=AtomicBoolean(false)
            val repository=object : ScriptRepository by store {
                override suspend fun all(): List<StoredScript> =
                    if (failLoad.get()) error("synthetic EACCES") else store.all()
                override suspend fun update(script: StoredScript) {
                    if (failWrite.get()) error("synthetic EIO")
                    store.update(script)
                }
            }
            val model=testConsoleModel(scriptRepository=repository)
            val document=mutableStateOf(EditorDocument.from(original).copy(source="print('before')"))
            val appearance=AppearanceController(AppearanceSettings(if (dark) NightMode.DARK else NightMode.LIGHT))
            val copiedErrors=mutableListOf<cn.elonzh.hanppie.ui.i18n.UiText>()
            fun capture(name: String) {
                waitForIdle()
                val image=onNode(isRoot() and hasAnyDescendant(hasTestTag("script-editor"))).captureToImage()
                val pixels=IntArray(image.width*image.height);image.readPixels(pixels)
                val bitmap=BufferedImage(image.width,image.height,BufferedImage.TYPE_INT_ARGB)
                bitmap.setRGB(0,0,image.width,image.height,pixels,0,image.width)
                val file=File("build/reports/ui/script-library-feedback-$width-$height-$scale-$name.png")
                file.parentFile.mkdirs();ImageIO.write(bitmap,"png",file)
            }
            fun inspectError() {
                onAllNodesWithTag("script-library-error").assertCountEquals(1)
                onAllNodesWithTag("script-back").assertCountEquals(1)
                onNodeWithTag("script-library-error").assertIsDisplayed()
                    .assertTextEquals(checkNotNull(model.scriptLibrary.state.value.error).resolve())
                onNodeWithTag("script-library-reload").assertIsDisplayed().assertIsEnabled().assertHeightIsAtLeast(48.dp)
                onNodeWithTag("script-editor").assertIsDisplayed()
                assertEquals("print('draft')",document.value.source)
            }
            try {
                waitUntil(timeoutMillis=5000) { !model.scriptLibrary.state.value.loading }
                setContent {
                    CompositionLocalProvider(LocalDensity provides Density(1f,scale)) {
                        WorkbenchTheme(appearance) {
                            ScriptPage(model,document,compact=width<1200,onImport={},onExport={},
                                onHostError={ if(it!=null) copiedErrors+=it })
                        }
                    }
                }
                onNodeWithTag("script-editor").performTextReplacement("print('draft')")
                waitUntil(timeoutMillis=5000) { document.value.source == "print('draft')" }
                val feedback=checkNotNull(model.scriptLibrary.state.value.error)
                for(language in AppLanguage.entries.map { it.preference }) {
                    runOnIdle { Localization.select(language) }
                    capture("load-$language");inspectError();onNodeWithTag("script-save").assertIsNotEnabled()
                    assertEquals(feedback,model.scriptLibrary.state.value.error)
                }
                failLoad.set(false)
                onNodeWithTag("script-library-reload").performClick()
                waitUntil(timeoutMillis=5000) { model.scriptLibrary.state.value.initialized && !model.scriptLibrary.state.value.loading }
                onNodeWithTag("script-library-error").assertDoesNotExist()
                onNodeWithTag("script-save").assertIsEnabled();capture("reloaded")
                assertEquals("print('draft')",document.value.source)
                failWrite.set(true)
                onNodeWithTag("script-save").performClick()
                waitUntil(timeoutMillis=5000) { model.scriptLibrary.state.value.error?.resource == Res.string.script_library_operation_failed_value && !document.value.busy }
                inspectError();capture("save-failed")
                assertTrue(copiedErrors.isEmpty(),"One library error must not be duplicated into file feedback")
                assertEquals("print('saved')",runBlocking { store.all().single().source })
                failWrite.set(false)
                onNodeWithTag("script-save").performClick()
                waitUntil(timeoutMillis=5000) { model.scriptLibrary.state.value.error==null && !document.value.dirty && !document.value.busy }
                onNodeWithTag("script-library-error").assertDoesNotExist()
                assertEquals("saved-id",document.value.scriptId)
                assertEquals("print('draft')",runBlocking { store.all().single().source })
                capture("saved")
                assertFalse(model.state.value.connected)
            } finally { model.close();Localization.initialize("zh",null) }
        }
}
