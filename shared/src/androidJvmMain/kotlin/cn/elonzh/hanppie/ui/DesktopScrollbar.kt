package cn.elonzh.hanppie.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.lazy.LazyListState

@Composable
internal expect fun DesktopListScrollbar(state: LazyListState, modifier: Modifier)
