package cn.elonzh.hanppie.agent.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class HanppieToolsTest {
    @Test
    fun registryContainsNamedClassBasedTools(): Unit = runBlocking {
        val environment = object : HanppieToolEnvironment {
            override suspend fun robotStatus(): String = "ready"

            override suspend fun executeLabPython(source: String): String = "executed:$source"

            override suspend fun stopLab(): String = "stopped"
        }
        val registry = hanppieToolRegistry(environment)

        assertEquals(
            setOf(RobotStatusTool.NAME, ExecuteLabPythonTool.NAME, StopLabTool.NAME),
            registry.tools.map { tool -> tool.name }.toSet(),
        )
        assertEquals("ready", registry.getTool<RobotStatusTool>().execute(NoToolArgs))
        assertEquals(
            "executed:def start(): pass",
            registry.getTool<ExecuteLabPythonTool>().execute(
                ExecuteLabPythonTool.Args("def start(): pass"),
            ),
        )
        assertEquals("stopped", registry.getTool<StopLabTool>().execute(NoToolArgs))
    }
}
