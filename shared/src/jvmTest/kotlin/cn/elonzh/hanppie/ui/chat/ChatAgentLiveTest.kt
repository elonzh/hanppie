package cn.elonzh.hanppie.ui.chat

import ai.koog.agents.core.tools.ToolRegistry
import cn.elonzh.hanppie.agent.runtime.TestSessionHistory
import cn.elonzh.hanppie.agent.tools.*
import cn.elonzh.hanppie.robot.lab.ScriptRunPhase
import cn.elonzh.hanppie.ui.settings.ModelSettings
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test

class ChatAgentLiveTest {
    @Test
    fun liveCompatibleConversationWithoutRobot() = runBlocking {
        val key = System.getenv("HANPPIE_LLM_API_KEY")
        assumeTrue("Opt-in cloud smoke test", System.getenv("HANPPIE_LLM_LIVE_TEST") == "1" && !key.isNullOrBlank())
        val defaults = ModelSettings()
        val config = defaults.copy(
            apiKey = key!!,
            model = System.getenv("HANPPIE_LLM_MODEL") ?: defaults.model,
        )
        ChatAgent(
            toolRegistry = ToolRegistry {
                tool(RobotStatusTool {
                    RobotStatusTool.Result(
                        connected = false,
                        script = RobotStatusTool.ScriptRun(phase = ScriptRunPhase.IDLE),
                    )
                })
                tool(ReadSkillTool { _, _ ->
                    ReadSkillTool.Result("test")
                })
                tool(ListLabScriptsTool { ListLabScriptsTool.Result(emptyList()) })
                tool(ReadLabScriptTool { ReadLabScriptTool.Result("script-1", it, "script:$it", 1, 1, ReadLabScriptTool.Status.FOUND) })
                tool(SaveLabScriptTool { original, name, source ->
                    SaveLabScriptTool.Result("script-1", name, original == null, source.length, 1)
                })
                tool(DeleteLabScriptTool({ "旧巡检" }) { DeleteLabScriptTool.Result(it, "旧巡检", DeleteLabScriptTool.Status.DELETED) })
                tool(ExecuteLabPythonTool({ error("No script execution allowed") }) { error("No robot allowed") })
                tool(StopLabTool { error("No robot allowed") })
            },
            createHttpClient = ::createAgentHttpClient,
            sessions = TestSessionHistory(),
        ).use { agent ->
            withTimeout(5000) { agent.state.first { it.ready } }
            agent.send("记住口令是蓝色。只回复已记住。", config)
            withTimeout(125000) { agent.state.first { !it.running } }
            assertNull(agent.state.value.error)
            println("live firstTokenMs=${agent.state.value.firstTokenMs} elapsedMs=${agent.state.value.elapsedMs}")
            agent.send("口令是什么？并调用工具读取当前连接状态。", config)
            withTimeout(125000) { agent.state.first { !it.running } }
            assertNull(agent.state.value.error)
            assertTrue(agent.state.value.lines.any {
                it.role == ChatRole.TOOL && it.toolResult?.output?.contains("\"connected\":false") == true
            })
            assertTrue(agent.state.value.lines.last().text.contains("蓝色"))
            println("live tool round firstTokenMs=${agent.state.value.firstTokenMs} elapsedMs=${agent.state.value.elapsedMs}")
        }
    }
}
