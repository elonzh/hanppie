package cn.elonzh.hanppie.ui.design

import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Shared adjustment, focus and keyboard behavior for Miuix/color/script sliders.
 * Miuix 0.9.4 supplies pointer input but no keyboard focus; its SetProgress also lacks enabled/finish checks.
 */
@Composable
internal fun Modifier.adjustableValue(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    description: String,
    enabled: Boolean = true,
    steps: Int = 0,
    keyboardStep: Float = (range.endInclusive - range.start) / (if (steps > 0) steps + 1 else 100),
    onChange: (Float) -> Unit,
    onFinished: (() -> Unit)? = null,
): Modifier {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var focused by remember { mutableStateOf(false) }
    var keyboardValue by remember(value, range) { mutableFloatStateOf(value) }
    val held = remember { mutableSetOf<Key>() }
    var keyboardChanged by remember { mutableStateOf(false) }
    val finish by rememberUpdatedState { if (enabled) onFinished?.invoke() }
    fun finishKeyboard() {
        val changed = keyboardChanged
        keyboardChanged = false
        held.clear()
        if (changed) finish()
    }
    val adjust by rememberUpdatedState<(Float, Boolean, Float) -> Boolean> { target, finished, previous ->
        if (!enabled || !target.isFinite() || range.start == range.endInclusive) false
        else {
            val next = target.coerceIn(range.start, range.endInclusive)
            if (next == previous) false
            else {
                onChange(next)
                if (finished) onFinished?.invoke()
                true
            }
        }
    }
    return semantics(mergeDescendants = true) {
        contentDescription = label
        stateDescription = description
        progressBarRangeInfo = ProgressBarRangeInfo(value, range, steps)
        if (!enabled) disabled()
        setProgress { adjust(it, true, value) }
    }.onFocusChanged {
        focused = it.isFocused
        if (!it.isFocused) finishKeyboard()
    }.onKeyEvent { event ->
        if (event.type == KeyEventType.KeyUp && held.remove(event.key)) {
            if (held.isEmpty()) finishKeyboard()
            return@onKeyEvent true
        }
        if (!enabled || event.type != KeyEventType.KeyDown || event.isCtrlPressed || event.isAltPressed || event.isMetaPressed ||
            range.start >= range.endInclusive || !keyboardStep.isFinite() || keyboardStep <= 0f) return@onKeyEvent false
        val next = when (event.key) {
            Key.DirectionLeft -> keyboardValue + if (rtl) keyboardStep else -keyboardStep
            Key.DirectionRight -> keyboardValue + if (rtl) -keyboardStep else keyboardStep
            Key.DirectionDown -> keyboardValue - keyboardStep
            Key.DirectionUp -> keyboardValue + keyboardStep
            Key.PageDown -> keyboardValue - keyboardStep * 10
            Key.PageUp -> keyboardValue + keyboardStep * 10
            Key.MoveHome -> range.start
            Key.MoveEnd -> range.endInclusive
            else -> return@onKeyEvent false
        }
        held.add(event.key)
        if (adjust(next, false, keyboardValue)) {
            keyboardValue = next.coerceIn(range.start, range.endInclusive)
            keyboardChanged = true
        }
        true
    }.then(if (focused) Modifier.border(2.dp, MiuixTheme.colorScheme.onSurface,
        RoundedCornerShape(HanppieDesignTokens.ControlRadius)) else Modifier)
        .focusable(enabled && range.start < range.endInclusive)
}
