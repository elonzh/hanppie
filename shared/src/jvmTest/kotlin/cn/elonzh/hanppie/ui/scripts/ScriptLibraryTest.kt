package cn.elonzh.hanppie.ui.scripts

import cn.elonzh.hanppie.agent.tools.ListLabScriptsTool
import cn.elonzh.hanppie.agent.tools.NoToolArgs
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.i18n.Localization
import cn.elonzh.hanppie.ui.i18n.tr
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
import kotlinx.coroutines.async
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

    @Test fun executionLoadsCurrentAudioByIdFromTheSavedDirectory(): Unit = runBlocking {
        val directory = Files.createTempDirectory("hanppie-execution-audio-")
        try {
            val repository = DirectoryScriptRepository(PlatformFile(directory.resolve("scripts").toString()),
                PlatformFile(directory.resolve("presets").toString()), enablePresetSync = false)
            val library = ScriptLibrary(repository)
            library.load()
            val script = library.create("有音频的脚本", "def start(): pass")
            val packets = byteArrayOf(4, 0, 1, 2, 3, 4)
            repository.replaceAllAudio(script.id, listOf(LabAudioClip(2, "提示音", 20, packets)))
            library.rename(script.id, "重命名的脚本")
            val snapshot = library.executionSnapshot(script.id)
            assertEquals("重命名的脚本", snapshot.name)
            assertEquals(script.source, snapshot.source)
            assertEquals(2, snapshot.audioClips.single().id)
            kotlin.test.assertContentEquals(packets, snapshot.audioClips.single().packets)
            library.delete(script.id)
            assertFailsWith<IllegalStateException> { library.executionSnapshot(script.id) }
        } finally { directory.toFile().deleteRecursively() }
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

    @Test fun idOperationsSurviveRenameAndNameReuse() = runBlocking {
        val store = MemoryScriptRepository()
        val library = ScriptLibrary(store)
        library.load()

        val created = library.save(null, "巡检", "def start():\n    pass\n")
        assertEquals(listOf(created), library.savedScripts())
        assertEquals(created, library.read(created.id))

        val replaced = library.save(created.id, "夜间巡检", "def start():\n    log_ctrl.print_msg('night')\n")
        assertEquals(created.id, replaced.id)
        assertEquals("夜间巡检", replaced.name)
        assertEquals(listOf(replaced), library.savedScripts())
        assertEquals(replaced, library.read(created.id))
        val reusedName = library.save(null, "巡检", "def start(): pass")
        assertFailsWith<IllegalStateException> { library.save("missing-id", "巡检", "def start(): pass") }
        assertFailsWith<IllegalStateException> { library.save("", "新脚本", "def start(): pass") }
        kotlin.test.assertNull(library.read("夜间巡检"))

        assertEquals("夜间巡检", library.delete(created.id).name)
        assertEquals(listOf(reusedName), library.savedScripts())
        assertFailsWith<IllegalStateException> { library.delete(created.id) }
        assertEquals(reusedName, library.read(reusedName.id))
    }

    @Test fun deleteApprovalShowsANameButKeepsItsTargetAfterRename() = runBlocking {
        val library = ScriptLibrary(MemoryScriptRepository())
        library.load()
        val original = library.create("原名称", "def start(): pass")
        val tool = cn.elonzh.hanppie.agent.tools.DeleteLabScriptTool({ requireNotNull(library.read(it)).name }) { id ->
            val removed = library.delete(id)
            cn.elonzh.hanppie.agent.tools.DeleteLabScriptTool.Result(removed.id, removed.name,
                cn.elonzh.hanppie.agent.tools.DeleteLabScriptTool.Status.DELETED)
        }
        val args = cn.elonzh.hanppie.agent.tools.DeleteLabScriptTool.Args(original.id)
        val preparation = tool.prepareApproval(args)
        assertEquals("原名称", preparation.preview)
        assertEquals("原名称", tool.rejectedResult(args, preparation).name)
        assertEquals(original, library.read(original.id))
        library.rename(original.id, "新名称")
        val replacement = library.create("原名称", "def start(): pass")
        val deleted = tool.execute(args)
        assertEquals(original.id, deleted.id)
        assertEquals("新名称", deleted.name)
        assertEquals(listOf(replacement), library.savedScripts())
    }

    @Test fun canceledLoadReleasesAgentScriptToolsWithoutWaitingForTimeout(): Unit = runBlocking {
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
        assertEquals(Res.string.script_library_load_interrupted, library.state.value.error?.resource)
        val failure = assertFailsWith<IllegalStateException> {
            withTimeout(1_000) {
                ListLabScriptsTool {
                    ListLabScriptsTool.Result(library.savedScripts().map { script ->
                        ListLabScriptsTool.Script(script.id, script.name, script.source.length, script.updatedAtEpochMillis)
                    })
                }.execute(NoToolArgs)
            }
        }
        assertContains(failure.message.orEmpty(), checkNotNull(library.state.value.error).resolve())
        assertFailsWith<IllegalStateException> { library.read("missing") }
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
            assertEquals(12, presets.size)
            assertEquals(12, presets.map { it.id }.distinct().size)
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
                    "-X", "utf8",
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

    @Test fun inputErrorsRetainTheirResourcesAcrossLanguagesWithoutChangingSavedData() = runBlocking {
        val store = MemoryScriptRepository()
        val library = ScriptLibrary(store)
        library.load(); val original = library.create("kept", "pass")
        assertFailsWith<IllegalArgumentException> { library.create(" kept ", "other") }
        val duplicate = checkNotNull(library.state.value.error)
        assertEquals(Res.string.script_library_name_in_use, duplicate.resource)
        assertEquals(listOf("kept"), duplicate.arguments)
        try {
            for (language in listOf("zh", "zh-Hant", "en", "ja", "de", "es", "fr", "ko", "ru")) {
                Localization.initialize(language,language)
                assertEquals(tr(Res.string.script_library_name_in_use,"kept"),duplicate.resolve())
                assertEquals(duplicate,library.state.value.error)
            }
        } finally { Localization.initialize("zh",null) }
        assertFailsWith<IllegalArgumentException> { library.create("bad\nname", "pass") }
        assertEquals(Res.string.script_name_unavailable,library.state.value.error?.resource)
        assertFailsWith<IllegalArgumentException> { library.update(original.id,"x".repeat(MAX_SCRIPT_LENGTH+1)) }
        assertEquals(Res.string.script_library_source_too_large,library.state.value.error?.resource)
        assertFailsWith<IllegalStateException> { library.rename("missing","new") }
        assertEquals(Res.string.script_library_missing,library.state.value.error?.resource)
        assertEquals(listOf(original),store.all())
        library.rename(original.id,"updated");assertEquals(null,library.state.value.error)
    }

    @Test fun failedLoadRejectsEveryMutationUntilAnExplicitReloadSucceeds() = runBlocking {
        val store = MemoryScriptRepository();var fail = true
        val repository = object : ScriptRepository by store {
            override suspend fun all(): List<StoredScript> = if (fail) error("synthetic EACCES") else store.all()
        }
        val library=ScriptLibrary(repository);library.load()
        val feedback=checkNotNull(library.state.value.error)
        assertEquals(Res.string.script_library_load_failed_value,feedback.resource)
        assertEquals(listOf("synthetic EACCES"),feedback.arguments)
        assertFailsWith<IllegalStateException> { library.create("new","pass") }
        assertFailsWith<IllegalStateException> { library.update("id","pass") }
        assertFailsWith<IllegalStateException> { library.rename("id","new") }
        assertFailsWith<IllegalStateException> { library.delete("id") }
        assertFailsWith<IllegalStateException> { library.save(null,"new","pass") }
        assertFailsWith<IllegalStateException> { library.executionSnapshot("id") }
        assertEquals(feedback,library.state.value.error);assertTrue(store.all().isEmpty())
        fail=false;library.load();assertTrue(library.state.value.initialized)
        assertEquals(null,library.state.value.error);library.create("recovered","pass")
        assertEquals("recovered",store.all().single().name)
    }

    @Test fun aQueuedReloadFailureCannotBeBypassedByAnAlreadyWaitingWriter() = runBlocking {
        val store=MemoryScriptRepository();var fail=false
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val repository=object : ScriptRepository by store {
            override suspend fun all(): List<StoredScript> = if (fail) error("synthetic reload failure") else store.all()
            override suspend fun update(script: StoredScript) {
                entered.complete(Unit);release.await();store.update(script)
            }
        }
        val library=ScriptLibrary(repository);library.load();val original=library.create("original","pass")
        val rename=async(start=kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { library.rename(original.id,"renamed") }
        entered.await();fail=true
        val reload=launch(start=kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { library.load() }
        val writer=async(start=kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { runCatching { library.create("late","pass") } }
        release.complete(Unit);rename.await();reload.join()
        assertTrue(writer.await().exceptionOrNull() is IllegalStateException)
        assertEquals(listOf("renamed"),store.all().map { it.name })
        assertEquals(Res.string.script_library_load_failed_value,library.state.value.error?.resource)
    }

    @Test fun suggestedExportNamesCannotIntroducePaths() {
        assertEquals("巡检_脚本.py", suggestedScriptFileName("巡检/脚本"))
        assertEquals("demo.py", suggestedScriptFileName("demo.py"))
        assertEquals("script.py", suggestedScriptFileName("..."))
    }
}
