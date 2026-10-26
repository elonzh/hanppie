package cn.elonzh.hanppie.ui.robot.remote

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import io.github.vinceglb.filekit.path
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.app.ConsoleController
import cn.elonzh.hanppie.ui.i18n.tr
import cn.elonzh.hanppie.ui.i18n.uiText
import cn.elonzh.hanppie.ui.robot.album.MediaContent
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable internal actual fun RobotVideo(model: ConsoleController, controls: RemoteMediaController, modifier: Modifier) {
    val preview = LocalVideoPreview.current
    val streamEnabled = LocalVideoStreamEnabled.current
    var playing by remember { mutableStateOf(true) }
    var sound by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    val latestFrame = remember { AtomicReference<ByteArray?>(null) }
    val recorder = remember { AtomicReference<DesktopVideoRecorder?>(null) }
    val uiScope = rememberCoroutineScope()
    val state by model.state.collectAsState()
    val frame = model.videoFrames.image(state.connectedAddress)
    val display by model.displaySettings.collectAsState()
    val mediaSettings by model.mediaSettings.collectAsState()
    val resolution = mediaSettings.videoResolution

    fun finishRecording() {
        val active = recorder.getAndSet(null) ?: return
        recording = false
        controls.recording(false)
        controls.state.value = controls.state.value.copy(saving = true)
        uiScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { active.finish() } }
            controls.state.value = controls.state.value.copy(saving = false)
            controls.notice(uiText(if (result.isSuccess) Res.string.media_video_saved else Res.string.media_record_failed))
        }
    }
    fun takePhoto() {
        val snapshot = latestFrame.get()?.copyOf() ?: return
        if (!state.connected) return
        if (controls.state.value.saving) return
        controls.state.value = controls.state.value.copy(saving = true)
        uiScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { saveDesktopPhoto(snapshot, resolution, Path.of(model.robotAlbum.captureDestination(MediaContent.JPEG).path)) } }
            controls.state.value = controls.state.value.copy(saving = false)
            controls.notice(uiText(if (result.isSuccess) Res.string.media_photo_saved else Res.string.media_photo_failed))
        }
    }
    fun toggleRecording() {
        if (!state.connected || !playing) return
        if (recording) finishRecording() else try {
            recorder.set(DesktopVideoRecorder(output = Path.of(model.robotAlbum.captureDestination(MediaContent.VIDEO).path), withAudio = sound, resolution = resolution))
            recording = true
            controls.recording(true)
        } catch (error: Exception) {
            controls.recording(false)
            controls.notice(uiText(Res.string.media_record_failed))
        }
    }
    LaunchedEffect(preview) {
        if (preview) { finishRecording(); sound = false; playing = true }
    }
    LaunchedEffect(controls) {
        var previous = controls.requests.value
        controls.requests.collect { request ->
            if (request.video != previous.video) playing = !playing
            if (request.photo != previous.photo) takePhoto()
            if (request.recording != previous.recording) toggleRecording()
            if (request.robotMicrophone != previous.robotMicrophone && !recording) { sound = !sound; if (sound) playing = true }
            previous = request
        }
    }
    SideEffect { controls.state.value = controls.state.value.copy(playing = playing, sound = sound) }

    LaunchedEffect(playing, state.connected) {
        if (!playing || !state.connected) finishRecording()
    }
    DisposableEffect(Unit) {
        onDispose {
            controls.recording(false)
            recorder.getAndSet(null)?.let { active -> thread(name = "hanppie-recorder-finish", isDaemon = true) { runCatching { active.finish() } } }
        }
    }
    DisposableEffect(playing, streamEnabled, state.connected, resolution) {
        controls.videoReady(false)
        if (!playing) { latestFrame.set(null); controls.discardPreview() }
        val decoderActive = java.util.concurrent.atomic.AtomicBoolean(true)
        var media: DesktopMedia? = null
        val lifecycleLock = Any()
        if (playing && streamEnabled && state.connected) thread(name = "hanppie-video-start", isDaemon = true) {
          try {
            val started = DesktopMedia(false, onVideo = { bytes ->
                if (!decoderActive.get()) return@DesktopMedia
                latestFrame.set(bytes)
                recorder.get()?.let { active -> runCatching { active.frame(bytes) }.onFailure { error ->
                    if (recorder.compareAndSet(active, null)) {
                        active.discard()
                        uiScope.launch {
                            recording = false
                            controls.recording(false)
                            controls.notice(uiText(Res.string.media_record_failed))
                        }
                    }
                } }
                uiScope.launch {
                    if (!decoderActive.get()) return@launch
                    model.videoFrames.publish(state.connectedAddress, org.jetbrains.skia.Image.makeRaster(
                        org.jetbrains.skia.ImageInfo(resolution.width, resolution.height, org.jetbrains.skia.ColorType.BGRA_8888, org.jetbrains.skia.ColorAlphaType.OPAQUE),
                        bytes, resolution.width * 4).toComposeImageBitmap())
                    controls.videoReady(true)
                }
            }, onStatus = { message -> if (message.isNotBlank()) uiScope.launch { if (decoderActive.get()) { playing = false; controls.notice(uiText(Res.string.media_video_failed)) } } }, resolution = resolution)
            val installed = synchronized(lifecycleLock) {
                if (decoderActive.get()) {
                    media = started
                    model.videoSink = started::video
                    model.startMedia(sound)
                    true
                } else false
            }
            if (!installed) started.close()
          } catch (e: Exception) {
              uiScope.launch { if (decoderActive.get()) { playing = false; controls.notice(uiText(Res.string.media_video_failed)) } }
          }
        }
        onDispose {
            val closing = synchronized(lifecycleLock) {
                decoderActive.set(false)
                controls.videoReady(false)
                media?.also { model.stopMedia() }
            }
            if (closing != null) thread(name = "hanppie-video-close", isDaemon = true) { closing.close() }
        }
    }
    DisposableEffect(playing, sound, streamEnabled, state.connected) {
        var audio: DesktopMedia? = null
        if (playing && streamEnabled && sound && state.connected) try {
            audio = DesktopMedia(true, {}, { message -> if (message.isNotBlank()) uiScope.launch { sound = false; controls.notice(uiText(Res.string.media_audio_failed)) } },
                pcmSink = { bytes, size -> recorder.get()?.audio(bytes, size) }, playAudio = true, videoEnabled = false)
            model.audioSink = audio::audio
            model.startMedia(true)
        } catch (e: Exception) { sound = false; controls.notice(uiText(Res.string.media_audio_failed)) }
        onDispose {
            model.audioSink = null
            if (playing && streamEnabled && state.connected) model.startMedia(false)
            audio?.let { closing -> thread(name = "hanppie-audio-close", isDaemon = true) { closing.close() } }
        }
    }
    Box(modifier.background(Color.Black)) {
        VideoPicture(frame?.width ?: resolution.width, frame?.height ?: resolution.height, display.fillVideo) {
            frame?.let { Image(it,tr(Res.string.robot_live_video),Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        }
    }
}
