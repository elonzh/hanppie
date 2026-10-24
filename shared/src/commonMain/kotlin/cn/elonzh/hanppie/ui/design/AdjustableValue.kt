package cn.elonzh.hanppie.ui.design

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*

/** Names an adjustable control in its user-facing units, including custom color/script sliders.
 * Miuix 0.9.4's SetProgress does not guard enabled or finish a gesture; keep both contracts here.
 */
internal fun Modifier.adjustableValue(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    description: String,
    enabled: Boolean = true,
    steps: Int = 0,
    onChange: (Float) -> Unit,
    onFinished: (() -> Unit)? = null,
): Modifier = semantics(mergeDescendants = true) {
    contentDescription = label
    stateDescription = description
    progressBarRangeInfo = ProgressBarRangeInfo(value, range, steps)
    if (!enabled) disabled()
    setProgress { target ->
        if (!enabled || !target.isFinite() || range.start == range.endInclusive) false
        else {
            val next = target.coerceIn(range.start, range.endInclusive)
            if (next == value) false
            else {
                onChange(next)
                onFinished?.invoke()
                true
            }
        }
    }
}
