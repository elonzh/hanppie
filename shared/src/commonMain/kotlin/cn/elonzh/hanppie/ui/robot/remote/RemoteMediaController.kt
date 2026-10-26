package cn.elonzh.hanppie.ui.robot.remote

import cn.elonzh.hanppie.ui.i18n.UiText
import cn.elonzh.hanppie.ui.settings.RemoteLedSettings
import cn.elonzh.hanppie.ui.settings.RobotLedColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal val LocalVideoStreamEnabled = androidx.compose.runtime.staticCompositionLocalOf { true }

internal val LocalVideoPreview = androidx.compose.runtime.staticCompositionLocalOf { false }


internal data class RemoteMediaRequests(
    val video: Long = 0,
    val photo: Long = 0,
    val recording: Long = 0,
    val robotMicrophone: Long = 0,
)

internal data class RemoteMediaState(val recording: Boolean = false, val playing: Boolean = true,
    val sound: Boolean = false, val saving: Boolean = false, val notice: UiText? = null,
    val videoReady: Boolean = false, val videoFrameAtEpochMillis: Long = 0)

/** One command path for HUD buttons and hardware-key shortcuts. */
internal class RemoteMediaController {
    val requests = MutableStateFlow(RemoteMediaRequests())
    val state = MutableStateFlow(RemoteMediaState())
    fun toggleVideo() = requests.update { it.copy(video = it.video + 1) }
    fun notice(message: UiText?) = state.update { it.copy(notice = message) }
    fun takePhoto() = requests.update { it.copy(photo = it.photo + 1) }
    fun toggleRecording() = requests.update { it.copy(recording = it.recording + 1) }
    fun toggleRobotMicrophone() = requests.update { it.copy(robotMicrophone = it.robotMicrophone + 1) }
    fun videoReady(ready: Boolean) = state.update { it.copy(videoReady = ready, videoFrameAtEpochMillis = if (ready) kotlin.time.Clock.System.now().toEpochMilliseconds() else it.videoFrameAtEpochMillis) }
    fun discardPreview() = state.update { it.copy(videoReady = false, videoFrameAtEpochMillis = 0) }
    fun recording(active: Boolean) = state.update { it.copy(recording = active) }
}

internal enum class RemoteLedState { STANDBY, ACTIVE, RECORDING, TALKING }

internal fun remoteLedState(enabled: Boolean, recording: Boolean, talking: Boolean): RemoteLedState = when {
    talking -> RemoteLedState.TALKING
    recording -> RemoteLedState.RECORDING
    enabled -> RemoteLedState.ACTIVE
    else -> RemoteLedState.STANDBY
}

internal fun RemoteLedSettings.color(state: RemoteLedState): RobotLedColor = when (state) {
    RemoteLedState.STANDBY -> standby
    RemoteLedState.ACTIVE -> active
    RemoteLedState.RECORDING -> recording
    RemoteLedState.TALKING -> talking
}
