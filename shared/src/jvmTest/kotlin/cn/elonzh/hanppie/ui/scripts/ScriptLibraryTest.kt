package cn.elonzh.hanppie.ui.scripts

import cn.elonzh.hanppie.agent.tools.ListLabScriptsTool
import cn.elonzh.hanppie.agent.tools.NoToolArgs
import cn.elonzh.hanppie.robot.lab.LabAudioClip
import cn.elonzh.hanppie.robot.lab.LabProgram
import cn.elonzh.hanppie.ui.app.MemoryScriptRepository
import io.github.vinceglb.filekit.PlatformFile
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class ScriptLibraryTest {
    @Test fun createUpdateRenameDeleteAndReload() = runBlocking {
        val directory = Files.createTempDirectory("hanppie-script-library-")
        try {
            val userDir = PlatformFile(directory.resolve("scripts").toString())
            val presetsDir = PlatformFile(directory.resolve("presets").toString())
            val repository = DirectoryScriptRepository(userDir, presetsDir, enablePresetSync = false)
            val library = ScriptLibrary(repository)
            library.load()
            val created = library.create("  巡检脚本  ", "def start():\n    pass\n")
            assertEquals("巡检脚本", created.name)
            val updated = library.update(created.id, "def start():\n    print('updated')\n")
            assertEquals("def start():\n    print('updated')\n", updated.source)
            val renamed = library.rename(created.id, "巡检脚本 2")
            assertEquals("巡检脚本 2", renamed.name)

            val restoredRepository = DirectoryScriptRepository(userDir, presetsDir, enablePresetSync = false)
            val restored = ScriptLibrary(restoredRepository)
            restored.load()
            assertEquals(listOf(renamed), restored.state.value.scripts)
            restored.delete(created.id)
            assertTrue(restored.state.value.scripts.isEmpty())
            assertTrue(restoredRepository.all().isEmpty())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test fun duplicateNamesAreRejectedWithoutChangingSavedState() = runBlocking {
        val store = MemoryScriptRepository()
        val library = ScriptLibrary(store)
        library.load()
        library.create("Demo", "pass")
        assertFailsWith<IllegalArgumentException> { library.create(" demo ", "other") }
        assertEquals(listOf("Demo"), library.state.value.scripts.map { it.name })
        assertEquals("Demo 2", library.uniqueName("Demo"))
    }

    @Test fun agentFacingNameOperationsCreateReplaceRenameAndDeleteAtomically() = runBlocking {
        val store = MemoryScriptRepository()
        val library = ScriptLibrary(store)
        library.load()

        val created = library.save(null, "巡检", "def start():\n    pass\n")
        assertEquals(listOf(created), library.savedScripts())
        assertEquals(created, library.read("巡检"))

        val replaced = library.save("巡检", "夜间巡检", "def start():\n    log_ctrl.print_msg('night')\n")
        assertEquals(created.id, replaced.id)
        assertEquals("夜间巡检", replaced.name)
        assertEquals(listOf(replaced), library.savedScripts())
        assertFailsWith<IllegalStateException> { library.read("巡检") }

        assertEquals("夜间巡检", library.deleteByName("夜间巡检").name)
        assertTrue(library.savedScripts().isEmpty())
    }

    @Test fun canceledLoadReleasesAgentScriptToolsWithoutWaitingForTimeout() = runBlocking {
        val loadStarted = CompletableDeferred<Unit>()
        val library = ScriptLibrary(object : ScriptRepository {
            override suspend fun all(): List<StoredScript> {
                loadStarted.complete(Unit)
                awaitCancellation()
            }

            override suspend fun presets(): List<StoredScript> = emptyList()
            override suspend fun insert(script: StoredScript) = Unit
            override suspend fun update(script: StoredScript) = Unit
            override suspend fun delete(script: StoredScript) = Unit
            override suspend fun audio(scriptId: String): List<LabAudioClip> = emptyList()
            override suspend fun insertAudio(scriptId: String, audio: LabAudioClip) = Unit
            override suspend fun updateAudio(scriptId: String, audio: LabAudioClip) = Unit
            override suspend fun deleteAudio(scriptId: String, nativeId: Int): Boolean = false
            override suspend fun replaceAllAudio(scriptId: String, clips: List<LabAudioClip>) = Unit
        })
        val load = launch { library.load() }
        loadStarted.await()
        load.cancelAndJoin()

        assertFalse(library.state.value.loading)
        assertEquals("Script library load was interrupted", library.state.value.error)
        val failure = assertFailsWith<IllegalStateException> {
            withTimeout(1_000) {
                ListLabScriptsTool {
                    ListLabScriptsTool.Result(library.savedScripts().map { script ->
                        ListLabScriptsTool.Script(script.name, script.source.length, script.updatedAtEpochMillis)
                    })
                }.execute(NoToolArgs)
            }
        }
        assertContains(failure.message.orEmpty(), "Script library load was interrupted")
    }

    @Test fun presetsHaveStableIdsAndPython36LabEntrypoints() = runBlocking {
        val tempDir = Files.createTempDirectory("hanppie-presets-test-")
        try {
            val repo = DirectoryScriptRepository(
                PlatformFile(tempDir.resolve("scripts").toString()),
                PlatformFile(tempDir.resolve("presets").toString()),
                enablePresetSync = true,
            )
            val presets = repo.presets()
            assertEquals(13, presets.size)
            assertEquals(13, presets.map { it.id }.distinct().size)
            presets.forEach {
                assertTrue(Regex("(?m)^def start\\(\\):$").containsMatchIn(it.source), it.id)
                assertTrue(it.source.endsWith("\n"), it.id)
                assertTrue(it.source.length <= MAX_SCRIPT_LENGTH, it.id)
                assertTrue("REPEAT_COUNT" in it.source, it.id)
                assertTrue("for " in it.source, it.id)
            }
            presets.filter { "gimbal_ctrl.stop()" in it.source }.forEach {
                assertTrue("robot_ctrl.set_mode(rm_define.robot_mode_free)" in it.source, it.id)
                assertTrue(it.source.indexOf("finally:") < it.source.indexOf("gimbal_ctrl.stop()"), it.id)
            }
            presets.filter { "chassis_ctrl.stop()" in it.source }.forEach {
                assertTrue("robot_ctrl.set_mode(rm_define.robot_mode_free)" in it.source, it.id)
                assertTrue(it.source.indexOf("finally:") < it.source.indexOf("chassis_ctrl.stop()"), it.id)
            }
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test fun presetsParseAsPython36() = runBlocking {
        val tempDir = Files.createTempDirectory("hanppie-python-presets-test-")
        try {
            val repo = DirectoryScriptRepository(
                PlatformFile(tempDir.resolve("scripts").toString()),
                PlatformFile(tempDir.resolve("presets").toString()),
                enablePresetSync = true,
            )
            val presets = repo.presets()
            val python = System.getenv("PYTHON")?.takeIf { it.isNotBlank() }
                ?: if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "python" else "python3"
            presets.forEach { preset ->
                val process = ProcessBuilder(
                    python,
                    "-c",
                    "import ast,sys; ast.parse(sys.stdin.read(), feature_version=(3,6))",
                ).start()
                process.outputStream.bufferedWriter(Charsets.UTF_8).use {
                    it.write(preset.source)
                }
                val stderr = process.errorStream.bufferedReader(Charsets.UTF_8).readText()
                assertEquals(0, process.waitFor(), "${preset.id}: $stderr")
            }
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test fun scriptsAreSortedByCreationTimeDescending() = runBlocking {
        var time = 1000L
        val clock = object : kotlin.time.Clock {
            override fun now(): kotlin.time.Instant = kotlin.time.Instant.fromEpochMilliseconds(time++)
        }
        val store = MemoryScriptRepository()
        val library = ScriptLibrary(store, clock = clock)
        library.load()
        val s1 = library.create("Script 1", "pass")
        val s2 = library.create("Script 2", "pass")
        assertEquals(listOf(s2.id, s1.id), library.state.value.scripts.map { it.id })

        library.update(s1.id, "pass # updated")
        assertEquals(listOf(s2.id, s1.id), library.state.value.scripts.map { it.id })
    }

    @Test fun fridayDiscoCarriesOriginalAudioClipWithinUploadBudget() = runBlocking {
        val tempDir = Files.createTempDirectory("hanppie-friday-test-")
        try {
            val repo = DirectoryScriptRepository(
                PlatformFile(tempDir.resolve("scripts").toString()),
                PlatformFile(tempDir.resolve("presets").toString()),
                enablePresetSync = true,
            )
            val friday = repo.presets().first { it.id == "friday-disco" }
            assertEquals(1, friday.audioClips.size)
            assertEquals(0, friday.audioClips[0].id)
            assertEquals("friday_night", friday.audioClips[0].name)
            assertTrue(friday.audioClips[0].durationMillis >= 88_000L)
            assertTrue(LabAudioClip.totalEncodedBytes(friday.audioClips) + friday.source.length <= LabProgram.MAX_DSP_BYTES)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test fun suggestedExportNamesCannotIntroducePaths() {
        assertEquals("巡检_脚本.py", suggestedScriptFileName("巡检/脚本"))
        assertEquals("demo.py", suggestedScriptFileName("demo.py"))
        assertEquals("script.py", suggestedScriptFileName("..."))
    }
}
