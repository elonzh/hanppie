package cn.elonzh.hanppie.ui.scripts

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import cn.elonzh.hanppie.ui.design.WorkbenchDialog
import cn.elonzh.hanppie.ui.design.WorkbenchIconButton
import cn.elonzh.hanppie.ui.design.WorkbenchSmallIconButton
import cn.elonzh.hanppie.ui.i18n.tr
import cn.elonzh.hanppie.ui.robot.audio.AudioPlaybackState
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private data class AudioMove(val snapshot: ScriptAudioState, val from: Int, val to: Int, val focus: Boolean)
private data class AudioReorderMenu(val snapshot: ScriptAudioState, val index: Int, val open: Boolean = true)

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
    onMove: (fromIndex: Int, toIndex: Int, expected: ScriptAudioState) -> Unit,
    onImport: () -> Unit,
    onRename: (LabAudioClip) -> Unit,
    onDelete: (LabAudioClip) -> Unit,
    modifier: Modifier = Modifier,
) {
    val encoded = LabAudioClip.totalEncodedBytes(audio.clips)
    val overBudget = encoded + sourceLength > LabProgram.MAX_DSP_BYTES
    var draggingIndex by remember(scriptId) { mutableStateOf<Int?>(null) }
    var dragOffsetY by remember(scriptId) { mutableFloatStateOf(0f) }
    var pendingMove by remember(scriptId) { mutableStateOf<AudioMove?>(null) }
    var reorder by remember(scriptId) { mutableStateOf<AudioReorderMenu?>(null) }
    var menuDismissed by remember(scriptId) { mutableStateOf(true) }
    var focusTarget by remember(scriptId) { mutableStateOf<Int?>(null) }
    val handles = remember(scriptId) { List(LabAudioClip.MAX_CLIPS) { FocusRequester() } }
    val latestAudio by rememberUpdatedState(audio)
    val latestScriptId by rememberUpdatedState(scriptId)
    val editable = scriptId != null && audio.scriptId == scriptId && !audio.busy && !audio.loading && pendingMove == null
    fun move(expected: ScriptAudioState, from: Int, to: Int, focus: Boolean): Boolean {
        if (expected !== latestAudio || expected.scriptId == null || expected.scriptId != latestScriptId ||
            expected.busy || expected.loading || pendingMove != null ||
            from !in expected.clips.indices || to !in expected.clips.indices || from == to) return false
        pendingMove = AudioMove(expected, from, to, focus)
        onMove(from, to, expected)
        return true
    }
    LaunchedEffect(audio, pendingMove) {
        val request = pendingMove ?: return@LaunchedEffect
        if (audio === request.snapshot || audio.busy) return@LaunchedEffect
        pendingMove = null
        val moved = audio.scriptId == request.snapshot.scriptId && audio.error == null &&
            audio.clips !== request.snapshot.clips &&
            audio.clips.getOrNull(request.to)?.packets === request.snapshot.clips[request.from].packets
        if (moved) {
            if (request.focus) focusTarget = request.to
            else if (draggingIndex != null) draggingIndex = request.to
        }
    }
    LaunchedEffect(focusTarget, menuDismissed, editable) {
        val target = focusTarget ?: return@LaunchedEffect
        if (menuDismissed && editable && target in audio.clips.indices) {
            handles[target].requestFocus()
            focusTarget = null
        }
    }
    val menu = reorder
    val menuEnabled = menu?.open == true && menu.snapshot === audio && editable
    WorkbenchDialog(menuEnabled, { reorder = reorder?.copy(open = false) },
        tr(Res.string.script_audio_reorder_title),
        onDismissFinished = { reorder = null; menuDismissed = true }) {
        if (menu != null) Column(
            Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val clip = menu.snapshot.clips[menu.index]
            Text(clip.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(tr(Res.string.script_audio_slot_value, clip.id.toString(), clip.durationSeconds.toString(), kiloBytes(clip.encodedBytes)),
                fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Button({ reorder = menu.copy(open = false); move(menu.snapshot, menu.index, menu.index - 1, true) },
                Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("script-audio-move-up"), enabled = menuEnabled && menu.index > 0) {
                Text(tr(Res.string.script_audio_move_up))
            }
            Button({ reorder = menu.copy(open = false); move(menu.snapshot, menu.index, menu.index + 1, true) },
                Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("script-audio-move-down"), enabled = menuEnabled && menu.index < menu.snapshot.clips.lastIndex) {
                Text(tr(Res.string.script_audio_move_down))
            }
            Button({ reorder = menu.copy(open = false) }, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("script-audio-reorder-close")) {
                Text(tr(Res.string.close))
            }
        }
    }

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
                enabled = editable,
                primary = true,
                tag = "script-audio-import",
            )
        }
        if (overBudget) {
            Text(tr(Res.string.script_audio_over_budget), Modifier.padding(horizontal = 8.dp), color = MiuixTheme.colorScheme.error, fontSize = 12.sp)
        }
        if (audio.loading || audio.busy || pendingMove != null || scriptId != null && audio.scriptId != scriptId)
            LinearProgressIndicator(Modifier.fillMaxWidth().heightIn(min = 2.dp))
        when {
            scriptId == null -> Text(
                tr(Res.string.script_audio_needs_script),
                Modifier.padding(horizontal = 8.dp),
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            audio.scriptId != scriptId || audio.loading -> Unit
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
                        busy = !editable,
                        scriptId = scriptId,
                        focus = handles[index],
                        onReorder = { menuDismissed = false; reorder = AudioReorderMenu(audio, index) },
                        playbackState = playbackState,
                        isDragging = isDragging,
                        dragOffsetY = dragOffsetY,
                        onPlay = { onPlay(clip) },
                        onPause = onPause,
                        onResume = onResume,
                        onDragDelta = { delta ->
                            dragOffsetY += delta
                            val from = draggingIndex ?: return@ScriptAudioRow
                            val threshold = 70f
                            if (dragOffsetY > threshold && move(latestAudio, from, from + 1, false)) {
                                dragOffsetY -= threshold
                            } else if (dragOffsetY < -threshold && move(latestAudio, from, from - 1, false)) {
                                dragOffsetY += threshold
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
        audio.error?.let { Text(it.resolve(), Modifier.padding(horizontal = 8.dp).testTag("script-audio-error"), color = MiuixTheme.colorScheme.error, fontSize = 12.sp) }
    }
}

@Composable
private fun ScriptAudioRow(
    clip: LabAudioClip,
    index: Int,
    busy: Boolean,
    scriptId: String?,
    focus: FocusRequester,
    onReorder: () -> Unit,
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
    var focused by remember(scriptId, index) { mutableStateOf(false) }
    val currentDragStart by rememberUpdatedState(onDragStart)
    val currentDragDelta by rememberUpdatedState(onDragDelta)
    val currentDragEnd by rememberUpdatedState(onDragEnd)
    val currentBusy by rememberUpdatedState(busy)

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
            WorkbenchIconButton(
                label = tr(Res.string.script_audio_reorder, clip.name) + " · " +
                    tr(Res.string.script_audio_slot_value, clip.id.toString(), clip.durationSeconds.toString(), kiloBytes(clip.encodedBytes)),
                glyph = WorkbenchGlyph.GRIP_VERTICAL,
                onClick = onReorder, enabled = !busy, tag = "script-audio-reorder-${clip.id}",
                modifier = Modifier.focusRequester(focus).onFocusChanged { focused = it.isFocused }
                    .then(if (focused) Modifier.border(2.dp, MiuixTheme.colorScheme.primary, RoundedCornerShape(14.dp)) else Modifier)
                    .pointerInput(scriptId, index) {
                    detectDragGestures(
                        onDragStart = { if (!currentBusy) currentDragStart() },
                        onDragEnd = { currentDragEnd() },
                        onDragCancel = { currentDragEnd() },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            currentDragDelta(dragAmount.y)
                        },
                    )
                },
            )

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
                        Modifier.weight(1f, fill = false),
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
