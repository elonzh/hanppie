package cn.elonzh.hanppie.ui.scripts

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.robot.lab.LabAudioClip
import cn.elonzh.hanppie.robot.lab.LabProgram
import cn.elonzh.hanppie.ui.design.WorkbenchGlyph
import cn.elonzh.hanppie.ui.design.WorkbenchIcon
import cn.elonzh.hanppie.ui.design.WorkbenchIconButton
import cn.elonzh.hanppie.ui.design.WorkbenchSmallIconButton
import cn.elonzh.hanppie.ui.i18n.tr
import cn.elonzh.hanppie.ui.robot.audio.AudioPlaybackState
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Custom audio management, shown in a dialog: importing, previewing, reordering,
 * renaming, and deleting sounds.
 */
@Composable
internal fun ScriptAudioPanel(
    audio: ScriptAudioState,
    scriptId: String?,
    sourceLength: Int,
    playbackState: AudioPlaybackState = AudioPlaybackState(),
    onPlay: (LabAudioClip) -> Unit = {},
    onPause: () -> Unit = {},
    onResume: () -> Unit = {},
    onMove: (fromIndex: Int, toIndex: Int) -> Unit = { _, _ -> },
    onImport: () -> Unit,
    onRename: (LabAudioClip) -> Unit,
    onDelete: (LabAudioClip) -> Unit,
    modifier: Modifier = Modifier,
) {
    val encoded = LabAudioClip.totalEncodedBytes(audio.clips)
    val overBudget = encoded + sourceLength > LabProgram.MAX_DSP_BYTES
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }

    Column(
        modifier.fillMaxWidth().heightIn(max = 450.dp).verticalScroll(rememberScrollState())
            .testTag("script-audio-panel"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                tr(
                    Res.string.script_audio_footprint_value,
                    kiloBytes(encoded),
                    audio.clips.size.toString(),
                    LabAudioClip.MAX_CLIPS.toString(),
                ),
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
            Text(tr(Res.string.script_audio_over_budget), Modifier.padding(horizontal = 8.dp), color = MiuixTheme.colorScheme.error, fontSize = 12.sp)
        }
        if (audio.loading || audio.busy) LinearProgressIndicator(Modifier.fillMaxWidth().heightIn(min = 2.dp))
        when {
            scriptId == null -> Text(
                tr(Res.string.script_audio_needs_script),
                Modifier.padding(horizontal = 8.dp),
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            audio.clips.isEmpty() -> Text(
                tr(Res.string.script_audio_empty),
                Modifier.padding(horizontal = 8.dp),
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            else -> {
                audio.clips.forEachIndexed { index, clip ->
                    val isDragging = draggingIndex == index
                    ScriptAudioRow(
                        clip = clip,
                        index = index,
                        busy = audio.busy,
                        playbackState = playbackState,
                        isDragging = isDragging,
                        dragOffsetY = dragOffsetY,
                        onPlay = { onPlay(clip) },
                        onPause = onPause,
                        onResume = onResume,
                        onDragDelta = { delta ->
                            dragOffsetY += delta
                            val threshold = 70f
                            if (dragOffsetY > threshold && index < audio.clips.size - 1) {
                                onMove(index, index + 1)
                                dragOffsetY -= threshold
                                draggingIndex = index + 1
                            } else if (dragOffsetY < -threshold && index > 0) {
                                onMove(index, index - 1)
                                dragOffsetY += threshold
                                draggingIndex = index - 1
                            }
                        },
                        onDragStart = {
                            draggingIndex = index
                            dragOffsetY = 0f
                        },
                        onDragEnd = {
                            draggingIndex = null
                            dragOffsetY = 0f
                        },
                        onRename = onRename,
                        onDelete = onDelete,
                    )
                }
                Text(
                    tr(Res.string.script_audio_hint),
                    Modifier.padding(horizontal = 8.dp),
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        audio.error?.let { Text(it, Modifier.padding(horizontal = 8.dp), color = MiuixTheme.colorScheme.error, fontSize = 12.sp) }
    }
}

@Composable
private fun ScriptAudioRow(
    clip: LabAudioClip,
    index: Int,
    busy: Boolean,
    playbackState: AudioPlaybackState,
    isDragging: Boolean,
    dragOffsetY: Float,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDragDelta: (Float) -> Unit,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onRename: (LabAudioClip) -> Unit,
    onDelete: (LabAudioClip) -> Unit,
) {
    val isCurrent = playbackState.playingClipId == clip.id
    val isPlaying = isCurrent && playbackState.isPlaying
    val isPaused = isCurrent && !playbackState.isPlaying

    Card(
        Modifier.fillMaxWidth()
            .zIndex(if (isDragging) 1f else 0f)
            .graphicsLayer { translationY = if (isDragging) dragOffsetY else 0f }
            .testTag("script-audio-${clip.id}"),
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Drag handle for pointer vertical dragging
            Box(
                Modifier.pointerInput(clip.id) {
                    detectDragGestures(
                        onDragStart = { onDragStart() },
                        onDragEnd = { onDragEnd() },
                        onDragCancel = { onDragEnd() },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            onDragDelta(dragAmount.y)
                        },
                    )
                }.padding(horizontal = 4.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                WorkbenchIcon(WorkbenchGlyph.GRIP_VERTICAL, MiuixTheme.colorScheme.disabledOnSurface)
            }

            Spacer(Modifier.width(4.dp))

            // Play / Pause / Resume button
            WorkbenchIconButton(
                label = if (isPlaying) tr(Res.string.script_audio_pause) else tr(Res.string.script_audio_play),
                glyph = if (isPlaying) WorkbenchGlyph.PAUSE else WorkbenchGlyph.PLAY,
                onClick = {
                    when {
                        isPlaying -> onPause()
                        isPaused -> onResume()
                        else -> onPlay()
                    }
                },
                enabled = !busy,
                primary = isPlaying,
                tag = "script-audio-play-${clip.id}",
            )

            Spacer(Modifier.width(8.dp))

            // Middle info
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        clip.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(2.dp))
                    WorkbenchSmallIconButton(
                        label = tr(Res.string.script_audio_rename),
                        glyph = WorkbenchGlyph.EDIT,
                        onClick = { onRename(clip) },
                        enabled = !busy,
                        tag = "script-audio-rename-${clip.id}",
                    )
                }
                val infoText = if (isCurrent) {
                    tr(
                        Res.string.script_audio_slot_playing_value,
                        clip.id.toString(),
                        formatTime(playbackState.positionMillis),
                        formatTime(clip.durationMillis),
                        kiloBytes(clip.encodedBytes),
                    )
                } else {
                    tr(
                        Res.string.script_audio_slot_value,
                        clip.id.toString(),
                        clip.durationSeconds.toString(),
                        kiloBytes(clip.encodedBytes),
                    )
                }
                Text(
                    infoText,
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                if (isCurrent && clip.durationMillis > 0) {
                    val progress = (playbackState.positionMillis.toFloat() / clip.durationMillis.toFloat()).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = progress,
                        modifier = Modifier.fillMaxWidth().height(4.dp).padding(top = 2.dp),
                    )
                }
            }

            Spacer(Modifier.width(4.dp))

            // Delete
            WorkbenchIconButton(
                label = tr(Res.string.script_audio_delete),
                glyph = WorkbenchGlyph.DELETE,
                onClick = { onDelete(clip) },
                enabled = !busy,
                danger = true,
                tag = "script-audio-delete-${clip.id}",
            )
        }
    }
}

private fun formatTime(millis: Long): String {
    val totalSec = (millis + 500) / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "${min.toString().padStart(2, '0')}:${sec.toString().padStart(2, '0')}"
}

private fun kiloBytes(bytes: Int): String = ((bytes + 512) / 1024).toString()
