package cn.elonzh.hanppie.agent.tools

import cn.elonzh.hanppie.robot.lab.ScriptRunPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class HanppieToolsTest {
    @Test
    fun registryContainsNamedClassBasedTools(): Unit = runBlocking {
        val statusResult = RobotStatusTool.Result(
            connected = true,
            batteryPercent = 82,
            script = RobotStatusTool.ScriptRun(phase = ScriptRunPhase.IDLE),
        )
        val referenceResult = LabApiReferenceTool.Result(
            inCatalog = true,
            availableCategories = listOf("chassis", "gimbal"),
            sections = listOf(LabApiReferenceTool.Section("chassis", listOf("move"))),
            guidance = "verified",
        )
        val scriptsResult = ListLabScriptsTool.Result(
            listOf(ListLabScriptsTool.Script("巡检", 17, 1234)),
        )
        val readResult = ReadLabScriptTool.Result("巡检", "def start(): pass", 1000, 1234)
        val saveResult = SaveLabScriptTool.Result("新脚本", created = false, sourceLength = 17, updatedAtEpochMillis = 2345)
        val deleteResult = DeleteLabScriptTool.Result("巡检", DeleteLabScriptTool.Status.DELETED)
        val executeResult = ExecuteLabPythonTool.Result(ExecuteLabPythonTool.Status.START_COMMAND_SENT, "run-1")
        val stopResult = StopLabTool.Result(StopLabTool.Status.STOP_COMMAND_SENT, robotStopConfirmed = false)
        val environment = object : HanppieToolEnvironment {
            override suspend fun robotStatus() = statusResult

            override suspend fun labApiReference(query: String) = referenceResult

            override suspend fun listLabScripts() = scriptsResult

            override suspend fun readLabScript(name: String) = readResult

            override suspend fun saveLabScript(originalName: String?, name: String, source: String) = saveResult

            override suspend fun deleteLabScript(name: String) = deleteResult

            override suspend fun executeLabPython(source: String) = executeResult

            override suspend fun stopLab() = stopResult
        }
        val registry = hanppieToolRegistry(environment)

        assertEquals(
            setOf(
                RobotStatusTool.NAME,
                LabApiReferenceTool.NAME,
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
            registry.getTool<LabApiReferenceTool>().execute(LabApiReferenceTool.Args("chassis gimbal")),
        )
        assertEquals(scriptsResult, registry.getTool<ListLabScriptsTool>().execute(NoToolArgs))
        assertEquals(
            readResult,
            registry.getTool<ReadLabScriptTool>().execute(ReadLabScriptTool.Args("巡检")),
        )
        assertEquals(
            saveResult,
            registry.getTool<SaveLabScriptTool>().execute(
                SaveLabScriptTool.Args("旧脚本", "新脚本", "def start(): pass"),
            ),
        )
        assertEquals(
            deleteResult,
            registry.getTool<DeleteLabScriptTool>().execute(DeleteLabScriptTool.Args("巡检")),
        )
        assertEquals(
            executeResult,
            registry.getTool<ExecuteLabPythonTool>().execute(
                ExecuteLabPythonTool.Args("def start(): pass"),
            ),
        )
        assertEquals(stopResult, registry.getTool<StopLabTool>().execute(NoToolArgs))
    }
}
