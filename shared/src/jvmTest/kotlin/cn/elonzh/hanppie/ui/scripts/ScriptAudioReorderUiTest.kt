package cn.elonzh.hanppie.ui.scripts

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cn.elonzh.hanppie.robot.lab.LabAudioClip
import cn.elonzh.hanppie.ui.app.MemoryScriptRepository
import cn.elonzh.hanppie.ui.app.testConsoleModel
import cn.elonzh.hanppie.ui.design.TestWorkbenchTheme
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.i18n.AppLanguage
import cn.elonzh.hanppie.ui.i18n.Localization
import cn.elonzh.hanppie.ui.i18n.tr
import cn.elonzh.hanppie.ui.settings.*
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class ScriptAudioReorderUiTest {
    @Test fun compact() = scenario(844, 390, 1f, false)
    @Test fun tabletDark() = scenario(1024, 768, 1f, true)
    @Test fun desktop() = scenario(1280, 720, 1f, false)
    @Test fun large() = scenario(844, 390, 1.4f, true)
    @Test fun failedWriteCanBeRetried() = scenario(1024, 768, 1f, false, failFirst = true)
    @Test fun scriptChangeClosesTheOldMenu() = scenario(1024, 768, 1f, false, switchAtEnd = true)

    private fun scenario(width: Int, height: Int, scale: Float, dark: Boolean, failFirst: Boolean = false, switchAtEnd: Boolean = false) =
        runDesktopComposeUiTest(width = width, height = height) {
            Localization.initialize("en", "en")
            val script = StoredScript("audio-order", "Audio order", "print('keep')", 1, 1)
            val repository = MemoryScriptRepository(initial = listOf(script))
            runBlocking {
                for ((index, id) in listOf(2, 5, 9).withIndex()) {
                    repository.insertAudio(script.id, LabAudioClip(id, if (index < 2) "Same name" else "Recorded sound with a very long author supplied title ".repeat(2).take(64),
                        1_000, ByteArray(600) { (index + 1).toByte() }))
                }
            }
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val writes = AtomicInteger()
            val store = object : ScriptRepository by repository {
                override suspend fun replaceAllAudio(scriptId: String, clips: List<LabAudioClip>) {
                    if (writes.incrementAndGet() == 1) {
                        entered.complete(Unit); release.await()
                        if (failFirst) error("synthetic audio write EIO")
                    }
                    repository.replaceAllAudio(scriptId, clips)
                }
            }
            val model = testConsoleModel(scriptRepository = store)
            val document = mutableStateOf(EditorDocument.from(script))
            fun order(expected: List<Int>) {
                waitUntil(timeoutMillis = 5_000) {
                    !model.scriptAudio.state.value.busy && model.scriptAudio.state.value.clips.map { it.packets.first().toInt() } == expected
                }
                assertEquals(listOf(0, 1, 2), model.scriptAudio.state.value.clips.map { it.id })
                assertEquals(expected, runBlocking { repository.audio(script.id).map { it.packets.first().toInt() } })
            }
            fun picture(name: String) {
                val tag = if (onAllNodesWithTag("script-audio-move-up").fetchSemanticsNodes().isEmpty()) "script-audio-panel" else "script-audio-move-up"
                val root = onNode(isRoot() and hasAnyDescendant(hasTestTag(tag)))
                val image = root.captureToImage(); val pixels = IntArray(image.width * image.height); image.readPixels(pixels)
                val bitmap = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
                bitmap.setRGB(0, 0, image.width, image.height, pixels, 0, image.width)
                val variant = if (failFirst) "retry" else if (switchAtEnd) "switch" else if (dark) "dark" else "light"
                val path = File("build/reports/ui/audio-reorder-$width-$height-$scale-$variant-$name.png")
                path.parentFile.mkdirs(); ImageIO.write(bitmap, "png", path)
            }
            try {
                setContent { TestWorkbenchTheme(AppearanceController(AppearanceSettings(if (dark) NightMode.DARK else NightMode.LIGHT))) {
                    CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                        ScriptPage(model, document, compact = width < 1200, onImport = {}, onExport = {}, onHostError = {})
                    }
                } }
                onNodeWithTag("script-audio-manage").performClick()
                waitUntil(timeoutMillis = 5_000) { model.scriptAudio.state.value.clips.size == 3 }
                val handle = onNodeWithTag("script-audio-reorder-5").performScrollTo().assertHeightIsAtLeast(48.dp)
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                    .assertContentDescriptionEquals(tr(Res.string.script_audio_reorder, "Same name") + " · " +
                        tr(Res.string.script_audio_slot_value, "5", "1", "1"))
                handle.performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
                handle.performKeyInput { keyDown(Key.Enter); keyUp(Key.Enter) }
                picture("menu")
                onNodeWithTag("script-audio-move-up").assertIsDisplayed().assertIsEnabled()
                    .performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
                waitUntil(timeoutMillis = 5_000) { entered.isCompleted && model.scriptAudio.state.value.busy }
                onNodeWithTag("script-audio-reorder-5").assertIsNotEnabled()
                    .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
                assertEquals(1, writes.get())
                picture("saving")
                release.complete(Unit)
                if (failFirst) {
                    waitUntil(timeoutMillis = 5_000) { !model.scriptAudio.state.value.busy && model.scriptAudio.state.value.error != null }
                    assertEquals(listOf(2, 5, 9), runBlocking { repository.audio(script.id).map { it.id } })
                    waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("script-audio-move-up").fetchSemanticsNodes().isEmpty() }
                    onNodeWithTag("script-audio-reorder-5").performScrollTo().assertIsEnabled().performClick()
                    onNodeWithTag("script-audio-move-up").performClick()
                }
                order(listOf(2, 1, 3))
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("script-audio-move-up").fetchSemanticsNodes().isEmpty() }
                onNodeWithTag("script-audio-reorder-0").assertIsFocused()
                picture("focused")
                onNodeWithTag("script-audio-reorder-0").performKeyInput { keyDown(Key.Enter); keyUp(Key.Enter) }
                onNodeWithTag("script-audio-move-up").assertIsNotEnabled()
                val down = onNodeWithTag("script-audio-move-down").assertIsEnabled()
                down.performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
                down.performKeyInput { keyDown(Key.Enter); keyUp(Key.Enter) }
                order(listOf(1, 2, 3))
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("script-audio-move-down").fetchSemanticsNodes().isEmpty() }
                onNodeWithTag("script-audio-reorder-1").assertIsFocused().performScrollTo()
                    .performTouchInput { down(center); moveBy(Offset(0f, 90f)) }
                order(listOf(1, 3, 2))
                onNodeWithTag("script-audio-reorder-1").performTouchInput { up() }
                onNodeWithTag("script-audio-move-up").assertDoesNotExist()
                picture("ordered")
                onNodeWithTag("script-audio-reorder-2").performScrollTo().performClick()
                onNodeWithTag("script-audio-move-down").assertIsNotEnabled()
                if (scale > 1f) {
                    onNodeWithTag("script-audio-reorder-close").performClick()
                    waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("script-audio-move-down").fetchSemanticsNodes().isEmpty() }
                    onNodeWithTag("script-audio-reorder-1").performScrollTo().performClick()
                }
                if (scale > 1f) for (language in AppLanguage.entries) {
                    runOnIdle { Localization.select(language.preference) }
                    onNodeWithTag("script-audio-move-up").assertTextContains(tr(Res.string.script_audio_move_up))
                    onNodeWithTag("script-audio-move-down").assertTextContains(tr(Res.string.script_audio_move_down))
                    onNodeWithTag("script-audio-reorder-close").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
                    picture("menu-${language.preference}")
                }
                if (switchAtEnd) {
                    val other = StoredScript("audio-other", "Other script", "print('other')", 2, 2)
                    runBlocking {
                        repository.insert(other)
                        repeat(2) { index -> repository.insertAudio(other.id, LabAudioClip(index, "Same name", 1_000,
                            ByteArray(600) { (index + 7).toByte() })) }
                    }
                    runOnIdle { document.value = EditorDocument.from(other) }
                    waitUntil(timeoutMillis = 5_000) { model.scriptAudio.state.value.scriptId == other.id && !model.scriptAudio.state.value.loading }
                    waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("script-audio-move-up").fetchSemanticsNodes().isEmpty() }
                    onNodeWithText(other.name).assertIsDisplayed()
                    assertEquals(listOf(7, 8), runBlocking { repository.audio(other.id).map { it.packets.first().toInt() } })
                    assertEquals(other.source, document.value.source)
                } else {
                    onNodeWithTag("script-audio-reorder-close").assertIsDisplayed().performClick()
                    assertEquals(script.source, document.value.source)
                    waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("script-audio-move-up").fetchSemanticsNodes().isEmpty() }
                    onNodeWithText(tr(Res.string.close)).assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
                    onNodeWithTag("script-audio-panel").assertDoesNotExist()
                    onNodeWithTag("script-audio-manage").performClick()
                    order(listOf(1, 3, 2))
                }
                assertFalse(model.state.value.connected)
            } finally { release.complete(Unit); model.close(); Localization.initialize("zh", "zh") }
        }
}
