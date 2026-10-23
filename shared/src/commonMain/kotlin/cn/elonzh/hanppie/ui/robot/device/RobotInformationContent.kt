package cn.elonzh.hanppie.ui.robot.device

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.robot.product.RobotVersionTarget
import cn.elonzh.hanppie.ui.app.ConsoleController
import cn.elonzh.hanppie.ui.i18n.tr
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
private fun versionTargetName(target: RobotVersionTarget): String = when (target) {
    RobotVersionTarget.SYSTEM -> tr(Res.string.robot_info_system)
    RobotVersionTarget.SYSTEM_CAN -> tr(Res.string.robot_info_can)
    RobotVersionTarget.PROGRAM_RUNTIME -> tr(Res.string.robot_info_runtime)
    RobotVersionTarget.GIMBAL_MOTOR -> tr(Res.string.robot_info_gimbal_motor)
    RobotVersionTarget.VISION -> tr(Res.string.robot_info_vision)
    RobotVersionTarget.PERCEPTION -> tr(Res.string.robot_info_perception)
    else -> robotModuleName(checkNotNull(target.componentId))
}

@Composable
internal fun RobotInformationContent(model: ConsoleController) {
    val console by model.state.collectAsState()
    val controller = model.robotInformation
    val state by controller.state.collectAsState()
    var expanded by remember(state.connected) { mutableStateOf(false) }
    Text(tr(Res.string.robot_info_title), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    Column(Modifier.fillMaxWidth().testTag("robot-information"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!console.connected || !state.connected) Text(tr(Res.string.robot_info_connect), fontSize = 15.sp)
        else {
            Text(console.productName, Modifier.testTag("robot-info-model"), fontSize = 15.sp)
            val usage = state.usage
            if (usage == null) Text(tr(Res.string.robot_info_usage_waiting), Modifier.testTag("robot-info-usage"), fontSize = 14.sp)
            else {
                Text(tr(Res.string.robot_info_mileage, usage.mileageMetres?.toString() ?: "—"),
                    Modifier.testTag("robot-info-mileage"), fontSize = 15.sp)
                Text(tr(Res.string.robot_info_driving_time, usage.drivingSeconds?.toString() ?: "—"),
                    Modifier.testTag("robot-info-driving"), fontSize = 15.sp)
                Text(tr(Res.string.robot_info_usage_age, usage.reportAgeSeconds), fontSize = 14.sp)
            }
            Button({ expanded = !expanded }, Modifier.heightIn(min = 48.dp).testTag("robot-info-toggle")) {
                Text(tr(if (expanded) Res.string.robot_info_hide else Res.string.robot_info_show))
            }
            if (expanded) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(controller::refresh, Modifier.heightIn(min = 48.dp).testTag("robot-info-refresh"), enabled = !state.busy) {
                        Text(tr(Res.string.robot_info_query_all))
                    }
                    if (state.busy) Button(controller::cancelQueries, Modifier.heightIn(min = 48.dp).testTag("robot-info-cancel")) {
                        Text(tr(Res.string.cancel))
                    }
                }
                InformationRow(tr(Res.string.robot_info_activation), state.activation, "activation", controller::readActivation) {
                    tr(if (it) Res.string.robot_info_activated else Res.string.robot_info_not_activated)
                }
                state.versions.forEach { (target, value) ->
                    InformationRow(versionTargetName(target), value, target.name, { controller.readVersion(target) }) { it }
                }
            }
        }
    }
}

@Composable
internal fun <T> InformationRow(label: String, value: InformationValue<T>, tag: String, query: () -> Unit,
    enabled: Boolean = true, render: @Composable (T) -> String) {
    Row(Modifier.fillMaxWidth().testTag("robot-info-$tag"), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(when (value) {
            InformationValue.Unread -> tr(Res.string.robot_info_unread)
            InformationValue.Loading -> tr(Res.string.robot_info_reading)
            is InformationValue.Value -> render(value.value)
            is InformationValue.Failed -> value.message.resolve()
        }, Modifier.testTag("robot-info-value-$tag"), fontSize = 14.sp,
            color = if (value is InformationValue.Failed) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurface)
        }
        Button(query, Modifier.heightIn(min = 48.dp).testTag("robot-info-query-$tag"), enabled = enabled && value != InformationValue.Loading) {
            Text(tr(if (value is InformationValue.Failed) Res.string.album_retry else Res.string.robot_info_query))
        }
    }
}
