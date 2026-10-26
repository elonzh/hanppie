package cn.elonzh.hanppie.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.app.ConsoleController
import cn.elonzh.hanppie.ui.design.adjustableValue
import cn.elonzh.hanppie.ui.i18n.tr
import cn.elonzh.hanppie.ui.robot.display.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first

@Composable
internal fun ExternalLightingContent(model: ConsoleController) {
    val connection by model.state.collectAsState()
    val ui by model.robotDisplay.state.collectAsState()
    LaunchedEffect(ui.connection, connection.connected) {
        if (connection.connected) {
            snapshotFlow { model.robotDisplay.state.value.busy }.first { !it }
            if (model.robotDisplay.state.value.headlights == null && !model.robotDisplay.state.value.headlightsUnavailable)
                model.robotDisplay.refreshHeadlights()
        }
    }
    DisposableEffect(model) { onDispose { model.robotDisplay.cancelRead() } }
    Text(tr(Res.string.display_external_lights_help), fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    when {
        !connection.connected -> SettingsNotice(tr(Res.string.display_connect))
        ui.headlightsUnavailable -> SettingsNotice(tr(Res.string.display_lights_unavailable), Modifier.testTag("display-lights-unavailable"))
        else -> {
            ui.error?.let { SettingsNotice(it.resolve(), Modifier.testTag("display-device-error"), error = true) }
            Text(tr(Res.string.display_headlights), fontSize = 15.sp)
            if (ui.target == DisplayTarget.HEADLIGHTS) {
                Text(tr(if (ui.writing) Res.string.display_writing else Res.string.display_reading), Modifier.testTag("display-device-busy"), fontSize = 14.sp)
                if (!ui.writing) Button(model.robotDisplay::cancelRead, Modifier.heightIn(min = 48.dp).testTag("display-cancel-read")) { Text(tr(Res.string.cancel)) }
            } else {
                ui.headlights?.let { reading ->
                    Text(tr(Res.string.display_lights_current, reading.left, reading.right), Modifier.testTag("display-lights-current"), fontSize = 14.sp)
                    if (reading.left in 0..100 && reading.right in 0..100) {
                        var draft by remember(reading, ui.connection) { mutableIntStateOf(reading.left) }
                        val changeBrightness: (Float) -> Unit = { draft = it.roundToInt() }
                        Text("$draft%", fontSize = 14.sp)
                        Slider(draft.toFloat(), changeBrightness, valueRange = 0f..100f, enabled = !ui.busy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("display-lights-slider")
                                .adjustableValue(tr(Res.string.display_headlights), draft.toFloat(), 0f..100f, "$draft%",
                                    !ui.busy, steps = 99, onChange = changeBrightness))
                        Text(tr(Res.string.display_lights_scope), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        Button({ model.robotDisplay.setHeadlights(draft) }, Modifier.heightIn(min = 48.dp).testTag("display-lights-apply"),
                            !ui.busy && (draft != reading.left || draft != reading.right)) { Text(tr(Res.string.display_lights_apply)) }
                    } else Text(tr(Res.string.display_lights_range), fontSize = 14.sp)
                }
                Button(model.robotDisplay::refreshHeadlights, Modifier.heightIn(min = 48.dp).testTag("display-lights-read"), !ui.busy) {
                    Text(tr(Res.string.display_lights_read))
                }
            }
        }
    }
}
