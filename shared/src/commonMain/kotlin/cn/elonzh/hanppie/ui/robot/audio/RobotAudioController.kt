package cn.elonzh.hanppie.ui.robot.audio

import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.robot.media.RobotAudioService
import cn.elonzh.hanppie.robot.media.RobotAudioSettings
import cn.elonzh.hanppie.robot.media.RobotSpeakerPlayback
import cn.elonzh.hanppie.robot.media.SpeakerLanguage
import cn.elonzh.hanppie.ui.i18n.UiText
import cn.elonzh.hanppie.ui.robot.device.robotRequestMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlin.concurrent.Volatile

internal sealed interface AudioSetting<out T> {
    data object Unread : AudioSetting<Nothing>
    data object Loading : AudioSetting<Nothing>
    data class Value<T>(val value: T) : AudioSetting<T>
    data class Failed(val message: UiText) : AudioSetting<Nothing>
}

internal data class RobotAudioState(
    val busy: Boolean = false,
    val settings: RobotAudioSettings? = null,
    val error: UiText? = null,
    val soundEnabled: AudioSetting<Boolean> = AudioSetting.Unread,
    val antiFeedback: AudioSetting<Boolean> = AudioSetting.Unread,
    val playback: RobotSpeakerPlayback? = null,
)

/** Owns connection-scoped device values and separate request results, never app playback preferences. */
internal class RobotAudioController(private val scope: CoroutineScope) {
    private class Binding(val service: RobotAudioService, val scope: CoroutineScope) {
        val settings = Mutex()
        val sound = Mutex()
        val feedback = Mutex()
    }
    val state = MutableStateFlow(RobotAudioState())
    @Volatile private var binding: Binding? = null

    fun attach(service: RobotAudioService) {
        clear()
        val current = Binding(service, CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job])))
        binding = current
        current.scope.launch { service.playback.collect { playback -> update(current) { it.copy(playback = playback) } } }
    }

    fun refresh() { readSettings { it.read() }; refreshSound() }
    fun applyVolume(volume: Int) = readSettings { it.applyVolume(volume) }
    fun setLanguage(language: SpeakerLanguage) = readSettings { it.setLanguage(language) }

    fun refreshSound() {
        val current = binding ?: return
        work(current, current.sound, { old, value -> old.copy(soundEnabled = value) }) { it.readSoundEnabled() }
    }

    fun setAntiFeedback(enabled: Boolean) {
        val current = binding ?: return
        work(current, current.feedback, { old, value -> old.copy(antiFeedback = value) }) { it.setAntiFeedback(enabled); enabled }
    }

    private fun readSettings(action: suspend (RobotAudioService) -> RobotAudioSettings) {
        val current = binding ?: return
        if (binding !== current || !current.scope.isActive || !current.settings.tryLock()) return
        update(current) { it.copy(busy = true, settings = null, error = null) }
        current.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val settings = action(current.service)
                update(current) { it.copy(busy = false, settings = settings) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                update(current) { it.copy(busy = false, error = robotRequestMessage(error, Res.string.robot_settings_connection_failed)) }
            } finally { current.settings.unlock() }
        }
    }

    private fun <T> work(current: Binding, mutex: Mutex, set: (RobotAudioState, AudioSetting<T>) -> RobotAudioState,
        action: suspend (RobotAudioService) -> T) {
        if (binding !== current || !current.scope.isActive || !mutex.tryLock()) return
        update(current) { set(it, AudioSetting.Loading) }
        current.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val value = action(current.service)
                update(current) { set(it, AudioSetting.Value(value)) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                update(current) { set(it, AudioSetting.Failed(robotRequestMessage(error, Res.string.robot_settings_connection_failed))) }
            } finally { mutex.unlock() }
        }
    }

    private fun update(current: Binding, transform: (RobotAudioState) -> RobotAudioState) {
        state.update { if (binding === current) transform(it) else it }
    }

    fun clear() {
        val old = binding
        binding = null
        state.value = RobotAudioState()
        old?.scope?.cancel()
    }

    fun invalidate() { binding?.service?.let(::attach) ?: clear() }
}
