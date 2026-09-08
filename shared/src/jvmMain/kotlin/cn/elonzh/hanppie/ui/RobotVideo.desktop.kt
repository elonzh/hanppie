package cn.elonzh.hanppie.ui

import cn.elonzh.hanppie.resources.*

import androidx.compose.runtime.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.*

@Composable internal actual fun RobotVideo(model: ConsoleModel, modifier: Modifier) {
    var playing by remember { mutableStateOf(false) }
    var sound by remember { mutableStateOf(false) }
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    var status by remember { mutableStateOf(tr(Res.string.video_off)) }
    var audioStatus by remember { mutableStateOf("") }
    val state by model.state.collectAsState()
    DisposableEffect(playing, state.connected) {
        frame = null; status = if (playing) tr(Res.string.waiting_for_video) else tr(Res.string.video_off)
        var media: DesktopMedia? = null
        if (playing && state.connected) try {
            media = DesktopMedia(false, onVideo = { bytes ->
                frame = org.jetbrains.skia.Image.makeRaster(
                    org.jetbrains.skia.ImageInfo(1280,720,org.jetbrains.skia.ColorType.BGRA_8888,org.jetbrains.skia.ColorAlphaType.OPAQUE),
                    bytes,1280*4).toComposeImageBitmap()
            }, onStatus = { status = it })
            model.videoSink = media::video
            model.startMedia(false)
        } catch (e: Exception) { status = e.message ?: tr(Res.string.could_not_start_media) }
        onDispose { if (media != null) { model.stopMedia(); media.close() } }
    }
    DisposableEffect(playing, sound, state.connected) {
        audioStatus = ""
        var audio: DesktopMedia? = null
        if (playing && sound && state.connected) try {
            audio = DesktopMedia(true, {}, { audioStatus = it }, videoEnabled = false)
            model.audioSink = audio::audio
            model.startMedia(true)
        } catch (e: Exception) { audioStatus = e.message ?: tr(Res.string.could_not_start_audio) }
        onDispose { model.audioSink = null; audio?.close() }
    }
    Box(modifier.background(Color.Black)) {
        Box(Modifier.fillMaxSize()) {
            frame?.let { Image(it,tr(Res.string.robot_live_video),Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        }
        Column(Modifier.padding(start=12.dp,top=64.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HudButton(if(playing) tr(Res.string.stop_video) else tr(Res.string.start_video),if(playing) tr(Res.string.stop_video) else tr(Res.string.start_video),state.connected) { playing = !playing }
            HudButton(if(sound) tr(Res.string.mute) else tr(Res.string.listen),if(sound) tr(Res.string.mute) else tr(Res.string.listen),state.connected) { sound = !sound; if(sound) playing=true }
        }
        Text(status, color=Color.White)
        if (sound) Text(audioStatus, color=Color.White)
        }
    }
}
