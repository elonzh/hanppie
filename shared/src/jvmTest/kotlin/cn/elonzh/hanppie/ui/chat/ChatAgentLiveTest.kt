package cn.elonzh.hanppie.ui.chat

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
            status = { "测试夹具：未连接机器人，电量未知" },
            execute = { error("No robot allowed") },
            stopRobot = { error("No robot allowed") },
            createHttpClient = ::createAgentHttpClient,
        ).use { agent ->
            agent.send("记住口令是蓝色。只回复已记住。", config)
            withTimeout(125000) { agent.state.first { !it.running } }
            assertNull(agent.state.value.error)
            println("live firstTokenMs=${agent.state.value.firstTokenMs} elapsedMs=${agent.state.value.elapsedMs}")
            agent.send("口令是什么？并调用工具读取当前连接状态。", config)
            withTimeout(125000) { agent.state.first { !it.running } }
            assertNull(agent.state.value.error)
            assertTrue(agent.state.value.lines.any { it.role == ChatRole.TOOL && it.text.contains("测试夹具") })
            assertTrue(agent.state.value.lines.last().text.contains("蓝色"))
            println("live tool round firstTokenMs=${agent.state.value.firstTokenMs} elapsedMs=${agent.state.value.elapsedMs}")
        }
    }
}
