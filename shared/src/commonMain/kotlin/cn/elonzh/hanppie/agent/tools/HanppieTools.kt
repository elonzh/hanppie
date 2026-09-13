package cn.elonzh.hanppie.agent.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.serialization.Serializable

/** Product-level tool boundary. Robot side effects remain behind explicit runtime policy. */
internal interface HanppieToolEnvironment {
    suspend fun robotStatus(): String
    suspend fun executeLabPython(source: String): String
    suspend fun stopLab(): String
}

@Serializable
internal data object NoToolArgs

internal class RobotStatusTool(
    private val environment: HanppieToolEnvironment,
) : SimpleTool<NoToolArgs>(
    argsType = typeToken<NoToolArgs>(),
    name = NAME,
    description = "读取当前连接、遥测及脚本消息，不连接新设备",
) {
    override suspend fun execute(args: NoToolArgs): String = environment.robotStatus()

    companion object {
        const val NAME = "robot_status"
    }
}

internal class ExecuteLabPythonTool(
    private val environment: HanppieToolEnvironment,
) : SimpleTool<ExecuteLabPythonTool.Args>(
    argsType = typeToken<Args>(),
    name = NAME,
    description = "提交完整 RoboMaster Lab Python 3.6 脚本。用户确认后上传并启动，不等于动作完成。",
) {
    @Serializable
    data class Args(
        @property:LLMDescription("完整脚本，包含 def start()")
        val source: String,
    )

    override suspend fun execute(args: Args): String = environment.executeLabPython(args.source)

    companion object {
        const val NAME = "execute_lab_python"
    }
}

internal class StopLabTool(
    private val environment: HanppieToolEnvironment,
) : SimpleTool<NoToolArgs>(
    argsType = typeToken<NoToolArgs>(),
    name = NAME,
    description = "向当前机器人发送停止脚本命令，不代表停止已被实机确认",
) {
    override suspend fun execute(args: NoToolArgs): String = environment.stopLab()

    companion object {
        const val NAME = "stop_lab"
    }
}

internal fun hanppieToolRegistry(environment: HanppieToolEnvironment) = ToolRegistry {
    tool(RobotStatusTool(environment))
    tool(ExecuteLabPythonTool(environment))
    tool(StopLabTool(environment))
}
