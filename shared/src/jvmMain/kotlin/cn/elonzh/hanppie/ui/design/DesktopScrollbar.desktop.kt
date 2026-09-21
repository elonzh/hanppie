package cn.elonzh.hanppie.ui.design

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.v2.ScrollbarAdapter
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import kotlin.math.roundToInt

@Composable
internal actual fun DesktopListScrollbar(
    state: LazyListState,
    modifier: Modifier,
    interactionSource: MutableInteractionSource,
) {
    val dragging by interactionSource.collectIsDraggedAsState()
    val adapter = remember(state) { MeasuredListScrollbarAdapter(state) }
    LaunchedEffect(adapter) {
        snapshotFlow { state.layoutInfo to dragging }.collect { (layout, isDragging) ->
            adapter.update(isDragging, layout.visibleItemsInfo.associate { it.index to it.size })
        }
    }
    VerticalScrollbar(adapter, modifier, interactionSource = interactionSource)
}

/** Retain measured heights instead of extrapolating from only the currently visible messages.
 * The coordinate system stays frozen for an entire thumb gesture, including gaps between moves.
 */
private class MeasuredListScrollbarAdapter(private val state: LazyListState) : ScrollbarAdapter {
    private val measured = mutableMapOf<Int, Int>()
    private var sizes by mutableStateOf(emptyList<Double>())
    private var dragging by mutableStateOf(false)
    private var dragOffset by mutableDoubleStateOf(0.0)
    private var width = 0

    fun update(isDragging: Boolean, visibleSizes: Map<Int, Int>) {
        val layout = state.layoutInfo
        if (width != layout.viewportSize.width) {
            measured.clear()
            width = layout.viewportSize.width
        }
        measured.keys.removeAll { it >= layout.totalItemsCount }
        measured.putAll(visibleSizes)
        if (isDragging && !dragging) dragOffset = scrollOffset
        dragging = isDragging
        if (!dragging) {
            // Unknown rows use the same estimate throughout navigation, not a changing visible average.
            val estimate = (viewportSize / 4).coerceAtLeast(48.0)
            sizes = List(layout.totalItemsCount) { index ->
                (measured[index]?.toDouble() ?: estimate) + layout.mainAxisItemSpacing
            }
        }
    }

    override val viewportSize: Double get() = state.layoutInfo.viewportSize.height.toDouble()
    override val contentSize: Double get() = (sizes.sum() + state.layoutInfo.beforeContentPadding +
        state.layoutInfo.afterContentPadding - state.layoutInfo.mainAxisItemSpacing).coerceAtLeast(viewportSize)
    private val maximum get() = (contentSize - viewportSize).coerceAtLeast(0.0)
    override val scrollOffset: Double get() = when {
        dragging -> dragOffset.coerceIn(0.0, maximum)
        !state.canScrollBackward -> 0.0
        !state.canScrollForward -> maximum
        else -> (sizes.take(state.firstVisibleItemIndex).sum() + state.firstVisibleItemScrollOffset)
            .coerceIn(0.0, maximum)
    }

    override suspend fun scrollTo(scrollOffset: Double) {
        if (sizes.isEmpty()) return
        val target = scrollOffset.coerceIn(0.0, maximum)
        if (dragging) dragOffset = target
        if (target >= maximum) {
            state.scrollToItem(sizes.lastIndex)
            val lastSize = state.layoutInfo.visibleItemsInfo.lastOrNull()?.size ?: 0
            state.scrollToItem(sizes.lastIndex, lastSize)
        } else {
            var remainder = target
            var index = 0
            while (index < sizes.lastIndex && remainder >= sizes[index]) {
                remainder -= sizes[index++]
            }
            // One positioning operation per pointer move. Measuring at offset zero and then
            // jumping again causes two synchronous lazy layouts and visible intermediate jumps.
            val fraction = remainder / sizes[index].coerceAtLeast(1.0)
            // Offscreen Markdown starts with a placeholder height when recomposed. Applying
            // a cached pixel offset to that placeholder can skip several messages.
            val visible = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
            val offset = visible?.let { (fraction * it.size).roundToInt() } ?: 0
            state.scrollToItem(index, offset)
        }
    }
}
