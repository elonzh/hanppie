package cn.elonzh.hanppie.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.lazy.LazyListState

@Composable
internal actual fun DesktopListScrollbar(state: LazyListState, modifier: Modifier) {
    androidx.compose.foundation.VerticalScrollbar(
        adapter = androidx.compose.foundation.rememberScrollbarAdapter(state), modifier = modifier)
}
