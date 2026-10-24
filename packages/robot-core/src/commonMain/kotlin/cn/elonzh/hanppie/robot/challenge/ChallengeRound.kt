package cn.elonzh.hanppie.robot.challenge

import cn.elonzh.hanppie.robot.display.SightPoint
import cn.elonzh.hanppie.robot.vision.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ChallengeMode { TARGET_PRACTICE, TARGET_RACE }
enum class ChallengeFiring { INFRARED, GEL }
enum class ChallengePhase { IDLE, PREPARING, COUNTDOWN, PLAYING, WAVE_CLEAR, LEVEL_CHANGE, STOPPING, RESULT, CLOSED }
enum class ChallengeCountdownStep(val number: Int? = null) { THREE(3), TWO(2), ONE(1), START }
enum class ChallengeEnd { TIME_EXPIRED, COMPLETED, CANCELLED, INPUT_LOST, VISION_FAILED, PREPARATION_FAILED }
enum class ChallengeProblem { CALIBRATION_UNAVAILABLE, RULES_REJECTED, RULES_TIMED_OUT, RESTORE_UNCONFIRMED }
data class ChallengeConfiguration(val mode: ChallengeMode, val firing: ChallengeFiring = ChallengeFiring.INFRARED,
    val markerCount: Int = 5) {
    init {
        require(markerCount in 1..5)
        require(mode != ChallengeMode.TARGET_PRACTICE || firing == ChallengeFiring.INFRARED && markerCount == 5)
    }
}
data class ChallengeTarget(val markerId: Int, val completed: Boolean = false)
data class ChallengeCheckpoint(val markerId: Int, val elapsedMillis: Long)
/** A host-rule impact, distinct from whether that marker earns this round's score. */
data class ChallengeHit(val sequence: Long, val markerId: Int, val rectangle: VisionRect?, val credited: Boolean,
    val scoreDelta: Int = 0)
data class ChallengeState(val phase: ChallengePhase = ChallengePhase.IDLE, val configuration: ChallengeConfiguration? = null,
    val stage: Int = 11, val wave: Int = 1, val score: Int = 0, val targets: List<ChallengeTarget> = emptyList(),
    val elapsedMillis: Long = 0, val remainingMillis: Long = 0, val checkpoints: List<ChallengeCheckpoint> = emptyList(),
    val lastHitId: Int? = null, val end: ChallengeEnd? = null, val stopConfirmed: Boolean = true,
    val sight: SightPoint? = null, val problem: ChallengeProblem? = null, val roundId: Long = 0,
    val aimedMarkerId: Int? = null, val hit: ChallengeHit? = null, val hitAgeMillis: Long? = null) {
    val ownsRound: Boolean get() = phase !in listOf(ChallengePhase.IDLE, ChallengePhase.CLOSED)
    val firing: ChallengeFiring? get() = configuration?.firing.takeIf { ownsRound }
    val blocksControl: Boolean get() = ownsRound && phase !in listOf(ChallengePhase.PLAYING, ChallengePhase.WAVE_CLEAR, ChallengePhase.LEVEL_CHANGE)
    /** Fixed ReadyCountDownAC: 5 init frames, then 3/2/1 at 0/60/120 and Start at 180 (60 fps).
     * The end event is at frame 230; boundaries round up to the host's millisecond clock.
     */
    val countdownElapsedMillis: Long? get() = if (phase == ChallengePhase.COUNTDOWN)
        (3_833 - remainingMillis).takeIf { it >= 0 } else null
    val countdownStep: ChallengeCountdownStep? get() = countdownElapsedMillis?.let { elapsed -> when {
        elapsed < 1_000 -> ChallengeCountdownStep.THREE
        elapsed < 2_000 -> ChallengeCountdownStep.TWO
        elapsed < 3_000 -> ChallengeCountdownStep.ONE
        else -> ChallengeCountdownStep.START
    } }
}

internal const val challengeReadyMillis = 3_917L

