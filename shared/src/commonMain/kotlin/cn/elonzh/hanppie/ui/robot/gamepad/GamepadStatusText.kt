package cn.elonzh.hanppie.ui.robot.gamepad

import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.robot.gamepad.GamepadWarning
import cn.elonzh.hanppie.ui.i18n.tr

internal fun GamepadWarning.label(): String = tr(when (this) {
    GamepadWarning.NOT_CALIBRATED -> Res.string.gamepad_warning_calibration
    GamepadWarning.NOT_CENTERED -> Res.string.gamepad_warning_centering
    GamepadWarning.LOW_BATTERY -> Res.string.gamepad_warning_battery
})
