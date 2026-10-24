package cn.elonzh.hanppie.ui.robot.remote

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.design.HanppieDesignTokens
import cn.elonzh.hanppie.ui.design.WorkbenchDialog
import cn.elonzh.hanppie.ui.design.WorkbenchGlyph
import cn.elonzh.hanppie.ui.i18n.tr
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Button

@Composable
internal fun CameraZoomControls(state: CameraZoomState, enabled: Boolean, modifier: Modifier = Modifier, onZoom: (Int) -> Unit) {
    val target = if ((state.hundredths ?: 100) > 100) 100 else 400
    val action = tr(if (target == 100) Res.string.camera_zoom_to_normal else Res.string.camera_zoom_to_four)
    val error = state.error
    var details by remember(error) { mutableStateOf(false) }
    Row(modifier.widthIn(max = HanppieDesignTokens.RemoteTaskSideMaxWidth)
        .background(HanppieDesignTokens.RemoteHudSurface.copy(alpha = HanppieDesignTokens.RemoteHudAlpha), RoundedCornerShape(12.dp))
        .padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        HudTextButton(when {
                state.busy -> tr(Res.string.camera_zoom_changing)
                state.hundredths == null -> tr(Res.string.camera_zoom_waiting)
                else -> tr(Res.string.camera_zoom_value, state.hundredths / 100f)
            }, enabled && !state.busy && state.hundredths != null, "camera-zoom", description = action,
            modifier = if (error != null) Modifier.weight(1f, fill = false) else Modifier) { onZoom(target) }
        if (error != null) HudIconButton(tr(Res.string.camera_zoom_error_details), WorkbenchGlyph.INFO, selected = true) { details = true }
    }
    WorkbenchDialog(details && error != null, { details = false }, tr(Res.string.camera_zoom_error_title)) {
        error?.let { Text(it.resolve(), Modifier.heightIn(max = 120.dp).verticalScroll(rememberScrollState())
            .testTag("camera-zoom-error"), fontSize = 14.sp) }
        Button({ details = false }, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("camera-zoom-error-close")) {
            Text(tr(Res.string.close))
        }
    }
}
