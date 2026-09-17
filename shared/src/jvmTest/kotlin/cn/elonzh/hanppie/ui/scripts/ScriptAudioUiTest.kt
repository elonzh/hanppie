package cn.elonzh.hanppie.ui.scripts

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import cn.elonzh.hanppie.robot.lab.ScriptRunPhase
import cn.elonzh.hanppie.ui.app.ConsoleState
import cn.elonzh.hanppie.ui.app.MemoryScriptRepository
import cn.elonzh.hanppie.ui.app.testConsoleModel
import cn.elonzh.hanppie.ui.design.WorkbenchTheme
import cn.elonzh.hanppie.ui.i18n.Localization
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

@OptIn(ExperimentalTestApi::class)
class ScriptAudioUiTest {
    @Test fun savedScriptManagesAudioFromTheDialogAndInsertsTheConstant() =
        runDesktopComposeUiTest(width = 1040, height = 700) {
            Localization.initialize("zh", null)
            val repository = MemoryScriptRepository()
            val script = runBlocking {
                val library = ScriptLibrary(repository)
                library.load()
                library.create("巡检", "def start():\n    pass\n")
            }
            runBlocking {
                repository.insertAudio(StoredScriptAudio(script.id, 0, "voice", 1_000, ByteArray(600)))
            }
            val model = testConsoleModel(scriptRepository = repository)
            val document = mutableStateOf(EditorDocument.from(script))
            var importRequested = false
            try {
                setContent {
                    WorkbenchTheme {
                        ScriptPage(
                            model = model,
                            document = document,
                            compact = false,
                            onImport = {},
                            onExport = {},
                            fileError = null,
                            onFileError = {},
                            onImportAudio = { importRequested = true },
                        )
                    }
                }
                // Monitoring keeps the live view and the telemetry together, with the strip below the view.
                onNodeWithTag("script-status-strip").assertIsDisplayed()
                onNodeWithTag("script-video").assertIsDisplayed()
                runOnIdle {
                    val video = onNodeWithTag("script-video").fetchSemanticsNode().boundsInRoot
                    val strip = onNodeWithTag("script-status-strip").fetchSemanticsNode().boundsInRoot
                    assertTrue(video.bottom <= strip.top, "video must sit above the status strip")
                }
                // No frame counter: it changes length and wraps the strip for no operational value.
                assertTrue(onAllNodesWithText("报文", substring = true).fetchSemanticsNodes().isEmpty())
                // Running is the primary action of the editor action bar, at its right edge.
                runOnIdle {
                    val run = onNodeWithTag("script-run").fetchSemanticsNode().boundsInRoot
                    val save = onNodeWithTag("script-save").fetchSemanticsNode().boundsInRoot
                    assertTrue(kotlin.math.abs(run.top - save.top) < 8f, "run must sit on the action bar row")
                    assertTrue(run.left > save.left, "run must sit right of the other actions")
                }
                // The view is already inline, so no monitor entry is offered.
                onNodeWithTag("script-monitor").assertDoesNotExist()

                // The rename icon trails the script name, and the connection chip keeps the right edge.
                runOnIdle {
                    val root = onRoot().fetchSemanticsNode().boundsInRoot
                    val title = onNodeWithText("巡检").fetchSemanticsNode().boundsInRoot
                    val pencil = onNodeWithTag("script-rename").fetchSemanticsNode().boundsInRoot
                    val chip = onNodeWithTag("connection-status").fetchSemanticsNode().boundsInRoot
                    assertTrue(pencil.left - title.right <= 8f, "rename icon must trail the name")
                    assertTrue(root.right - chip.right <= 40f, "connection chip must stay at the right edge")
                }

                // The console lives on the page itself, so a run never replaces the editor with a log page,
                // and the running action turns into stop in place.
                model.state.value = ConsoleState(connected = true, scriptRunPhase = ScriptRunPhase.RUNNING,
                    scriptTitle = "巡检运行中", scriptMessages = listOf("started"))
                onNodeWithTag("script-console").assertIsDisplayed()
                onNodeWithTag("script-editor").assertIsDisplayed()
                onNodeWithText("started").assertIsDisplayed()
                onNodeWithTag("script-run-stop").assertIsDisplayed()

                onNodeWithTag("script-audio-panel").assertDoesNotExist()
                onNodeWithTag("script-audio-manage").performClick()
                onNodeWithText("voice").assertIsDisplayed()
                runOnIdle {
                    val name = onNodeWithText("voice").fetchSemanticsNode().boundsInRoot
                    val pencil = onNodeWithTag("script-audio-rename-0").fetchSemanticsNode().boundsInRoot
                    assertTrue(pencil.left >= name.right - 1f, "rename must sit with the name, not in a button row")
                }
                onNodeWithTag("script-audio-insert-0").assertIsDisplayed().performClick()
                runOnIdle {
                    assertContains(document.value.source, "media_ctrl.play_sound(rm_define.media_custom_audio_0)")
                }
                onNodeWithTag("script-audio-import").performClick()
                runOnIdle { assertTrue(importRequested) }
            } finally {
                model.close()
                Localization.initialize("zh", null)
            }
        }

    @Test fun phoneLayoutKeepsTheConsoleUnderTheEditorAndMonitorsInADialog() =
        runDesktopComposeUiTest(width = 393, height = 740) {
            Localization.initialize("zh", null)
            val model = testConsoleModel()
            val document = mutableStateOf(EditorDocument(source = "def start():\n    pass\n"))
            try {
                setContent {
                    WorkbenchTheme {
                        ScriptPage(
                            model = model,
                            document = document,
                            compact = true,
                            onImport = {},
                            onExport = {},
                            fileError = null,
                            onFileError = {},
                        )
                    }
                }
                onNodeWithTag("script-console").assertIsDisplayed()
                onNodeWithTag("script-editor").assertIsDisplayed()
                onNodeWithTag("script-video").assertDoesNotExist()
                onNodeWithTag("script-monitor").assertIsDisplayed().performClick()
                onNodeWithTag("script-video").assertIsDisplayed()
                onNodeWithTag("script-status-strip").assertIsDisplayed()
                onNodeWithText("关闭").performClick()
                onNodeWithTag("script-video").assertDoesNotExist()

                // A draft has no program to attach audio to, and the dialog says so.
                onNodeWithTag("script-audio-manage").performClick()
                onNodeWithTag("script-audio-panel").assertIsDisplayed()
                onNodeWithText("请先保存脚本：自定义音频属于已保存的程序，并随其 DSP 一起上传。").assertIsDisplayed()
                onNodeWithTag("script-audio-import").assertIsNotEnabled()
            } finally {
                model.close()
                Localization.initialize("zh", null)
            }
        }
}
