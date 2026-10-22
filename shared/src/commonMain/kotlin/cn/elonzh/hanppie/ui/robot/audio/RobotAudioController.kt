package cn.elonzh.hanppie.ui.robot.audio

import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.robot.media.RobotAudioService
import cn.elonzh.hanppie.robot.media.RobotAudioSettings
import cn.elonzh.hanppie.robot.media.SpeakerLanguage
import cn.elonzh.hanppie.robot.session.RequestProblem
import cn.elonzh.hanppie.robot.session.RobotRequestException
import cn.elonzh.hanppie.ui.i18n.UiText
import cn.elonzh.hanppie.ui.i18n.uiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

internal data class RobotAudioState(
    val busy: Boolean = false,
    val settings: RobotAudioSettings? = null,
    val error: UiText? = null,
)

/** Owns only connection-scoped robot values. It never rewrites the app's saved preferences. */
internal class RobotAudioController(private val scope: CoroutineScope) {
    val state = MutableStateFlow(RobotAudioState())
    private var service: RobotAudioService? = null
    private var job: Job? = null
    private var revision = 0

    fun attach(service: RobotAudioService) { clear(); this.service = service }
    fun refresh() = work { it.read() }
    fun applyVolume(volume: Int) = work { it.applyVolume(volume) }
    fun setLanguage(language: SpeakerLanguage) = work { it.setLanguage(language) }

    private fun work(action: suspend (RobotAudioService) -> RobotAudioSettings) {
        val current = service ?: return
        if (job?.isActive == true) return
        val expectedRevision = revision
        state.value = RobotAudioState(busy = true)
        job = scope.launch {
            try {
                val settings = action(current)
                if (service === current && revision == expectedRevision) state.value = RobotAudioState(settings = settings)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (service === current && revision == expectedRevision) state.value = RobotAudioState(error = uiText(when ((error as? RobotRequestException)?.problem) {
                    RequestProblem.TIMED_OUT -> Res.string.robot_settings_timed_out
                    RequestProblem.REJECTED -> Res.string.robot_settings_rejected
                    RequestProblem.INVALID_RESPONSE -> Res.string.robot_settings_invalid_response
                    RequestProblem.NOT_APPLIED -> Res.string.robot_settings_not_applied
                    else -> Res.string.robot_settings_connection_failed
                }))
            }
        }
    }

    fun clear() {
        service = null
        invalidate()
    }

    fun invalidate() {
        revision++
        job?.cancel(); job = null
        state.value = RobotAudioState()
    }
}
