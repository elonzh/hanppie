package cn.elonzh.hanppie.ui.scripts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.robot.lab.LabAudioClip
import cn.elonzh.hanppie.robot.lab.LabProgram
import cn.elonzh.hanppie.ui.design.WorkbenchGlyph
import cn.elonzh.hanppie.ui.design.WorkbenchIconButton
import cn.elonzh.hanppie.ui.i18n.tr
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Custom audio management, shown in a dialog: importing, renaming and deleting sounds are rare
 * operations, so they should not hold permanent space next to the code editor.
 */
@Composable
internal fun ScriptAudioPanel(
    audio: ScriptAudioState,
    scriptId: String?,
    sourceLength: Int,
    onImport: () -> Unit,
    onInsert: (LabAudioClip) -> Unit,
    onRename: (LabAudioClip) -> Unit,
    onDelete: (LabAudioClip) -> Unit,
    modifier: Modifier = Modifier,
) {
    val encoded = LabAudioClip.totalEncodedBytes(audio.clips)
    val available = (LabProgram.MAX_DSP_BYTES - sourceLength).coerceAtLeast(0)
    val overBudget = encoded > available
    Column(
        modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())
            .testTag("script-audio-panel"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                tr(Res.string.script_audio_footprint_value, kiloBytes(encoded), kiloBytes(available)),
                Modifier.weight(1f),
                fontSize = 12.sp,
                color = if (overBudget) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            WorkbenchIconButton(
                label = tr(Res.string.script_audio_import),
                glyph = WorkbenchGlyph.ADD,
                onClick = onImport,
                enabled = scriptId != null && !audio.busy,
                primary = true,
                tag = "script-audio-import",
            )
        }
        if (overBudget) {
            Text(tr(Res.string.script_audio_over_budget), color = MiuixTheme.colorScheme.error, fontSize = 12.sp)
        }
        if (audio.loading || audio.busy) LinearProgressIndicator(Modifier.fillMaxWidth().heightIn(min = 2.dp))
        when {
            scriptId == null -> Text(
                tr(Res.string.script_audio_needs_script),
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            audio.clips.isEmpty() -> Text(
                tr(Res.string.script_audio_empty),
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            else -> {
                audio.clips.forEach { clip -> ScriptAudioRow(clip, audio.busy, onInsert, onRename, onDelete) }
                Text(
                    tr(Res.string.script_audio_hint),
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        audio.error?.let { Text(it, color = MiuixTheme.colorScheme.error, fontSize = 12.sp) }
    }
}

@Composable
private fun ScriptAudioRow(
    clip: LabAudioClip,
    busy: Boolean,
    onInsert: (LabAudioClip) -> Unit,
    onRename: (LabAudioClip) -> Unit,
    onDelete: (LabAudioClip) -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().testTag("script-audio-${clip.id}"),
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            WorkbenchIconButton(tr(Res.string.script_audio_insert), WorkbenchGlyph.CODE, { onInsert(clip) },
                enabled = !busy, primary = true, tag = "script-audio-insert-${clip.id}")
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(clip.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    WorkbenchIconButton(tr(Res.string.script_audio_rename), WorkbenchGlyph.EDIT,
                        { onRename(clip) }, enabled = !busy, tag = "script-audio-rename-${clip.id}")
                }
                Text(
                    tr(
                        Res.string.script_audio_slot_value,
                        clip.id.toString(),
                        clip.durationSeconds.toString(),
                        kiloBytes(clip.encodedBytes),
                    ),
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            WorkbenchIconButton(tr(Res.string.script_audio_delete), WorkbenchGlyph.DELETE, { onDelete(clip) },
                enabled = !busy, danger = true, tag = "script-audio-delete-${clip.id}")
        }
    }
}

private fun kiloBytes(bytes: Int): String = ((bytes + 512) / 1024).toString()
