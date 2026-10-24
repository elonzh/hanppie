package cn.elonzh.hanppie.ui.robot.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.robot.challenge.*
import cn.elonzh.hanppie.ui.design.HanppieDesignTokens
import cn.elonzh.hanppie.ui.design.WorkbenchDialog
import cn.elonzh.hanppie.ui.i18n.UiText
import cn.elonzh.hanppie.ui.i18n.tr
import top.yukonga.miuix.kmp.basic.*

@Composable
internal fun ChallengeHud(state: ChallengeState, modifier: Modifier, onStop: () -> Unit) {
    val configuration = state.configuration ?: return
    val modeTitle = challengeModeTitle(configuration.mode)
    Row(modifier.widthIn(max = 500.dp)
        .background(HanppieDesignTokens.RemoteHudSurface.copy(alpha = HanppieDesignTokens.RemoteHudAlpha), RoundedCornerShape(12.dp))
        .padding(horizontal = 12.dp, vertical = 6.dp).testTag("challenge-hud").semantics { contentDescription = modeTitle },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f, fill = false)) {
        Text(when (state.phase) {
            ChallengePhase.PREPARING -> tr(Res.string.challenge_preparing)
            ChallengePhase.COUNTDOWN -> when (val step = state.countdownStep) {
                null -> tr(Res.string.challenge_preparing)
                ChallengeCountdownStep.START -> tr(Res.string.challenge_go)
                else -> tr(Res.string.challenge_countdown, checkNotNull(step.number).toString())
            }
            ChallengePhase.WAVE_CLEAR -> tr(Res.string.challenge_wave_clear)
            ChallengePhase.LEVEL_CHANGE -> tr(Res.string.challenge_level_change, challengeStage(state.stage))
            ChallengePhase.STOPPING -> tr(Res.string.challenge_stopping)
            ChallengePhase.RESULT -> tr(Res.string.challenge_finished)
            else -> if (configuration.mode == ChallengeMode.TARGET_PRACTICE)
                tr(Res.string.challenge_practice_live, challengeStage(state.stage), state.wave.toString(), state.score.toString(), challengeTime(state.remainingMillis))
            else modeTitle + " · " + tr(Res.string.challenge_race_live, challengeTime(state.elapsedMillis))
        }, Modifier.testTag("challenge-status").semantics { if (state.phase != ChallengePhase.PLAYING) liveRegion = LiveRegionMode.Polite },
            color = HanppieDesignTokens.RemoteHudContent, fontSize = 13.sp)
        if (state.phase == ChallengePhase.PLAYING) Text(tr(Res.string.challenge_targets_left,
            state.targets.filterNot { it.completed }.joinToString(" · ") { (it.markerId - 10).toString() }),
            Modifier.testTag("challenge-targets").semantics { liveRegion = LiveRegionMode.Polite }, color = HanppieDesignTokens.RemoteHudContent, fontSize = 14.sp)
        }
        if (state.phase !in listOf(ChallengePhase.STOPPING, ChallengePhase.RESULT)) HudTextButton(tr(Res.string.challenge_stop),
            true, "challenge-stop", action = onStop)
    }
}

private data class Score(val configuration: ChallengeConfiguration, val name: String?, val points: Int, val millis: Long)

