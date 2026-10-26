package cn.elonzh.hanppie.ui.robot.remote

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.design.WorkbenchGlyph
import cn.elonzh.hanppie.ui.i18n.tr

/** Media actions belong to the interactive cockpit, above its full-window input surface. */
@Composable
internal fun RemoteMediaHud(controls: RemoteMediaController, enabled: Boolean, modifier: Modifier = Modifier) {
    val media by controls.state.collectAsState()
    Row(modifier.testTag("remote-media-hud"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HudIconButton(tr(if (media.playing) Res.string.stop_video else Res.string.start_video),
            if (media.playing) WorkbenchGlyph.VIDEO else WorkbenchGlyph.VIDEO_OFF,
            enabled && !media.saving, modifier = Modifier.testTag("remote-video-toggle"), action = controls::toggleVideo)
        HudIconButton(tr(if (media.sound) Res.string.mute else Res.string.listen),
            if (media.sound) WorkbenchGlyph.SPEAKER else WorkbenchGlyph.MUTED,
            enabled && !media.recording && !media.saving, selected = media.sound,
            modifier = Modifier.testTag("remote-audio-toggle"), action = controls::toggleRobotMicrophone)
        HudIconButton(tr(Res.string.take_photo), WorkbenchGlyph.CAMERA,
            enabled && media.videoReady && !media.saving,
            modifier = Modifier.testTag("remote-photo"), action = controls::takePhoto)
        HudIconButton(tr(if (media.recording) Res.string.stop_recording else Res.string.start_recording),
            if (media.recording) WorkbenchGlyph.STOP else WorkbenchGlyph.RECORD,
            enabled && !media.saving && (media.recording || media.playing && media.videoReady), selected = media.recording,
            modifier = Modifier.testTag("remote-record"), action = controls::toggleRecording)
    }
}
