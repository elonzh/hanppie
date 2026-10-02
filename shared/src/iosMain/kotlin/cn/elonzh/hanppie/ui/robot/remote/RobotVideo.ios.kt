package cn.elonzh.hanppie.ui.robot.remote

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.UIKitView
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.app.ConsoleController
import cn.elonzh.hanppie.ui.design.HanppieDesignTokens
import cn.elonzh.hanppie.ui.design.WorkbenchGlyph
import cn.elonzh.hanppie.ui.i18n.tr
import cn.elonzh.hanppie.ui.platform.LocalIosPlatformServices
import cn.elonzh.hanppie.ui.platform.toPlatformData
import cn.elonzh.hanppie.ui.platform.toByteArray
import kotlinx.coroutines.launch
import org.jetbrains.skia.Image
import top.yukonga.miuix.kmp.basic.Text

@Composable internal actual fun RemoteOrientation(onBack: (() -> Unit)?) = Unit

@Composable internal actual fun RobotVideo(model: ConsoleController, controls: RemoteMediaController, modifier: Modifier) {
    val platform = LocalIosPlatformServices.current
    val scope = rememberCoroutineScope()
    val preview = LocalVideoPreview.current
    val streamEnabled = LocalVideoStreamEnabled.current
    val foreground by model.foregroundState.collectAsState()
    val inputEnabled = LocalVideoInputEnabled.current && foreground
    val state by model.state.collectAsState()
    val media by controls.state.collectAsState()
    val requests by controls.requests.collectAsState()
    val settings by model.mediaSettings.collectAsState()
    var playing by remember { mutableStateOf(true) }
    var sound by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    fun report(message: String?, error: String?) { status = error ?: message.orEmpty() }
    fun toggleRecording() {
        if (!state.connected || !media.videoReady) return
        val enabled = !recording
        platform.setRecording(enabled) { path, error -> scope.launch {
            recording = enabled && error == null
            controls.recording(recording)
            report(path?.let { tr(Res.string.video_saved_to_value, it) }, error)
        } }
    }
    LaunchedEffect(preview) { if (preview) { if (recording) toggleRecording(); sound = false; playing = true } }
    LaunchedEffect(requests.photo) {
        if (requests.photo > 0 && media.videoReady) platform.takePhoto { path, error -> scope.launch {
            report(path?.let { tr(Res.string.photo_saved_to_value, it) }, error)
        } }
    }
    LaunchedEffect(requests.recording) { if (requests.recording > 0) toggleRecording() }
    LaunchedEffect(requests.robotMicrophone) { if (requests.robotMicrophone > 0 && !recording) sound = !sound }
    DisposableEffect(state.connected, playing, streamEnabled, sound, settings.videoResolution) {
        controls.videoReady(false)
        var active = true
        if (state.connected && playing && streamEnabled) {
            platform.configureVideo({ data ->
                val png = data.toByteArray()
                scope.launch {
                if (active) {
                    if (png.isNotEmpty()) model.videoFrames.publish(state.connectedAddress,
                        Image.makeFromEncoded(png).toComposeImageBitmap())
                    controls.videoReady(true)
                }
            } }, { error -> scope.launch { if (active) { status = error; controls.videoReady(false) } } })
            val parser = cn.elonzh.hanppie.robot.media.AnnexB()
            val units = cn.elonzh.hanppie.robot.media.H264AccessUnits()
            model.videoSink = { bytes -> parser.accept(bytes).forEach { nal -> units.accept(nal)?.let { platform.videoPacket(it.toPlatformData()) } } }
            model.audioSink = if (sound) { bytes -> platform.audioPacket(bytes.toPlatformData()) } else null
            model.startMedia(sound)
        }
        onDispose {
            active = false
            controls.videoReady(false); controls.recording(false); recording = false
            model.stopMedia(); platform.stopVideo()
        }
    }
    Box(modifier) {
        UIKitView(factory = platform::videoView, modifier = Modifier.fillMaxSize())
        if (!media.videoReady) model.videoFrames.image(state.connectedAddress)?.let {
            Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
        if (!preview) Column(Modifier.align(Alignment.TopEnd).padding(HanppieDesignTokens.RemoteEdgePadding), horizontalAlignment = Alignment.End) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HudIconButton(if (playing) tr(Res.string.stop_video) else tr(Res.string.start_video), if (playing) WorkbenchGlyph.VIDEO else WorkbenchGlyph.VIDEO_OFF,
                    state.connected && inputEnabled) { playing = !playing }
                HudIconButton(if (sound) tr(Res.string.mute) else tr(Res.string.listen), if (sound) WorkbenchGlyph.SPEAKER else WorkbenchGlyph.MUTED,
                    state.connected && inputEnabled && !recording) { controls.toggleRobotMicrophone() }
                HudIconButton(tr(Res.string.take_photo), WorkbenchGlyph.CAMERA, media.videoReady && inputEnabled) { controls.takePhoto() }
                HudIconButton(if (recording) tr(Res.string.stop_recording) else tr(Res.string.start_recording), if (recording) WorkbenchGlyph.STOP else WorkbenchGlyph.RECORD,
                    media.videoReady && inputEnabled) { controls.toggleRecording() }
            }
            if (status.isNotBlank()) Text(status, Modifier.widthIn(max = 300.dp), color = Color.White, fontSize = 11.sp)
        }
    }
}
