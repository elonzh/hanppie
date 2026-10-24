package cn.elonzh.hanppie.ui.robot.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.robot.remote.*
import cn.elonzh.hanppie.ui.design.HanppieDesignTokens
import cn.elonzh.hanppie.ui.i18n.tr
import top.yukonga.miuix.kmp.basic.Text

@Composable
internal fun GunCoolingControls(state: GunCoolingState, enabled: Boolean, onCool: () -> Unit) {
    if (state.heatRaw?.let { it > 0 } != true && !state.ownsCooling && state.problem == null) return
    Column(Modifier.widthIn(max = 300.dp)
        .background(HanppieDesignTokens.RemoteHudSurface.copy(alpha = HanppieDesignTokens.RemoteHudAlpha), RoundedCornerShape(12.dp))
        .padding(horizontal = 8.dp, vertical = 4.dp).testTag("gun-cooling")) {
        val message = when {
            state.phase == GunCoolingPhase.REQUESTING -> tr(Res.string.gun_cooling_requesting)
            state.phase == GunCoolingPhase.COOLING -> tr(Res.string.gun_cooling_active)
            state.phase == GunCoolingPhase.RESETTING -> tr(Res.string.gun_cooling_resetting)
            state.phase == GunCoolingPhase.UNCONFIRMED -> tr(Res.string.gun_cooling_unconfirmed)
            !state.fresh -> tr(Res.string.gun_heat_stale)
            state.problem == GunCoolingProblem.REJECTED -> tr(Res.string.gun_cooling_rejected)
            (state.heatRaw ?: 0) >= 2000 -> tr(Res.string.gun_overheated)
            else -> tr(Res.string.gun_heat, "${(checkNotNull(state.heatRaw).coerceAtMost(2000) * 100 / 2000)}%")
        }
        Text(message, Modifier.testTag("gun-heat").semantics { liveRegion = LiveRegionMode.Polite },
            color = HanppieDesignTokens.RemoteHudContent, fontSize = 12.sp)
        if (state.ownsCooling && !state.fresh)
            Text(tr(Res.string.gun_heat_stale), color = HanppieDesignTokens.RemoteHudMuted, fontSize = 12.sp)
        if (state.phase == GunCoolingPhase.IDLE || state.phase == GunCoolingPhase.UNCONFIRMED)
            HudTextButton(tr(if (state.canRetryReset) Res.string.gun_cooling_retry else Res.string.gun_cooling_start),
                enabled && (state.canCool || state.canRetryReset), "gun-cooling-action", action = onCool)
    }
}
