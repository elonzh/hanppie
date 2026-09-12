package cn.elonzh.hanppie.ui.design

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal actual fun DesktopListScrollbar(state: LazyListState, modifier: Modifier) {
    androidx.compose.foundation.VerticalScrollbar(
        adapter = androidx.compose.foundation.rememberScrollbarAdapter(state), modifier = modifier)
}
