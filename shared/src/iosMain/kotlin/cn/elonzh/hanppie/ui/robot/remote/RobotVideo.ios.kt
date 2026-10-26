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
    val state by model.state.collectAsState()
    val media by controls.state.collectAsState()
    val display by model.displaySettings.collectAsState()
    val settings by model.mediaSettings.collectAsState()
    var playing by remember { mutableStateOf(true) }
    var sound by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    fun takePhoto() {
        if (!media.videoReady) return
        platform.takePhoto { _, error -> scope.launch {
            controls.notice(cn.elonzh.hanppie.ui.i18n.uiText(if (error == null) Res.string.media_photo_saved else Res.string.media_photo_failed))
        } }
    }
    fun toggleRecording() {
        if (!state.connected || !media.videoReady) return
        val enabled = !recording
        platform.setRecording(enabled) { path, error -> scope.launch {
            recording = enabled && error == null
            controls.recording(recording)
            if (!enabled || error != null) controls.notice(cn.elonzh.hanppie.ui.i18n.uiText(if (error == null) Res.string.media_video_saved else Res.string.media_record_failed))
        } }
    }
    LaunchedEffect(preview) { if (preview) { if (recording) toggleRecording(); sound = false; playing = true } }
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
            } }, { error -> scope.launch { if (active) { playing = false; controls.notice(cn.elonzh.hanppie.ui.i18n.uiText(Res.string.media_video_failed)); controls.videoReady(false) } } })
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
        VideoPicture(settings.videoResolution.width, settings.videoResolution.height, display.fillVideo) {
            UIKitView(factory = platform::videoView, modifier = Modifier.fillMaxSize())
            if (!media.videoReady) model.videoFrames.image(state.connectedAddress)?.let {
                Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
        }
    }
}
