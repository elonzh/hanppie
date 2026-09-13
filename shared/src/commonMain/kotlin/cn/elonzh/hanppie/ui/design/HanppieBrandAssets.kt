package cn.elonzh.hanppie.ui.design

import cn.elonzh.hanppie.resources.Res
import cn.elonzh.hanppie.resources.hanppie_companion
import cn.elonzh.hanppie.resources.hanppie_expression_active
import cn.elonzh.hanppie.resources.hanppie_expression_recording
import cn.elonzh.hanppie.resources.hanppie_expression_standby
import cn.elonzh.hanppie.resources.hanppie_expression_talking
import cn.elonzh.hanppie.resources.hanppie_mark
import cn.elonzh.hanppie.ui.robot.remote.RemoteLedState
import org.jetbrains.compose.resources.DrawableResource

internal object HanppieBrandAssets {
    val avatar: DrawableResource get() = Res.drawable.hanppie_companion
    val smallMark: DrawableResource get() = Res.drawable.hanppie_mark

    fun expression(state: RemoteLedState): DrawableResource = when (state) {
        RemoteLedState.STANDBY -> Res.drawable.hanppie_expression_standby
        RemoteLedState.ACTIVE -> Res.drawable.hanppie_expression_active
        RemoteLedState.RECORDING -> Res.drawable.hanppie_expression_recording
        RemoteLedState.TALKING -> Res.drawable.hanppie_expression_talking
    }
}
