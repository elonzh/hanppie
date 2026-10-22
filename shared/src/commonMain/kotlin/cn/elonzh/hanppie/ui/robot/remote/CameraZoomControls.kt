package cn.elonzh.hanppie.ui.robot.remote

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.design.HanppieDesignTokens
import cn.elonzh.hanppie.ui.i18n.tr
import top.yukonga.miuix.kmp.basic.Text

@Composable
internal fun CameraZoomControls(state: CameraZoomState, enabled: Boolean, modifier: Modifier = Modifier, onZoom: (Int) -> Unit) {
    val target = if ((state.hundredths ?: 100) > 100) 100 else 400
    val action = tr(if (target == 100) Res.string.camera_zoom_to_normal else Res.string.camera_zoom_to_four)
    Column(modifier.widthIn(max = 200.dp)
        .background(HanppieDesignTokens.RemoteHudSurface.copy(alpha = HanppieDesignTokens.RemoteHudAlpha), RoundedCornerShape(12.dp))
        .padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HudTextButton(when {
                state.busy -> tr(Res.string.camera_zoom_changing)
                state.hundredths == null -> tr(Res.string.camera_zoom_waiting)
                else -> tr(Res.string.camera_zoom_value, state.hundredths / 100f)
            }, enabled && !state.busy && state.hundredths != null, "camera-zoom", description = action) { onZoom(target) }
        state.error?.let { Text(it.resolve(), Modifier.testTag("camera-zoom-error"),
            fontSize = 12.sp, color = HanppieDesignTokens.RemoteHudContent) }
    }
}
