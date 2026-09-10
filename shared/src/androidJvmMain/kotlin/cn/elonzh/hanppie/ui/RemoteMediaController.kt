package cn.elonzh.hanppie.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal data class RemoteMediaRequests(
    val photo: Long = 0,
    val recording: Long = 0,
    val robotMicrophone: Long = 0,
)

/** One command path for HUD buttons and hardware-key shortcuts. */
internal class RemoteMediaController {
    val requests = MutableStateFlow(RemoteMediaRequests())
    fun takePhoto() = requests.update { it.copy(photo = it.photo + 1) }
    fun toggleRecording() = requests.update { it.copy(recording = it.recording + 1) }
    fun toggleRobotMicrophone() = requests.update { it.copy(robotMicrophone = it.robotMicrophone + 1) }
}