/** Scores live with this cockpit, matching the fixed component's local list rather than inventing cloud persistence. */
@Composable
internal fun ChallengeDialogs(choosing: Boolean, state: ChallengeState, enabled: Boolean, sounds: Boolean, onSoundsChange: (Boolean) -> Unit, maxContentHeight: Dp,
    onChooseClose: () -> Unit, onStart: (ChallengeConfiguration) -> UiText?, onStop: () -> Unit, onDismiss: () -> Unit) {
    var mode by remember { mutableStateOf(ChallengeMode.TARGET_PRACTICE) }
    var firing by remember { mutableStateOf(ChallengeFiring.INFRARED) }
    var markerCount by remember { mutableIntStateOf(5) }
    var name by remember { mutableStateOf("") }
    var scores by remember { mutableStateOf(emptyList<Score>()) }
    var savedRound by remember { mutableStateOf<Long?>(null) }
    var startError by remember { mutableStateOf<UiText?>(null) }
    LaunchedEffect(choosing) { if (choosing) startError = null }
    LaunchedEffect(state.phase) { if (state.phase == ChallengePhase.PREPARING) savedRound = null }
    val result = state.phase == ChallengePhase.RESULT
    WorkbenchDialog(show = choosing && !state.ownsRound, onDismissRequest = onChooseClose,
        title = tr(Res.string.challenge_title)) {
        Column(Modifier.fillMaxWidth().heightIn(max = maxContentHeight)
            .testTag("challenge-setup"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChallengeMode.entries.forEach { value ->
                    Button({ mode = value; if (value == ChallengeMode.TARGET_PRACTICE) firing = ChallengeFiring.INFRARED },
                        Modifier.heightIn(min = 48.dp).testTag("challenge-mode-${value.name.lowercase()}").semantics { selected = mode == value },
                        colors = if (mode == value) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors()) {
                        Text(challengeModeTitle(value), fontSize = 15.sp)
                    }
                }
            }
            Text(tr(if (mode == ChallengeMode.TARGET_PRACTICE) Res.string.challenge_practice_guide else Res.string.challenge_race_guide), fontSize = 14.sp)
            if (mode == ChallengeMode.TARGET_RACE) {
                Text(tr(Res.string.challenge_marker_count), fontSize = 14.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (1..5).forEach { count -> Button({ markerCount = count },
                        Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("challenge-count-$count")
                            .semantics { selected = markerCount == count },
                        colors = if (markerCount == count) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors()) {
                        Text(count.toString())
                    } }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChallengeFiring.entries.forEach { value -> Button({ firing = value },
                        Modifier.heightIn(min = 48.dp).testTag("challenge-firing-${value.name.lowercase()}").semantics { selected = firing == value },
                        colors = if (firing == value) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors()) {
                        Text(tr(if (value == ChallengeFiring.GEL) Res.string.gel else Res.string.ir))
                    } }
                }
                if (firing == ChallengeFiring.GEL) Text(tr(Res.string.challenge_gel_guide), fontSize = 14.sp)
            }
            val soundsLabel = tr(Res.string.challenge_sounds)
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("challenge-sounds")
                .toggleable(sounds, role = Role.Switch, onValueChange = onSoundsChange)
                .semantics { contentDescription = soundsLabel },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(soundsLabel, Modifier.weight(1f), fontSize = 14.sp)
                Switch(checked = sounds, onCheckedChange = null, modifier = Modifier.clearAndSetSemantics {})
            }
            }
            startError?.let { Text(it.resolve(), Modifier.testTag("challenge-start-error"), fontSize = 14.sp) }
            Button({
                startError = onStart(ChallengeConfiguration(mode, firing, if (mode == ChallengeMode.TARGET_PRACTICE) 5 else markerCount))
                if (startError == null) onChooseClose()
            },
                Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("challenge-start"), enabled = enabled,
                colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr(Res.string.challenge_start)) }
            Button(onChooseClose, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("challenge-setup-close")) { Text(tr(Res.string.cancel)) }
        }
    }
    WorkbenchDialog(show = result, onDismissRequest = { if (state.stopConfirmed) onDismiss() },
        title = tr(Res.string.challenge_finished)) {
        val configuration = state.configuration
        Column(Modifier.fillMaxWidth().heightIn(max = maxContentHeight)
            .testTag("challenge-result"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(challengeEndTitle(state), Modifier.testTag("challenge-result-reason").semantics { liveRegion = LiveRegionMode.Polite }, fontSize = 15.sp)
                if (!state.stopConfirmed) Text(tr(Res.string.challenge_cleanup_unknown), fontSize = 14.sp)
                else if (configuration != null) {
                    val scoreable = state.end == ChallengeEnd.TIME_EXPIRED && configuration.mode == ChallengeMode.TARGET_PRACTICE || state.end == ChallengeEnd.COMPLETED
                    if (scoreable) {
                        val value = if (configuration.mode == ChallengeMode.TARGET_PRACTICE) tr(Res.string.challenge_score, state.score.toString()) else challengeTime(state.elapsedMillis)
                        Text(value, Modifier.testTag("challenge-result-value"), fontSize = 22.sp)
                        if (state.checkpoints.isNotEmpty()) Text(state.checkpoints.joinToString("\n") {
                            tr(Res.string.challenge_checkpoint, (it.markerId - 10).toString(), challengeTime(it.elapsedMillis))
                        }, fontSize = 14.sp)
                        if (savedRound != state.roundId) {
                            TextField(name, { name = it.take(32) }, Modifier.fillMaxWidth().testTag("challenge-player-name"),
                                label = tr(Res.string.challenge_name), singleLine = true)
                            Button({
                                scores = scores + Score(configuration, name.trim().ifEmpty { null }, state.score, state.elapsedMillis)
                                savedRound = state.roundId
                            }, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("challenge-save-score")) { Text(tr(Res.string.challenge_save_score)) }
                        }
                        val ranking = scores.filter { it.configuration == configuration }.let { entries ->
                            if (configuration.mode == ChallengeMode.TARGET_PRACTICE) entries.sortedByDescending { it.points } else entries.sortedBy { it.millis }
                        }.take(5)
                        if (ranking.isNotEmpty()) {
                            Text(tr(Res.string.challenge_local_scores), fontSize = 15.sp)
                            ranking.forEachIndexed { index, score -> Text("${index + 1}. ${score.name ?: tr(Res.string.challenge_default_player)} · " +
                                if (configuration.mode == ChallengeMode.TARGET_PRACTICE) tr(Res.string.challenge_score, score.points.toString()) else challengeTime(score.millis),
                                Modifier.testTag("challenge-ranking-$index"), fontSize = 14.sp) }
                        }
                    }
                }
            }
            if (!state.stopConfirmed) {
                Button(onStop, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("challenge-retry-stop")) { Text(tr(Res.string.vision_retry_stop)) }
            } else if (configuration != null) {
                startError?.let { Text(it.resolve(), Modifier.testTag("challenge-replay-error"), fontSize = 14.sp) }
                Button({ startError = onStart(configuration) }, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("challenge-replay"),
                    enabled = enabled, colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr(Res.string.challenge_replay)) }
                Button(onDismiss, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("challenge-dismiss")) { Text(tr(Res.string.challenge_back_to_driving)) }
            }
        }
    }
}

