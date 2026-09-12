package cn.elonzh.hanppie.ui

import cn.elonzh.hanppie.resources.*

import androidx.compose.runtime.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import top.yukonga.miuix.kmp.basic.*

@Composable internal actual fun RobotVideo(model: ConsoleModel, controls: RemoteMediaController, modifier: Modifier) {
    var playing by remember { mutableStateOf(true) }
    var sound by remember { mutableStateOf(false) }
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    var status by remember { mutableStateOf(tr(Res.string.video_off)) }
    var audioStatus by remember { mutableStateOf("") }
    var captureStatus by remember { mutableStateOf("") }
    var recording by remember { mutableStateOf(false) }
    val latestFrame = remember { AtomicReference<ByteArray?>(null) }
    val recorder = remember { AtomicReference<DesktopVideoRecorder?>(null) }
    val uiScope = rememberCoroutineScope()
    val state by model.state.collectAsState()
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
            val result = withContext(Dispatchers.IO) { runCatching { saveDesktopPhoto(snapshot) } }
            captureStatus = result.fold(
                { tr(Res.string.photo_saved_to_value, it) },
                { tr(Res.string.photo_failed_value, it.message ?: it.javaClass.simpleName) })
        }
    }
    fun toggleRecording() {
        if (!state.connected || !playing) return
        if (recording) finishRecording() else try {
            recorder.set(DesktopVideoRecorder(withAudio = sound))
            recording = true
            controls.recording(true)
            captureStatus = tr(Res.string.recording)
        } catch (error: Exception) {
            controls.recording(false)
            captureStatus = tr(Res.string.recording_failed_value, error.message ?: error.javaClass.simpleName)
        }
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
    DisposableEffect(playing, state.connected) {
        if (!playing) { frame = null; latestFrame.set(null) }
        status = if (!state.connected) "" else if (playing) tr(Res.string.waiting_for_video) else tr(Res.string.video_off)
        var media: DesktopMedia? = null
        if (playing && state.connected) try {
            media = DesktopMedia(false, onVideo = { bytes ->
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
                    frame = org.jetbrains.skia.Image.makeRaster(
                        org.jetbrains.skia.ImageInfo(1280,720,org.jetbrains.skia.ColorType.BGRA_8888,org.jetbrains.skia.ColorAlphaType.OPAQUE),
                        bytes,1280*4).toComposeImageBitmap()
                }
            }, onStatus = { message -> uiScope.launch { status = message } })
            model.videoSink = media::video
            model.startMedia(false)
        } catch (e: Exception) { status = e.message ?: tr(Res.string.could_not_start_media) }
        onDispose { if (media != null) { model.stopMedia(); media.close() } }
    }
    DisposableEffect(playing, sound, state.connected) {
        audioStatus = ""
        var audio: DesktopMedia? = null
        if (playing && sound && state.connected) try {
            audio = DesktopMedia(true, {}, { message -> uiScope.launch { audioStatus = message } },
                pcmSink = { bytes, size -> recorder.get()?.audio(bytes, size) }, playAudio = true, videoEnabled = false)
            model.audioSink = audio::audio
            model.startMedia(true)
        } catch (e: Exception) { audioStatus = e.message ?: tr(Res.string.could_not_start_audio) }
        onDispose { model.audioSink = null; audio?.close() }
    }
    Box(modifier.background(Color.Black)) {
        Box(Modifier.fillMaxSize()) {
            frame?.let { Image(it,tr(Res.string.robot_live_video),Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        }
        Column(Modifier.align(Alignment.TopEnd).padding(end = HanppieDesignTokens.RemoteEdgePadding,
            top = HanppieDesignTokens.RemoteEdgePadding), horizontalAlignment = Alignment.End) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HudIconButton(if(playing) tr(Res.string.stop_video) else tr(Res.string.start_video), if(playing) HanppieSymbol.Video else HanppieSymbol.VideoOff, state.connected) { playing = !playing }
                HudIconButton(if(sound) tr(Res.string.mute) else tr(Res.string.listen), if(sound) HanppieSymbol.Speaker else HanppieSymbol.Muted,
                    state.connected && !recording) { controls.toggleRobotMicrophone() }
                HudIconButton(tr(Res.string.take_photo), HanppieSymbol.Camera, state.connected && latestFrame.get() != null) { controls.takePhoto() }
                HudIconButton(if(recording) tr(Res.string.stop_recording) else tr(Res.string.start_recording), if(recording) HanppieSymbol.Stop else HanppieSymbol.Record,
                    state.connected && playing && (recording || latestFrame.get() != null)) { controls.toggleRecording() }
            }
            Text(status, Modifier.widthIn(max = 300.dp), color = Color.White, fontSize = 11.sp, maxLines = 1)
            if (sound) Text(audioStatus, Modifier.widthIn(max = 300.dp), color = Color.White, fontSize = 11.sp, maxLines = 1)
            if (captureStatus.isNotBlank()) Text(captureStatus, Modifier.widthIn(max = 300.dp), color = Color.White, fontSize = 11.sp, maxLines = 1)
        }
    }
}
