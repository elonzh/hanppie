package cn.elonzh.hanppie.ui.design

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal expect fun DesktopListScrollbar(state: LazyListState, modifier: Modifier)
