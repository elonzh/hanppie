package cn.elonzh.hanppie.ui.scripts

import androidx.room3.Room
import cn.elonzh.hanppie.agent.tools.ListLabScriptsTool
import cn.elonzh.hanppie.agent.tools.NoToolArgs
import cn.elonzh.hanppie.robot.lab.LabRunProtocol
import cn.elonzh.hanppie.ui.app.MemoryScriptRepository
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
        var database: HanppieDatabase? = null
        try {
            val file = directory.resolve("hanppie.db")
            val firstDatabase = buildHanppieDatabase(Room.databaseBuilder<HanppieDatabase>(file.toString()))
            database = firstDatabase
            val library = ScriptLibrary(RoomScriptRepository(firstDatabase.scriptDao(), firstDatabase.scriptAudioDao()))
            library.load()
            val created = library.create("  巡检脚本  ", "def start():\n    pass\n")
            assertEquals("巡检脚本", created.name)
            val updated = library.update(created.id, "def start():\n    print('updated')\n")
            assertEquals("def start():\n    print('updated')\n", updated.source)
            val renamed = library.rename(created.id, "巡检脚本 2")
            assertEquals("巡检脚本 2", renamed.name)

            firstDatabase.close()
            database = null
            val restoredDatabase = buildHanppieDatabase(Room.databaseBuilder<HanppieDatabase>(file.toString()))
            database = restoredDatabase
            val restored = ScriptLibrary(RoomScriptRepository(restoredDatabase.scriptDao(), restoredDatabase.scriptAudioDao()))
            restored.load()
            assertEquals(listOf(renamed), restored.state.value.scripts)
            restored.delete(created.id)
            assertTrue(restored.state.value.scripts.isEmpty())
            assertTrue(restoredDatabase.scriptDao().getAll().isEmpty())
        } finally {
            database?.close()
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

            override suspend fun insert(script: StoredScript) = Unit
            override suspend fun update(script: StoredScript) = Unit
            override suspend fun delete(script: StoredScript) = Unit
            override suspend fun audio(scriptId: String): List<StoredScriptAudio> = emptyList()
            override suspend fun insertAudio(audio: StoredScriptAudio) = Unit
            override suspend fun updateAudio(audio: StoredScriptAudio) = Unit
            override suspend fun deleteAudio(scriptId: String, nativeId: Int): Boolean = false
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

    @Test fun presetsHaveStableIdsAndPython36LabEntrypoints() {
        assertEquals(presetScripts.size, presetScripts.map { it.id }.distinct().size)
        assertEquals(12, presetScripts.size)
        presetScripts.forEach {
            assertTrue(Regex("(?m)^def start\\(\\):$").containsMatchIn(it.source), it.id)
            assertTrue(it.source.endsWith("\n"), it.id)
            assertTrue(it.source.length <= MAX_SCRIPT_LENGTH, it.id)
            assertTrue("REPEAT_COUNT" in it.source, it.id)
            assertTrue("for " in it.source, it.id)
        }
        presetScripts.filter { it.capability == PresetCapability.GIMBAL_MOTION }.forEach {
            assertTrue("robot_ctrl.set_mode(rm_define.robot_mode_free)" in it.source, it.id)
            assertTrue(it.source.indexOf("finally:") < it.source.indexOf("gimbal_ctrl.stop()"), it.id)
        }
        presetScripts.filter { it.capability == PresetCapability.CHASSIS_MOTION }.forEach {
            assertTrue("robot_ctrl.set_mode(rm_define.robot_mode_free)" in it.source, it.id)
            assertTrue(it.source.indexOf("finally:") < it.source.indexOf("chassis_ctrl.stop()"), it.id)
        }
    }

    @Test fun instrumentedPresetsParseAsPython36() {
        val python = System.getenv("PYTHON")?.takeIf { it.isNotBlank() }
            ?: if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "python" else "python3"
        presetScripts.forEach { preset ->
            val process = ProcessBuilder(
                python,
                "-c",
                "import ast,sys; ast.parse(sys.stdin.read(), feature_version=(3,6))",
            ).start()
            process.outputStream.bufferedWriter(Charsets.UTF_8).use {
                it.write(LabRunProtocol.instrument(preset.source, "0123456789abcdef"))
            }
            val stderr = process.errorStream.bufferedReader(Charsets.UTF_8).readText()
            assertEquals(0, process.waitFor(), "${preset.id}: $stderr")
        }
    }

    @Test fun suggestedExportNamesCannotIntroducePaths() {
        assertEquals("巡检_脚本.py", suggestedScriptFileName("巡检/脚本"))
        assertEquals("demo.py", suggestedScriptFileName("demo.py"))
        assertEquals("script.py", suggestedScriptFileName("..."))
    }
}
