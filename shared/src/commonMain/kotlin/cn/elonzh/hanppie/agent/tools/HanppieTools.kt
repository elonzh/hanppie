package cn.elonzh.hanppie.agent.tools

import ai.koog.agents.core.tools.ToolBase
import ai.koog.agents.core.tools.ToolCallMetadata
import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.validate
import ai.koog.serialization.typeToken
import cn.elonzh.hanppie.agent.runtime.ApprovalRequiredTool
import cn.elonzh.hanppie.agent.runtime.ToolApprovalPreparation
import cn.elonzh.hanppie.ui.scripts.StoredScript
import cn.elonzh.hanppie.robot.lab.LabAudioClip
import cn.elonzh.hanppie.agent.runtime.TerminalTool
import cn.elonzh.hanppie.resources.Res
import cn.elonzh.hanppie.resources.lab_script_must_not_import_modules
import cn.elonzh.hanppie.resources.lab_script_requires_start_entry
import cn.elonzh.hanppie.resources.script_is_empty_or_exceeds_the_32k_character_limit
import cn.elonzh.hanppie.robot.lab.ScriptRunPhase
import cn.elonzh.hanppie.ui.i18n.tr
import kotlinx.serialization.Serializable

@Serializable
internal data object NoToolArgs

internal class RobotStatusTool(
    private val status: suspend () -> Result,
) : Tool<NoToolArgs, RobotStatusTool.Result>(
    argsType = typeToken<NoToolArgs>(),
    resultType = typeToken<Result>(),
    name = NAME,
    description = "读取当前连接、遥测及脚本消息，不连接新设备",
) {
    @Serializable
    data class Result(
        val connected: Boolean,
        val address: String? = null,
        val batteryPercent: Int? = null,
        val signalQualityRaw: Int? = null,
        val script: ScriptRun,
    )

    @Serializable
    data class ScriptRun(
        val id: String? = null,
        val title: String? = null,
        val phase: ScriptRunPhase,
        val startedAtEpochMillis: Long? = null,
        val finishedAtEpochMillis: Long? = null,
        val recentMessages: List<String> = emptyList(),
    )

    override suspend fun execute(args: NoToolArgs): Result = status()

    companion object {
        const val NAME = "robot_status"
    }
}

internal class ExecuteLabPythonTool(
    private val loadScript: suspend (String) -> StoredScript,
    private val execute: suspend (StoredScript) -> Result,
) : ToolBase<ExecuteLabPythonTool.Args, ExecuteLabPythonTool.Result>(
    argsType = typeToken<Args>(),
    resultType = typeToken<Result>(),
    name = NAME,
    description = "按脚本库 ID 执行已保存的 Lab 脚本，自动携带关联音频。先通过列表、读取或保存取得 ID。用户确认源码及音频清单后上传并发送启动命令，必须等待机内回报才能视为已启动。",
), ApprovalRequiredTool<ExecuteLabPythonTool.Args, ExecuteLabPythonTool.Result>, TerminalTool {
    @Serializable
    data class Args(
        @property:LLMDescription("脚本库中的脚本 ID，取自 list_lab_scripts、read_lab_script 或 save_lab_script 返回的 id；不接受名称或源码")
        val scriptId: String,
    )

    @Serializable
    data class Result(
        val status: Status,
        val runId: String? = null,
    )

    @Serializable
    enum class Status { START_COMMAND_SENT, USER_REJECTED }

    override suspend fun execute(args: Args, metadata: ToolCallMetadata): Result {
        val script = checkNotNull(metadata[APPROVED_SCRIPT] as? StoredScript) { "Missing approved script snapshot" }
        check(script.id == args.scriptId) { "Approved script does not match requested ID" }
        return execute(script)
    }

    override suspend fun prepareApproval(args: Args): ToolApprovalPreparation {
        val loaded = loadScript(args.scriptId)
        check(loaded.id == args.scriptId) { "Loaded script does not match requested ID" }
        validateLabSource(loaded.source)
        val snapshot = loaded.copy(audioClips = loaded.audioClips.map {
            LabAudioClip(it.id, it.name, it.durationMillis, it.packets.copyOf())
        })
        val preview = buildString {
            appendLine("# ${snapshot.name}")
            snapshot.audioClips.forEach { clip ->
                appendLine("# audio[${clip.id}]: ${clip.name.replace('\n', ' ')} (${clip.durationMillis} ms)")
            }
            append(snapshot.source)
        }
        return ToolApprovalPreparation(preview, ToolCallMetadata.of(APPROVED_SCRIPT to snapshot))
    }

    override fun rejectedResult(args: Args, preparation: ToolApprovalPreparation) = Result(Status.USER_REJECTED)

    override val displayPreviewAfterApproval: Boolean get() = true

    companion object {
        const val NAME = "execute_lab_python"
        private const val APPROVED_SCRIPT = "hanppie.approvedScript"
    }
}

internal class ReadSkillTool(
    private val read: suspend (String, String) -> Result,
) : Tool<ReadSkillTool.Args, ReadSkillTool.Result>(
    argsType = typeToken<Args>(),
    resultType = typeToken<Result>(),
    name = NAME,
    description = "按名称读取已发现技能的 Markdown 文档，path 默认 SKILL.md。仅返回正文；读取其他文档时使用技能正文中引用的相对路径。不执行文档或访问任意主机文件。",
) {
    @Serializable
    data class Args(
        @property:LLMDescription("available_skills 中的技能名称")
        val name: String,
        @property:LLMDescription("技能目录内的相对文档路径，默认 SKILL.md；其他路径取自技能正文中的文档引用")
        val path: String = "SKILL.md",
    )

    @Serializable
    data class Result(val content: String)

    override suspend fun execute(args: Args): Result = read(args.name, args.path)

    companion object {
        const val NAME = "read_skill"
    }
}

