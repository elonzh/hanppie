package cn.elonzh.hanppie.ui.robot.telemetry

import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.i18n.tr

/** UI bands retain the former signal-bar thresholds; not a calibrated percentage or link guarantee. */
internal fun signalQualityLabel(raw: Int?): String = tr(when {
    raw == null || raw !in 0..255 -> Res.string.signal_level_unknown
    raw < 20 -> Res.string.signal_level_weak
    raw < 40 -> Res.string.signal_level_fair
    else -> Res.string.signal_level_strong
})
