package cn.elonzh.hanppie.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.i18n.tr
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

@Composable
internal fun GamepadInputPreferences(value: GamepadInputSettings, enabled: Boolean, change: (GamepadInputSettings) -> Unit) {
    Text(tr(Res.string.gamepad_response_title), fontSize = 15.sp)
    Text(tr(Res.string.gamepad_response_help), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    GamepadResponseSlider(tr(Res.string.gamepad_lateral_gain), "lateral-gain", value.lateralGain * 100, 0f..200f, "%", enabled) {
        change(value.copy(lateralGain = it / 100))
    }
    GamepadResponseSlider(tr(Res.string.gamepad_forward_gain), "forward-gain", value.forwardGain * 100, 0f..200f, "%", enabled) {
        change(value.copy(forwardGain = it / 100))
    }
    for (yaw in listOf(true, false)) {
        val curve = if (yaw) value.yaw else value.pitch
        val tag = if (yaw) "yaw" else "pitch"
        fun update(next: GamepadAxisCurve) = change(if (yaw) value.copy(yaw = next) else value.copy(pitch = next))
        val axisLabel = tr(if (yaw) Res.string.gamepad_yaw_response else Res.string.gamepad_pitch_response)
        Text(axisLabel, fontSize = 14.sp)
        GamepadResponseSlider(tr(Res.string.gamepad_axis_gain, axisLabel), "$tag-gain", curve.gain * 100, 0f..200f, "%", enabled) { update(curve.copy(gain = it / 100)) }
        GamepadResponseSlider(tr(Res.string.gamepad_axis_exponent, axisLabel), "$tag-exponent", curve.exponent * 100, 25f..400f, "", enabled,
            display = { "${it / 100.0}×" }) { update(curve.copy(exponent = it / 100)) }
        GamepadResponseSlider(tr(Res.string.gamepad_axis_deadzone, axisLabel), "$tag-deadzone", curve.deadZone / (1 + curve.deadZone) * 100, 0f..50f, "%", enabled) {
            val fraction = it / 100; update(curve.copy(deadZone = fraction / (1 - fraction)))
        }
    }
    Button({ change(GamepadInputSettings()) }, Modifier.heightIn(min = 48.dp).testTag("gamepad-response-defaults"), enabled) {
        Text(tr(Res.string.gamepad_response_defaults))
    }
}

@Composable
private fun GamepadResponseSlider(label: String, tag: String, value: Double, range: ClosedFloatingPointRange<Float>,
    suffix: String, enabled: Boolean, display: (Int) -> String = { "$it$suffix" }, change: (Double) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp)
        Text(display(value.roundToInt()), fontSize = 14.sp)
    }
    Slider(value.toFloat(), { change(it.roundToInt().toDouble()) }, valueRange = range, enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("gamepad-response-$tag")
            .semantics { contentDescription = label; stateDescription = display(value.roundToInt()) })
}