internal data class PracticeStage(val id: Int, val timeMillis: Long, val count: Int)
internal val practiceStages = listOf(
    PracticeStage(11, 10_000, 1), PracticeStage(12, 7_000, 1), PracticeStage(13, 4_000, 1),
    PracticeStage(21, 10_000, 2), PracticeStage(22, 7_000, 2), PracticeStage(23, 4_000, 2),
    PracticeStage(31, 10_000, 3), PracticeStage(32, 8_000, 3), PracticeStage(33, 5_000, 3),
    PracticeStage(41, 13_000, 4), PracticeStage(42, 10_000, 4), PracticeStage(43, 7_000, 4),
    PracticeStage(51, 14_000, 5), PracticeStage(52, 11_000, 5), PracticeStage(53, 8_000, 5),
)

/** Fixed App challenge rules. The session serializes calls and owns recognition, motion and cleanup.
 * A device fire-status notification is an input to the host's game rule, not proof of physical impact.
 */
internal class ChallengeRound(private val nowMillis: () -> Long, private val stop: () -> Unit,
    private val chooseTargets: (Int) -> List<Int> = { count -> (11..15).shuffled().take(count) }) {
    private val mutable = MutableStateFlow(ChallengeState())
    val state = mutable.asStateFlow()
    private var stageIndex = 0
    private var deadline = 0L
    private var startedAt = 0L
    private var lastHitAt = 0L
    private var leaving = false
    private var roundId = 0L
    private var hitSequence = 0L
    private var feedbackAt = 0L
    private data class GelWindow(val readyAt: Long, var expiresAt: Long, val rectangle: VisionRect, var absentAt: Long? = null)
    private val gel = mutableMapOf<Int, GelWindow>()

    fun prepare(configuration: ChallengeConfiguration) {
        check(state.value.phase != ChallengePhase.CLOSED &&
            (!state.value.ownsRound || state.value.phase == ChallengePhase.RESULT && state.value.stopConfirmed))
        stageIndex = 0; startedAt = 0; deadline = 0; leaving = false; gel.clear()
        mutable.value = ChallengeState(phase = ChallengePhase.PREPARING, configuration = configuration, stopConfirmed = false,
            roundId = ++roundId)
    }

    fun tick(operatorPresent: Boolean, vision: VisionState, environment: ChallengeEnvironment) {
        val now = nowMillis()
        if (state.value.hit != null) {
            val age = (now - feedbackAt).coerceAtLeast(0)
            mutable.value = state.value.copy(hit = state.value.hit.takeIf { age < 1_000 },
                hitAgeMillis = age.takeIf { it < 1_000 })
        }
        val current = state.value
        if (current.phase in listOf(ChallengePhase.IDLE, ChallengePhase.CLOSED)) return
        if (current.phase == ChallengePhase.STOPPING || current.phase == ChallengePhase.RESULT) {
            val confirmed = !vision.ownsPipeline && environment.cleanupConfirmed
            if (!current.stopConfirmed && (confirmed || vision.problem == VisionProblem.STOP_UNCONFIRMED ||
                    environment.problem == ChallengeProblem.RESTORE_UNCONFIRMED))
                mutable.value = current.copy(phase = ChallengePhase.RESULT, stopConfirmed = confirmed,
                    problem = environment.problem ?: current.problem)
            if (leaving && state.value.phase == ChallengePhase.RESULT && state.value.stopConfirmed) dismissResult()
            return
        }
        if (!operatorPresent) { finish(ChallengeEnd.INPUT_LOST); return }
        if (environment.problem != null) {
            mutable.value = state.value.copy(problem = environment.problem)
            finish(ChallengeEnd.PREPARATION_FAILED); return
        }
        if (!environment.ready) return
        if (current.phase == ChallengePhase.PREPARING) mutable.value = current.copy(sight = environment.sight)
        if (vision.phase == VisionPhase.ERROR || vision.mode != VisionMode.MARKERS || !vision.ownsPipeline) {
            finish(ChallengeEnd.VISION_FAILED); return
        }
        val fresh = vision.phase == VisionPhase.ACTIVE && vision.report?.type == VisionMode.MARKERS.reportType &&
            vision.reportAgeMillis?.let { it in 0..749 } == true
        if (current.phase == ChallengePhase.PREPARING) {
            if (fresh) {
                deadline = now + challengeReadyMillis
                mutable.value = state.value.copy(phase = ChallengePhase.COUNTDOWN, remainingMillis = challengeReadyMillis)
            }
            return
        }
        if (!fresh) { finish(ChallengeEnd.VISION_FAILED); return }
        if (current.lastHitId != null && now - lastHitAt >= 500) mutable.value = current.copy(lastHitId = null)
        when (state.value.phase) {
            ChallengePhase.COUNTDOWN -> if (now >= deadline) {
                startedAt = now
                playWave(now)
            } else mutable.value = state.value.copy(remainingMillis = deadline - now)
            ChallengePhase.PLAYING -> {
                mutable.value = state.value.copy(elapsedMillis = (now - startedAt).coerceAtLeast(0),
                    remainingMillis = if (isPractice()) (deadline - now).coerceAtLeast(0) else 0)
                // Expiry wins over a late report, even if the UI/ticker was stalled.
                if (isPractice() && now >= deadline) { finish(ChallengeEnd.TIME_EXPIRED); return }
                checkGel(now, vision)
            }
            ChallengePhase.WAVE_CLEAR -> if (now >= deadline) {
                if (state.value.wave < 3) {
                    mutable.value = state.value.copy(wave = state.value.wave + 1)
                    playWave(now)
                } else {
                    stageIndex = minOf(stageIndex + 1, practiceStages.lastIndex)
                    deadline = now + 3_000
                    mutable.value = state.value.copy(phase = ChallengePhase.LEVEL_CHANGE,
                        stage = practiceStages[stageIndex].id, wave = 1, remainingMillis = 3_000)
                }
            } else mutable.value = state.value.copy(remainingMillis = deadline - now)
            ChallengePhase.LEVEL_CHANGE -> if (now >= deadline) playWave(now)
                else mutable.value = state.value.copy(remainingMillis = deadline - now)
            else -> Unit
        }
        mutable.value = state.value.copy(aimedMarkerId = if (state.value.phase == ChallengePhase.PLAYING &&
            state.value.configuration?.firing == ChallengeFiring.INFRARED)
            state.value.sight?.let { infraredTarget(vision, it, now)?.markerId } else null)
    }

    /** Called only for an admitted device notification; never from a command-send callback. */
    fun fireStatus(vision: VisionState, sight: SightPoint) {
        val now = nowMillis()
        if (state.value.phase != ChallengePhase.PLAYING || vision.phase != VisionPhase.ACTIVE ||
            vision.mode != VisionMode.MARKERS || vision.report?.type != VisionMode.MARKERS.reportType ||
            vision.reportAgeMillis?.let { it in 0..749 } != true) return
        if (isPractice() && now >= deadline) { finish(ChallengeEnd.TIME_EXPIRED); return }
        val rectangles = vision.rectangles
        val target = if (state.value.configuration?.firing == ChallengeFiring.INFRARED)
            infraredTarget(vision, sight, now)
        else rectangles.minByOrNull { (it.x - sight.x / 10000f) * (it.x - sight.x / 10000f) +
            (it.y - (1f - sight.y / 10000f)) * (it.y - (1f - sight.y / 10000f)) }
        val id = target?.markerId ?: return
        if (state.value.configuration?.firing == ChallengeFiring.INFRARED) hit(id, now, target)
        else {
            if (state.value.targets.none { it.markerId == id && !it.completed }) return
            // At most one overlapping observation window per physical marker; a target scores once.
            val pending = gel[id]
            if (pending == null || pending.expiresAt <= now) gel[id] = GelWindow(now + 100, now + 2_600, target)
            else { pending.expiresAt = now + 2_600; pending.absentAt = null }
        }
    }

    private fun infraredTarget(vision: VisionState, sight: SightPoint, now: Long): VisionRect? {
        val x = sight.x / 10000f; val y = 1f - sight.y / 10000f
        return vision.rectangles.firstOrNull { (it.markerId != state.value.lastHitId || now - lastHitAt >= 500) &&
            x > it.x - it.width / 2 && x < it.x + it.width / 2 &&
            y > it.y - it.height / 2 && y < it.y + it.height / 2 }
    }

    private fun checkGel(now: Long, vision: VisionState) {
        for ((id, window) in gel.toMap()) {
            if (now >= window.expiresAt) { gel.remove(id); continue }
            if (now < window.readyAt) continue
            if (vision.rectangles.any { it.markerId == id }) window.absentAt = null
            else {
                if (window.absentAt == null) window.absentAt = now
                if (now - checkNotNull(window.absentAt) > 300) {
                    gel.remove(id); hit(id, now, window.rectangle)
                    if (state.value.phase != ChallengePhase.PLAYING) return
                }
            }
        }
    }

    private fun playWave(now: Long) {
        gel.clear()
        val stage = practiceStages[stageIndex]
        val ids = if (isPractice()) chooseTargets(stage.count) else (11..10 + checkNotNull(state.value.configuration).markerCount).toList()
        check(ids.distinct().size == ids.size && ids.all { it in 11..15 } &&
            ids.size == if (isPractice()) stage.count else checkNotNull(state.value.configuration).markerCount)
        if (isPractice()) deadline = now + stage.timeMillis
        mutable.value = state.value.copy(phase = ChallengePhase.PLAYING, stage = stage.id,
            targets = ids.map(::ChallengeTarget), lastHitId = null, remainingMillis = if (isPractice()) stage.timeMillis else 0)
    }

    private fun hit(id: Int, now: Long, rectangle: VisionRect? = null) {
        val credited = state.value.targets.any { it.markerId == id && !it.completed }
        lastHitAt = now
        feedbackAt = now
        mutable.value = state.value.copy(lastHitId = id, aimedMarkerId = null, hitAgeMillis = 0,
            hit = ChallengeHit(++hitSequence, id, rectangle, credited, if (credited && isPractice()) 100 else 0))
        if (!credited) return
        val current = state.value
        val elapsed = (now - startedAt).coerceAtLeast(0)
        val targets = current.targets.map { if (it.markerId == id) it.copy(completed = true) else it }
        mutable.value = current.copy(targets = targets, lastHitId = id, elapsedMillis = elapsed,
            score = if (isPractice()) current.score + 100 else current.score,
            checkpoints = if (isPractice()) current.checkpoints else current.checkpoints + ChallengeCheckpoint(id, elapsed))
        if (targets.all { it.completed }) {
            if (isPractice()) {
                deadline = now + 1_000
                mutable.value = state.value.copy(phase = ChallengePhase.WAVE_CLEAR, remainingMillis = 1_000)
            } else finish(ChallengeEnd.COMPLETED)
        }
    }

    fun cancel() {
        if (state.value.phase == ChallengePhase.RESULT) { if (!state.value.stopConfirmed) stop() }
        else if (state.value.phase == ChallengePhase.STOPPING) stop()
        else if (state.value.ownsRound) finish(ChallengeEnd.CANCELLED)
    }
    fun dismissResult() {
        check(state.value.phase == ChallengePhase.RESULT && state.value.stopConfirmed)
        mutable.value = ChallengeState()
    }
    fun leave() {
        leaving = true
        cancel()
        if (state.value.phase == ChallengePhase.RESULT && state.value.stopConfirmed) dismissResult()
    }
    private fun finish(reason: ChallengeEnd) {
        gel.clear()
        mutable.value = state.value.copy(phase = ChallengePhase.STOPPING, end = reason, remainingMillis = 0, aimedMarkerId = null,
            elapsedMillis = if (startedAt == 0L) 0 else (nowMillis() - startedAt).coerceAtLeast(0), lastHitId = null)
        stop()
    }
    fun close() { gel.clear(); mutable.value = state.value.copy(phase = ChallengePhase.CLOSED) }
    private fun isPractice() = state.value.configuration?.mode == ChallengeMode.TARGET_PRACTICE
}