internal class ListLabScriptsTool(
    private val listScripts: suspend () -> Result,
) : Tool<NoToolArgs, ListLabScriptsTool.Result>(
    argsType = typeToken<NoToolArgs>(),
    resultType = typeToken<Result>(),
    name = NAME,
    description = "列出用户保存的 Lab 脚本 ID、名称、更新时间和源码长度，不读取预置脚本内容",
) {
    @Serializable
    data class Result(val scripts: List<Script>)

    @Serializable
    data class Script(
        val id: String,
        val name: String,
        val sourceLength: Int,
        val updatedAtEpochMillis: Long,
    )

    override suspend fun execute(args: NoToolArgs): Result = listScripts()

    companion object {
        const val NAME = "list_lab_scripts"
    }
}

internal class ReadLabScriptTool(
    private val readScript: suspend (String) -> Result,
) : Tool<ReadLabScriptTool.Args, ReadLabScriptTool.Result>(
    argsType = typeToken<Args>(),
    resultType = typeToken<Result>(),
    name = NAME,
    description = "按脚本 ID 读取一个用户保存的 Lab 脚本；修改已有脚本前先读取，避免覆盖未知内容。不存在时返回 NOT_FOUND，可重新列出脚本选择 ID。",
) {
    @Serializable
    data class Args(
        @property:LLMDescription("脚本库返回的 id，不是脚本名称")
        val scriptId: String,
    )

    @Serializable
    data class Result(
        val id: String,
        val name: String? = null,
        val source: String? = null,
        val createdAtEpochMillis: Long? = null,
        val updatedAtEpochMillis: Long? = null,
        val status: Status,
    )

    @Serializable
    enum class Status { FOUND, NOT_FOUND }

    override suspend fun execute(args: Args): Result = readScript(args.scriptId)

    companion object {
        const val NAME = "read_lab_script"
    }
}

internal class SaveLabScriptTool(
    private val saveScript: suspend (String?, String, String) -> Result,
) : Tool<SaveLabScriptTool.Args, SaveLabScriptTool.Result>(
    argsType = typeToken<Args>(),
    resultType = typeToken<Result>(),
    name = NAME,
    description = "创建或更新用户的 Lab 脚本，仅保存到脚本库，不上传、不运行。更新或重命名时必须提供 scriptId。",
) {
    @Serializable
    data class Args(
        @property:LLMDescription("创建时不传或为 null；更新或重命名时必须提供现有脚本的 id，不能使用名称")
        val scriptId: String? = null,
        @property:LLMDescription("保存后的脚本名称")
        val name: String,
        @property:LLMDescription("完整 Python 3.6 Lab 源码，包含 def start()")
        val source: String,
    )

    @Serializable
    data class Result(
        val id: String,
        val name: String,
        val created: Boolean,
        val sourceLength: Int,
        val updatedAtEpochMillis: Long,
    )

    override suspend fun execute(args: Args): Result {
        validateLabSource(args.source)
        return saveScript(args.scriptId, args.name, args.source)
    }

    companion object {
        const val NAME = "save_lab_script"
    }
}

internal class DeleteLabScriptTool(
    private val scriptName: suspend (String) -> String,
    private val deleteScript: suspend (String) -> Result,
) : Tool<DeleteLabScriptTool.Args, DeleteLabScriptTool.Result>(
    argsType = typeToken<Args>(),
    resultType = typeToken<Result>(),
    name = NAME,
    description = "按脚本 ID 永久删除一个用户保存的 Lab 脚本。仅在用户明确要求删除时调用，执行前还会显示界面确认。",
), ApprovalRequiredTool<DeleteLabScriptTool.Args, DeleteLabScriptTool.Result> {
    @Serializable
    data class Args(
        @property:LLMDescription("要删除的脚本 id，取自脚本库工具的结果，不是名称")
        val scriptId: String,
    )

    @Serializable
    data class Result(
        val id: String,
        val name: String,
        val status: Status,
    )

    @Serializable
    enum class Status { DELETED, USER_REJECTED }

    override suspend fun execute(args: Args): Result = deleteScript(args.scriptId)

    override suspend fun prepareApproval(args: Args) = ToolApprovalPreparation(scriptName(args.scriptId))

    override fun rejectedResult(args: Args, preparation: ToolApprovalPreparation) =
        Result(args.scriptId, preparation.preview, Status.USER_REJECTED)

    companion object {
        const val NAME = "delete_lab_script"
    }
}

internal class StopLabTool(
    private val stop: suspend () -> Result,
) : Tool<NoToolArgs, StopLabTool.Result>(
    argsType = typeToken<NoToolArgs>(),
    resultType = typeToken<Result>(),
    name = NAME,
    description = "向当前机器人发送停止脚本命令，不代表停止已被实机确认",
), TerminalTool {
    @Serializable
    data class Result(
        val status: Status,
    )

    @Serializable
    enum class Status { STOP_COMMAND_SENT }

    override suspend fun execute(args: NoToolArgs): Result = stop()

    companion object {
        const val NAME = "stop_lab"
    }
}

private fun validateLabSource(source: String) {
    validate(source.isNotBlank() && source.length <= 32_000) {
        tr(Res.string.script_is_empty_or_exceeds_the_32k_character_limit)
    }
    validate(Regex("(?m)^def\\s+start\\s*\\(\\s*\\)\\s*:").containsMatchIn(source)) {
        tr(Res.string.lab_script_requires_start_entry)
    }
    validate(!Regex("(?m)^\\s*(?:from\\s+\\S+\\s+)?import\\s+").containsMatchIn(source)) {
        tr(Res.string.lab_script_must_not_import_modules)
    }
}
