package cn.elonzh.hanppie.ui

import cn.elonzh.hanppie.robot.LabRunProtocol
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class ScriptLibraryTest {
    @Test fun createUpdateRenameDeleteAndReload() = runBlocking {
        val directory = Files.createTempDirectory("hanppie-script-library-")
        try {
            val file = directory.resolve("library-v1.json")
            val library = ScriptLibrary(JsonScriptStore(file))
            library.load()
            val created = library.create("  巡检脚本  ", "def start():\n    pass\n")
            assertEquals("巡检脚本", created.name)
            val updated = library.update(created.id, "def start():\n    print('updated')\n")
            assertEquals("def start():\n    print('updated')\n", updated.source)
            val renamed = library.rename(created.id, "巡检脚本 2")
            assertEquals("巡检脚本 2", renamed.name)

            val restored = ScriptLibrary(JsonScriptStore(file))
            restored.load()
            assertEquals(listOf(renamed), restored.state.value.scripts)
            restored.delete(created.id)
            assertTrue(restored.state.value.scripts.isEmpty())
            assertTrue(JsonScriptStore(file).load().isEmpty())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test fun duplicateNamesAreRejectedWithoutChangingSavedState() = runBlocking {
        val store = MemoryScriptStore()
        val library = ScriptLibrary(store)
        library.load()
        library.create("Demo", "pass")
        assertFailsWith<IllegalArgumentException> { library.create(" demo ", "other") }
        assertEquals(listOf("Demo"), library.state.value.scripts.map { it.name })
        assertEquals("Demo 2", library.uniqueName("Demo"))
    }

    @Test fun presetsHaveStableIdsAndPython36LabEntrypoints() {
        assertEquals(presetScripts.size, presetScripts.map { it.id }.distinct().size)
        assertTrue(presetScripts.size >= 5)
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
