package cn.elonzh.hanppie.ui.robot.device

import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.robot.session.RequestProblem
import cn.elonzh.hanppie.robot.session.RobotRequestException
import cn.elonzh.hanppie.ui.i18n.UiText
import cn.elonzh.hanppie.ui.i18n.uiText
import org.jetbrains.compose.resources.StringResource

internal fun robotRequestMessage(error: Exception, fallback: StringResource): UiText = uiText(when ((error as? RobotRequestException)?.problem) {
    RequestProblem.TIMED_OUT -> Res.string.robot_settings_timed_out
    RequestProblem.REJECTED -> Res.string.robot_settings_rejected
    RequestProblem.INVALID_RESPONSE -> Res.string.robot_settings_invalid_response
    RequestProblem.NOT_APPLIED -> Res.string.robot_settings_not_applied
    else -> fallback
})