internal fun challengeTime(millis: Long): String {
    val value = millis.coerceAtLeast(0)
    return "${value / 60_000}:" + ((value / 1000) % 60).toString().padStart(2, '0') + "." + ((value / 10) % 100).toString().padStart(2, '0')
}

private fun challengeStage(id: Int) = "${id / 10}–${id % 10}"

@Composable
private fun challengeModeTitle(mode: ChallengeMode) = tr(if (mode == ChallengeMode.TARGET_PRACTICE) Res.string.challenge_practice else Res.string.challenge_race)

@Composable
private fun challengeEndTitle(state: ChallengeState): String = tr(when {
    state.problem == ChallengeProblem.CALIBRATION_UNAVAILABLE -> Res.string.challenge_calibration_failed
    state.problem == ChallengeProblem.RULES_REJECTED -> Res.string.challenge_rules_rejected
    state.problem == ChallengeProblem.RULES_TIMED_OUT -> Res.string.challenge_rules_timed_out
    state.end == ChallengeEnd.COMPLETED -> Res.string.challenge_completed
    state.end == ChallengeEnd.TIME_EXPIRED -> Res.string.challenge_time_expired
    state.end == ChallengeEnd.INPUT_LOST -> Res.string.challenge_input_lost
    state.end == ChallengeEnd.VISION_FAILED -> Res.string.challenge_vision_failed
    else -> Res.string.challenge_cancelled
})
