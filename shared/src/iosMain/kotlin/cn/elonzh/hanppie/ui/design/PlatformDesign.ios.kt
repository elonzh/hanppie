package cn.elonzh.hanppie.ui.design

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.LocalUIViewController
import platform.UIKit.UIUserInterfaceStyle

@Composable
internal actual fun ApplyPlatformAppearance(dark: Boolean) {
    val controller = LocalUIViewController.current
    SideEffect { controller.overrideUserInterfaceStyle = if (dark) UIUserInterfaceStyle.UIUserInterfaceStyleDark else UIUserInterfaceStyle.UIUserInterfaceStyleLight }
}
internal actual fun Modifier.secondaryClick(onClick: () -> Unit): Modifier = this
@Composable
internal actual fun DesktopListScrollbar(state: LazyListState, modifier: Modifier, interactionSource: MutableInteractionSource) = Unit
