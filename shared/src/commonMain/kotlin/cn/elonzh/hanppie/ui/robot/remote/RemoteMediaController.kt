package cn.elonzh.hanppie.ui.robot.remote

import cn.elonzh.hanppie.ui.settings.RemoteLedSettings
import cn.elonzh.hanppie.ui.settings.RobotLedColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal val LocalVideoStreamEnabled = androidx.compose.runtime.staticCompositionLocalOf { true }
internal val LocalVideoInputEnabled = androidx.compose.runtime.staticCompositionLocalOf { true }

internal val LocalVideoPreview = androidx.compose.runtime.staticCompositionLocalOf { false }

internal val LocalVideoHudAlpha = androidx.compose.runtime.staticCompositionLocalOf { 1f }

internal data class RemoteMediaRequests(
    val photo: Long = 0,
    val recording: Long = 0,
    val robotMicrophone: Long = 0,
)

internal data class RemoteMediaState(val recording: Boolean = false, val videoReady: Boolean = false, val videoFrameAtEpochMillis: Long = 0)

/** One command path for HUD buttons and hardware-key shortcuts. */
internal class RemoteMediaController {
    val requests = MutableStateFlow(RemoteMediaRequests())
    val state = MutableStateFlow(RemoteMediaState())
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
