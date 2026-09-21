package cn.elonzh.hanppie.agent.tools

import ai.koog.agents.core.tools.ToolRegistry
import cn.elonzh.hanppie.robot.lab.ScriptRunPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class HanppieToolsTest {
    @Test fun executionRequiresLibraryIdAndApprovedSnapshot(): Unit = runBlocking {
        val tool = ExecuteLabPythonTool({ error("No load expected") }) { error("No execution allowed") }
        assertEquals(listOf("scriptId"), tool.descriptor.requiredParameters.map { it.name })
        assertEquals(emptyList(), tool.descriptor.optionalParameters)
        kotlin.test.assertFailsWith<IllegalStateException> {
            tool.execute(ExecuteLabPythonTool.Args("script-1"), ai.koog.agents.core.tools.ToolCallMetadata.EMPTY)
        }
    }

    @Test fun scriptSchemasUseIdsWhileSaveKeepsAnEditableName() {
        val read = ReadLabScriptTool { error("Not executed") }.descriptor
        val delete = DeleteLabScriptTool({ error("Not loaded") }) { error("Not executed") }.descriptor
        val save = SaveLabScriptTool { _, _, _ -> error("Not executed") }.descriptor
        assertEquals(listOf("scriptId"), read.requiredParameters.map { it.name })
        assertEquals(listOf("scriptId"), delete.requiredParameters.map { it.name })
        assertEquals(listOf("name", "source"), save.requiredParameters.map { it.name })
        assertEquals(listOf("scriptId"), save.optionalParameters.map { it.name })
    }

    @Test fun skillNameIsRequiredAndPathIsOptionalInTheModelSchema() {
        val descriptor = ReadSkillTool { _, _ -> error("Not executed") }.descriptor
        assertEquals(listOf("name"), descriptor.requiredParameters.map { it.name })
        assertEquals(listOf("path"), descriptor.optionalParameters.map { it.name })
    }

    @Test
    fun registryContainsNamedClassBasedTools(): Unit = runBlocking {
        val statusResult = RobotStatusTool.Result(
            connected = true,
            batteryPercent = 82,
            script = RobotStatusTool.ScriptRun(phase = ScriptRunPhase.IDLE),
        )
        val referenceResult = ReadSkillTool.Result("skill content")
        val scriptsResult = ListLabScriptsTool.Result(
            listOf(ListLabScriptsTool.Script("script-1", "巡检", 17, 1234)),
        )
        val readResult = ReadLabScriptTool.Result("script-1", "巡检", "def start(): pass", 1000, 1234, ReadLabScriptTool.Status.FOUND)
        val saveResult = SaveLabScriptTool.Result("script-1", "新脚本", created = false, sourceLength = 17, updatedAtEpochMillis = 2345)
        val deleteResult = DeleteLabScriptTool.Result("script-1", "巡检", DeleteLabScriptTool.Status.DELETED)
        val executeResult = ExecuteLabPythonTool.Result(ExecuteLabPythonTool.Status.START_COMMAND_SENT, "run-1")
        val stopResult = StopLabTool.Result(StopLabTool.Status.STOP_COMMAND_SENT)
        val registry = ToolRegistry {
            tool(RobotStatusTool { statusResult })
            tool(ReadSkillTool { _, _ -> referenceResult })
            tool(ListLabScriptsTool { scriptsResult })
            tool(ReadLabScriptTool { readResult })
            tool(SaveLabScriptTool { _, _, _ -> saveResult })
            tool(DeleteLabScriptTool({ "旧巡检" }) { deleteResult })
            tool(ExecuteLabPythonTool({ cn.elonzh.hanppie.ui.scripts.StoredScript(it, "巡检", "def start(): pass", 1, 1) }) { executeResult })
            tool(StopLabTool { stopResult })
        }

        assertEquals(
            setOf(
                RobotStatusTool.NAME,
                ReadSkillTool.NAME,
                ListLabScriptsTool.NAME,
                ReadLabScriptTool.NAME,
                SaveLabScriptTool.NAME,
                DeleteLabScriptTool.NAME,
                ExecuteLabPythonTool.NAME,
                StopLabTool.NAME,
            ),
            registry.tools.map { tool -> tool.name }.toSet(),
        )
        assertEquals(statusResult, registry.getTool<RobotStatusTool>().execute(NoToolArgs))
        assertEquals(
            referenceResult,
            registry.getTool<ReadSkillTool>().execute(ReadSkillTool.Args("lab-python")),
        )
        assertEquals(scriptsResult, registry.getTool<ListLabScriptsTool>().execute(NoToolArgs))
        assertEquals(
            readResult,
            registry.getTool<ReadLabScriptTool>().execute(ReadLabScriptTool.Args("script-1")),
        )
        assertEquals(
            saveResult,
            registry.getTool<SaveLabScriptTool>().execute(
                SaveLabScriptTool.Args("script-1", "新脚本", "def start(): pass"),
            ),
        )
        assertEquals(
            deleteResult,
            registry.getTool<DeleteLabScriptTool>().execute(DeleteLabScriptTool.Args("script-1")),
        )
        assertEquals(
            executeResult,
            registry.getTool<ExecuteLabPythonTool>().execute(
                ExecuteLabPythonTool.Args("script-1"),
                registry.getTool<ExecuteLabPythonTool>().prepareApproval(ExecuteLabPythonTool.Args("script-1")).metadata,
            ),
        )
        assertEquals(stopResult, registry.getTool<StopLabTool>().execute(NoToolArgs))
    }
}
