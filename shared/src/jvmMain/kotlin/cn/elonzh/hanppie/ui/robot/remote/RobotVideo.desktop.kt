package cn.elonzh.hanppie.ui.robot.remote

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.app.ConsoleController
import cn.elonzh.hanppie.ui.design.HanppieDesignTokens
import cn.elonzh.hanppie.ui.design.WorkbenchGlyph
import cn.elonzh.hanppie.ui.i18n.tr
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*

@Composable internal actual fun RobotVideo(model: ConsoleController, controls: RemoteMediaController, modifier: Modifier) {
    val preview = LocalVideoPreview.current
    val streamEnabled = LocalVideoStreamEnabled.current
    val foreground by model.foregroundState.collectAsState()
    val inputEnabled = LocalVideoInputEnabled.current && foreground
    val hudAlpha = LocalVideoHudAlpha.current
    var playing by remember { mutableStateOf(true) }
    var sound by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(tr(Res.string.video_off)) }
    var audioStatus by remember { mutableStateOf("") }
    var captureStatus by remember { mutableStateOf("") }
    var recording by remember { mutableStateOf(false) }
    val latestFrame = remember { AtomicReference<ByteArray?>(null) }
    val recorder = remember { AtomicReference<DesktopVideoRecorder?>(null) }
    val uiScope = rememberCoroutineScope()
    val state by model.state.collectAsState()
    val frame = model.videoFrames.image(state.connectedAddress)
    val mediaSettings by model.mediaSettings.collectAsState()
    val resolution = mediaSettings.videoResolution
    val requests by controls.requests.collectAsState()

    fun finishRecording() {
        val active = recorder.getAndSet(null) ?: return
        recording = false
        controls.recording(false)
        captureStatus = tr(Res.string.saving_recording)
        uiScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { active.finish() } }
            captureStatus = result.fold(
                { tr(Res.string.video_saved_to_value, it) },
                { tr(Res.string.recording_failed_value, it.message ?: it.javaClass.simpleName) })
        }
    }
    fun takePhoto() {
        val snapshot = latestFrame.get()?.copyOf() ?: return
        if (!state.connected) return
        captureStatus = tr(Res.string.saving_photo)
        uiScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { saveDesktopPhoto(snapshot, resolution) } }
            captureStatus = result.fold(
                { tr(Res.string.photo_saved_to_value, it) },
                { tr(Res.string.photo_failed_value, it.message ?: it.javaClass.simpleName) })
        }
    }
    fun toggleRecording() {
        if (!state.connected || !playing) return
        if (recording) finishRecording() else try {
            recorder.set(DesktopVideoRecorder(withAudio = sound, resolution = resolution))
            recording = true
            controls.recording(true)
            captureStatus = tr(Res.string.recording)
        } catch (error: Exception) {
            controls.recording(false)
            captureStatus = tr(Res.string.recording_failed_value, error.message ?: error.javaClass.simpleName)
        }
    }
    LaunchedEffect(preview) {
        if (preview) { finishRecording(); sound = false; playing = true }
    }
    LaunchedEffect(requests.photo) { if (requests.photo > 0) takePhoto() }
    LaunchedEffect(requests.recording) { if (requests.recording > 0) toggleRecording() }
    LaunchedEffect(requests.robotMicrophone) {
        if (requests.robotMicrophone > 0 && !recording) { sound = !sound; if (sound) playing = true }
    }

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
        status = if (!state.connected || playing) "" else tr(Res.string.video_off)
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
                            captureStatus = tr(Res.string.recording_failed_value, error.message ?: error.javaClass.simpleName)
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
            }, onStatus = { message -> uiScope.launch { status = message } }, resolution = resolution)
            val installed = synchronized(lifecycleLock) {
                if (decoderActive.get()) {
                    media = started
                    model.videoSink = started::video
                    model.startMedia(false)
                    true
                } else false
            }
            if (!installed) started.close()
          } catch (e: Exception) {
              uiScope.launch { if (decoderActive.get()) status = e.message ?: tr(Res.string.could_not_start_media) }
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
        audioStatus = ""
        var audio: DesktopMedia? = null
        if (playing && streamEnabled && sound && state.connected) try {
            audio = DesktopMedia(true, {}, { message -> uiScope.launch { audioStatus = message } },
                pcmSink = { bytes, size -> recorder.get()?.audio(bytes, size) }, playAudio = true, videoEnabled = false)
            model.audioSink = audio::audio
            model.startMedia(true)
        } catch (e: Exception) { audioStatus = e.message ?: tr(Res.string.could_not_start_audio) }
        onDispose { model.audioSink = null; audio?.close() }
    }
    Box(modifier.background(Color.Black)) {
        Box(Modifier.fillMaxSize()) {
            frame?.let { Image(it,tr(Res.string.robot_live_video),Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
        if (!preview) Column(Modifier.align(Alignment.TopEnd).graphicsLayer { alpha = hudAlpha }.padding(end = HanppieDesignTokens.RemoteEdgePadding,
            top = HanppieDesignTokens.RemoteEdgePadding), horizontalAlignment = Alignment.End) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HudIconButton(if(playing) tr(Res.string.stop_video) else tr(Res.string.start_video), if(playing) WorkbenchGlyph.VIDEO else WorkbenchGlyph.VIDEO_OFF, state.connected && inputEnabled) { playing = !playing }
                HudIconButton(if(sound) tr(Res.string.mute) else tr(Res.string.listen), if(sound) WorkbenchGlyph.SPEAKER else WorkbenchGlyph.MUTED,
                    state.connected && inputEnabled && !recording) { controls.toggleRobotMicrophone() }
                HudIconButton(tr(Res.string.take_photo), WorkbenchGlyph.CAMERA, state.connected && inputEnabled && latestFrame.get() != null) { controls.takePhoto() }
                HudIconButton(if(recording) tr(Res.string.stop_recording) else tr(Res.string.start_recording), if(recording) WorkbenchGlyph.STOP else WorkbenchGlyph.RECORD,
                    state.connected && inputEnabled && playing && (recording || latestFrame.get() != null)) { controls.toggleRecording() }
            }
            if (status.isNotBlank()) Text(status, Modifier.widthIn(max = 300.dp), color = Color.White, fontSize = 11.sp, maxLines = 1)
            if (sound) Text(audioStatus, Modifier.widthIn(max = 300.dp), color = Color.White, fontSize = 11.sp, maxLines = 1)
            if (captureStatus.isNotBlank()) Text(captureStatus, Modifier.widthIn(max = 300.dp), color = Color.White, fontSize = 11.sp, maxLines = 1)
        }
    }
}
