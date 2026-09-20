package cn.elonzh.hanppie.ui.scripts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.robot.lab.ScriptRunPhase
import cn.elonzh.hanppie.ui.app.ConsoleState
import cn.elonzh.hanppie.ui.design.DesktopListScrollbar
import cn.elonzh.hanppie.ui.i18n.tr
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * The script console: run phase, elapsed time, run id and the onboard output of the current run.
 *
 * It is the same panel on every screen size; wide layouts place it beside the editor and phones put it
 * below, so a run never replaces the editor with a separate log page.
 */
@Composable
internal fun ScriptConsolePanel(state: ConsoleState, modifier: Modifier = Modifier) {
    val lines = state.scriptMessages
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }
    BoxWithConstraints(modifier) {
        // Short landscape windows have to keep the log readable, so the panel drops to one status line.
        val tight = maxHeight < 220.dp
        Card(Modifier.fillMaxSize().testTag("script-console"),
            colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.fillMaxSize().padding(if (tight) 12.dp else 16.dp),
                verticalArrangement = Arrangement.spacedBy(if (tight) 6.dp else 10.dp)) {
                ScriptRunStatusRow(state, tight)
                if (state.scriptRunPhase.progressing) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
                }
                ScriptRunLog(lines, listState, tight, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ScriptRunStatusRow(state: ConsoleState, tight: Boolean) {
    val color = scriptRunColor(state.scriptRunPhase)
    val elapsedText = rememberScriptElapsedText(state)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(if (tight) 8.dp else 10.dp).background(color, RoundedCornerShape(50)))
            Spacer(Modifier.width(8.dp))
            SelectionContainer(Modifier.weight(1f)) {
                Text(
                    state.scriptStatus,
                    fontSize = if (tight) 13.sp else 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = color,
                    maxLines = if (tight || state.scriptRunPhase != ScriptRunPhase.FAILED) 1 else 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            elapsedText?.let {
                Text(it, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
        if (tight) return@Column
        state.scriptTitle?.let {
            Text(it, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        state.scriptRunId?.let { runId ->
            SelectionContainer {
                Text(tr(Res.string.script_run_id_value, runId), fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ScriptRunLog(
    lines: List<String>,
    listState: LazyListState,
    tight: Boolean,
    modifier: Modifier,
) {
    Column(modifier.testTag("script-run-log")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(tr(Res.string.run_log), Modifier.weight(1f), fontSize = if (tight) 13.sp else 15.sp,
                fontWeight = FontWeight.SemiBold)
            Text(lines.size.toString(), fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        Spacer(Modifier.height(if (tight) 6.dp else 10.dp))
        if (lines.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(tr(Res.string.waiting_for_script_output), fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        } else {
            Box(Modifier.weight(1f)) {
                SelectionContainer(Modifier.fillMaxSize().padding(end = 12.dp)) {
                    LazyColumn(Modifier.fillMaxSize(), state = listState,
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(lines) { index, line ->
                            Row(Modifier.fillMaxWidth()) {
                                Text((index + 1).toString().padStart(2, '0'), Modifier.width(30.dp), fontSize = 11.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    fontFamily = FontFamily.Monospace)
                                Text(line, Modifier.weight(1f), fontSize = 12.sp, lineHeight = 18.sp,
                                    fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
                DesktopListScrollbar(listState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }
    }
}

/** Ticking elapsed run time, shared by the console panel and the global run bar. */
@Composable
internal fun rememberScriptElapsedText(state: ConsoleState): String? {
    var now by remember { mutableStateOf(kotlin.time.Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(state.scriptRunPhase.active, state.scriptStartedAtEpochMillis) {
        while (state.scriptRunPhase.active) {
            delay(1_000)
            now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        }
    }
    val elapsed = state.scriptStartedAtEpochMillis?.let { ((now - it).coerceAtLeast(0) / 1_000) }
    return elapsed?.let { "${it / 60}:${(it % 60).toString().padStart(2, '0')}" }
}

@Composable
internal fun scriptRunColor(phase: ScriptRunPhase) = when (phase) {
    ScriptRunPhase.COMPLETED -> androidx.compose.ui.graphics.Color(0xff32aa78)
    ScriptRunPhase.FAILED, ScriptRunPhase.UNKNOWN, ScriptRunPhase.STOP_UNCONFIRMED -> MiuixTheme.colorScheme.error
    else -> MiuixTheme.colorScheme.primary
}
