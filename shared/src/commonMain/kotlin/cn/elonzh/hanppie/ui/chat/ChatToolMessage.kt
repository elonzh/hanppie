package cn.elonzh.hanppie.ui.chat

import cn.elonzh.hanppie.ui.robot.telemetry.signalQualityLabel
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.agent.runtime.toolNameFromUnknownNotice
import cn.elonzh.hanppie.agent.tools.DeleteLabScriptTool
import cn.elonzh.hanppie.agent.tools.ExecuteLabPythonTool
import cn.elonzh.hanppie.agent.tools.ReadSkillTool
import cn.elonzh.hanppie.agent.tools.ListLabScriptsTool
import cn.elonzh.hanppie.agent.tools.ReadLabScriptTool
import cn.elonzh.hanppie.agent.tools.RobotStatusTool
import cn.elonzh.hanppie.agent.tools.SaveLabScriptTool
import cn.elonzh.hanppie.agent.tools.StopLabTool
import cn.elonzh.hanppie.ui.design.WorkbenchGlyph
import cn.elonzh.hanppie.ui.design.WorkbenchIcon
import cn.elonzh.hanppie.ui.i18n.tr
import kotlinx.serialization.json.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ToolMessage(line: ChatLine, onOpenScript: (String) -> Unit) {
    val tool = checkNotNull(line.toolCall?.tool ?: line.toolResult?.tool) {
        "Tool activity requires a Koog tool call or result"
    }
    val result = line.toolResult
    val outcomeUnknown = result?.output?.let(::toolNameFromUnknownNotice) != null
    val arguments = line.toolCall?.args?.let(::parseToolPayload)?.takeUnless(JsonElement::isEmptyPayload)
    val output = when {
        result == null || outcomeUnknown -> null
        result.isError -> JsonPrimitive(result.output)
        else -> parseToolPayload(result.output)
    }?.takeUnless(JsonElement::isEmptyPayload)
    val hasDetails = arguments != null || output != null
    val resultStatus = (output as? JsonObject).string("status")
    val rejected = resultStatus == ExecuteLabPythonTool.Status.USER_REJECTED.name ||
        resultStatus == DeleteLabScriptTool.Status.USER_REJECTED.name
    val commandSent = resultStatus == ExecuteLabPythonTool.Status.START_COMMAND_SENT.name ||
        resultStatus == StopLabTool.Status.STOP_COMMAND_SENT.name
    val scriptCard = tool in setOf(ReadLabScriptTool.NAME, SaveLabScriptTool.NAME) &&
        (output as? JsonObject).string("name") != null && result?.isError == false
    val summary = toolSummary(tool, arguments, output, result?.isError == true)
    var detailsVisible by rememberSaveable(result?.id, line.toolCall?.args, result?.isError) { mutableStateOf(result?.isError == true) }
    val status = when {
        result == null -> tr(Res.string.tool_in_progress)
        outcomeUnknown -> tr(Res.string.tool_result_unknown)
        result.isError -> tr(Res.string.tool_failed)
        rejected -> tr(Res.string.tool_canceled)
        commandSent -> tr(Res.string.tool_command_sent)
        else -> tr(Res.string.tool_completed)
    }
    val statusColor = if (result?.isError == true || outcomeUnknown) {
        MiuixTheme.colorScheme.error
    } else {
        MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    Column(
        Modifier.fillMaxWidth()
            .testTag("tool-message-$tool"),
    ) {
        val headerModifier = if (hasDetails) {
            Modifier.toggleable(
                value = detailsVisible,
                role = Role.Button,
                onValueChange = { detailsVisible = it },
            ).semantics {
                stateDescription = tr(
                    if (detailsVisible) Res.string.tool_details_expanded else Res.string.tool_details_collapsed,
                )
            }
        } else {
            Modifier
        }
        Row(
            headerModifier.fillMaxWidth().testTag("tool-message-header-$tool")
                .heightIn(min = 32.dp)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            WorkbenchIcon(toolGlyph(tool), MiuixTheme.colorScheme.onSurfaceVariantSummary, Modifier.size(18.dp))
            Text(toolLabel(tool), Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(status, color = statusColor, fontSize = 11.sp)
            if (hasDetails) {
                WorkbenchIcon(
                    WorkbenchGlyph.CHEVRON_RIGHT,
                    MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    Modifier.size(16.dp).rotate(if (detailsVisible) 90f else 0f),
                )
            }
        }
        if (summary != null && !detailsVisible && !scriptCard) {
            Text(
                summary,
                Modifier.fillMaxWidth().padding(start = if (tool == RobotStatusTool.NAME) 12.dp else 39.dp, end = 12.dp, bottom = 9.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (result?.isError == true) {
                    MiuixTheme.colorScheme.error
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
                fontSize = 11.sp,
            )
        }
        if (result != null && !result.isError && !outcomeUnknown && !rejected) {
            ToolPresentation(tool, output as? JsonObject, onOpenScript)
        }
        if (detailsVisible) {
            Column(
                Modifier.fillMaxWidth().padding(start = 39.dp, end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                arguments?.let { ToolPayload(tr(Res.string.tool_parameters), it) }
                output?.let { ToolPayload(tr(Res.string.tool_result), it) }
            }
        }
    }
}

@Composable
private fun ToolPayload(title: String, payload: JsonElement) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            title,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
        SelectionContainer { StructuredJson(payload) }
    }
}

@Composable
private fun StructuredJson(value: JsonElement, field: String? = null) {
    when (value) {
        JsonNull -> ToolField(field, "—")
        is JsonPrimitive -> {
            if ((field == "source" || field == "content") && value.isString) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ToolFieldName(field)
                    ChatMarkdown(if (field == "source") "```python\n${value.content.trimEnd()}\n```" else value.content, Modifier.fillMaxWidth())
                }
            } else {
                ToolField(field, value.content)
            }
        }
        is JsonObject -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            value.forEach { (name, child) ->
                if (child is JsonObject || child is JsonArray) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ToolFieldName(name)
                        Box(Modifier.padding(start = 10.dp)) { StructuredJson(child) }
                    }
                } else {
                    StructuredJson(child, name)
                }
            }
        }
        is JsonArray -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            value.forEach { child ->
                if (child is JsonObject) {
                    Column(
                        Modifier.fillMaxWidth()
                            .background(MiuixTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp))
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        StructuredJson(child)
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("•", color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 12.sp)
                        Box(Modifier.weight(1f)) { StructuredJson(child, field = null) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolField(field: String?, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        field?.let { ToolFieldName(it, Modifier.widthIn(min = 104.dp, max = 160.dp)) }
        Text(
            value,
            Modifier.weight(1f, fill = false),
            color = MiuixTheme.colorScheme.onSurface,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun ToolFieldName(field: String, modifier: Modifier = Modifier) {
    Text(
        field,
        modifier,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
    )
}

@Composable
private fun toolSummary(
    tool: String,
    arguments: JsonElement?,
    output: JsonElement?,
    resultIsError: Boolean,
): String? {
    if (output is JsonPrimitive) {
        return if (resultIsError) tr(Res.string.tool_failure_summary) else output.content.lineSequence().firstOrNull()
    }
    val args = arguments as? JsonObject
    val result = output as? JsonObject
    return when (tool) {
        RobotStatusTool.NAME -> if (result == null) {
            null
        } else {
            val connection = if ((result["connected"] as? JsonPrimitive)?.booleanOrNull == true) {
                tr(Res.string.connected)
            } else {
                tr(Res.string.disconnected)
            }
            (result["batteryPercent"] as? JsonPrimitive)?.intOrNull?.let { battery ->
                "$connection · ${tr(Res.string.tool_battery_summary, battery)}"
            } ?: connection
        }
        ReadSkillTool.NAME -> args.string("name")?.let { name ->
            tr(Res.string.tool_skill_path_summary, "$name/${args.string("path") ?: "SKILL.md"}")
        }
        ListLabScriptsTool.NAME -> (result?.get("scripts") as? JsonArray)?.size?.let {
            tr(Res.string.tool_scripts_summary, it)
        }
        ReadLabScriptTool.NAME -> if (result.string("status") == ReadLabScriptTool.Status.NOT_FOUND.name) {
            tr(Res.string.chat_script_not_found)
        } else result.string("name")?.let { tr(Res.string.tool_script_summary, it) }
        SaveLabScriptTool.NAME -> (result.string("name") ?: args.string("name"))?.let { tr(Res.string.tool_script_summary, it) }
        DeleteLabScriptTool.NAME -> if (result.string("status") == DeleteLabScriptTool.Status.USER_REJECTED.name) {
            tr(Res.string.delete_script_rejected_summary)
        } else {
            (result.string("name") ?: args.string("name"))?.let { tr(Res.string.tool_script_summary, it) }
        }
        ExecuteLabPythonTool.NAME -> if (result.string("status") == ExecuteLabPythonTool.Status.USER_REJECTED.name) {
            tr(Res.string.execute_script_rejected_summary)
        } else {
            args.string("source")?.length?.let { tr(Res.string.tool_source_summary, it) }
        }
        StopLabTool.NAME -> if (result.string("status") == StopLabTool.Status.STOP_COMMAND_SENT.name) {
            tr(Res.string.stop_command_sent_robot_stop_is_unconfirmed)
        } else null
        else -> if (result?.isNotEmpty() == true) result.entries.first().value.summaryValue() else null
    }
}

private fun JsonObject?.string(name: String): String? =
    (this?.get(name) as? JsonPrimitive)?.contentOrNull

private fun JsonElement.summaryValue(): String? = when (this) {
    JsonNull -> null
    is JsonPrimitive -> content
    is JsonArray -> size.toString()
    is JsonObject -> entries.firstOrNull()?.value?.summaryValue()
}

private fun JsonElement.isEmptyPayload(): Boolean =
    (this is JsonObject && isEmpty()) || (this is JsonArray && isEmpty())

@Composable
private fun toolLabel(tool: String): String = when (tool) {
    RobotStatusTool.NAME -> tr(Res.string.read_status)
    ReadSkillTool.NAME -> tr(Res.string.read_agent_skill)
    ListLabScriptsTool.NAME -> tr(Res.string.list_lab_scripts)
    ReadLabScriptTool.NAME -> tr(Res.string.read_lab_script)
    SaveLabScriptTool.NAME -> tr(Res.string.save_lab_script)
    DeleteLabScriptTool.NAME -> tr(Res.string.delete_lab_script)
    ExecuteLabPythonTool.NAME -> tr(Res.string.run_lab_script)
    StopLabTool.NAME -> tr(Res.string.stop_script)
    else -> tool
}

private fun toolGlyph(tool: String): WorkbenchGlyph = when (tool) {
    RobotStatusTool.NAME -> WorkbenchGlyph.ACTIVITY
    ReadSkillTool.NAME -> WorkbenchGlyph.SEARCH
    ListLabScriptsTool.NAME -> WorkbenchGlyph.FOLDER
    ReadLabScriptTool.NAME -> WorkbenchGlyph.FILE_TEXT
    SaveLabScriptTool.NAME -> WorkbenchGlyph.SAVE
    DeleteLabScriptTool.NAME -> WorkbenchGlyph.DELETE
    ExecuteLabPythonTool.NAME -> WorkbenchGlyph.CODE
    StopLabTool.NAME -> WorkbenchGlyph.STOP
    else -> WorkbenchGlyph.ACTIVITY
}



/** Old sessions and third-party tools may contain plain text or a different JSON shape. */
private fun parseToolPayload(raw: String): JsonElement =
    runCatching { Json.parseToJsonElement(raw) }.getOrElse { JsonPrimitive(raw) }

@Composable
private fun ToolPresentation(tool: String, result: JsonObject?, onOpenScript: (String) -> Unit) {
    if (result == null) return
    when (tool) {
        ListLabScriptsTool.NAME -> ScriptListResult(result, onOpenScript)
        ReadLabScriptTool.NAME -> ReadScriptResult(result, onOpenScript)
        SaveLabScriptTool.NAME -> SaveScriptResult(result, onOpenScript)
        RobotStatusTool.NAME -> RobotStatusResult(result)
        ExecuteLabPythonTool.NAME -> ExecuteScriptResult(result)
        DeleteLabScriptTool.NAME -> DeleteScriptResult(result)
    }
}

@Composable
private fun ScriptListResult(result: JsonObject, onOpenScript: (String) -> Unit) {
    val scripts = result["scripts"] as? JsonArray ?: return
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (scripts.isEmpty()) Text(tr(Res.string.no_saved_scripts), fontSize = 12.sp)
        scripts.forEach { item ->
            val script = item as? JsonObject
            val name = script.string("name")
            if (name != null) ScriptToolRow(script.string("id"), name, script.string("sourceLength"), onOpenScript)
        }
    }
}

@Composable
private fun ReadScriptResult(result: JsonObject, onOpenScript: (String) -> Unit) {
    val name = result.string("name") ?: return
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
        ScriptToolRow(result.string("id"), name, result.string("source")?.length?.toString(), onOpenScript)
    }
}

@Composable
private fun SaveScriptResult(result: JsonObject, onOpenScript: (String) -> Unit) {
    val name = result.string("name") ?: return
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
        ScriptToolRow(result.string("id"), name, result.string("sourceLength"), onOpenScript)
        Text(tr(if ((result["created"] as? JsonPrimitive)?.booleanOrNull == true)
            Res.string.chat_script_created else Res.string.chat_script_updated),
            fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
private fun RobotStatusResult(result: JsonObject) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        result.string("address")?.let { ToolField(tr(Res.string.robot_ipv4), it) }
        ToolField(tr(Res.string.signal), signalQualityLabel(result.string("signalQualityRaw")?.toIntOrNull()))
        val script = result["script"] as? JsonObject
        script.string("title")?.let { ToolField(tr(Res.string.script), it) }
        script.string("phase")?.let { phase ->
            val label = when (phase) {
                "IDLE" -> tr(Res.string.no_script_running)
                "UPLOADING" -> tr(Res.string.uploading)
                "STARTING" -> tr(Res.string.waiting_for_script_start)
                "RUNNING" -> tr(Res.string.script_running)
                "COMPLETING" -> tr(Res.string.finishing_completed_script)
                "COMPLETED" -> tr(Res.string.script_completed)
                "FAILED" -> tr(Res.string.tool_failed)
                "STOPPING" -> tr(Res.string.stopping_script)
                "STOP_UNCONFIRMED" -> tr(Res.string.stop_command_sent_robot_stop_is_unconfirmed)
                "UNKNOWN" -> tr(Res.string.tool_result_unknown)
                else -> phase
            }
            Text(label, fontSize = 12.sp)
        }
        (script?.get("recentMessages") as? JsonArray)?.takeIf { it.isNotEmpty() }?.let {
            SelectionContainer { Text(it.mapNotNull { entry -> (entry as? JsonPrimitive)?.contentOrNull }
                .joinToString("\n"), fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
        }
    }
}

@Composable
private fun ExecuteScriptResult(result: JsonObject) {
    if (result.string("status") == ExecuteLabPythonTool.Status.START_COMMAND_SENT.name) {
        Text(tr(Res.string.chat_script_start_sent), Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp), fontSize = 12.sp)
    }
}

@Composable
private fun DeleteScriptResult(result: JsonObject) {
    if (result.string("status") == DeleteLabScriptTool.Status.DELETED.name) {
        Text(tr(Res.string.chat_script_deleted), Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp), fontSize = 12.sp)
    }
}

@Composable
private fun ScriptToolRow(id: String?, name: String, length: String?, onOpenScript: (String) -> Unit) {
    val action = if (id != null) Modifier.clickable(role = Role.Button) { onOpenScript(id) }
        .testTag("tool-open-script-$id") else Modifier
    Row(Modifier.fillMaxWidth().then(action)
        .heightIn(min = 32.dp).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        WorkbenchIcon(WorkbenchGlyph.FILE_TEXT, MiuixTheme.colorScheme.primary, Modifier.size(20.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(name, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            length?.let { Text(tr(Res.string.tool_source_summary, it), fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
        }
        if (id != null) {
            Text(tr(Res.string.chat_open_editor), fontSize = 11.sp, color = MiuixTheme.colorScheme.primary)
            WorkbenchIcon(WorkbenchGlyph.CHEVRON_RIGHT, MiuixTheme.colorScheme.primary, Modifier.size(16.dp))
        }
    }
}
