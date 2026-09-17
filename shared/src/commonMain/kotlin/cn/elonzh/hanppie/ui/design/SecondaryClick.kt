package cn.elonzh.hanppie.ui.design

import androidx.compose.ui.Modifier

/**
 * Secondary (right) mouse click, available only where the platform has a mouse. Long press covers touch,
 * so callers pair this with `combinedClickable` and expose the same action on both platforms.
 */
internal expect fun Modifier.secondaryClick(onClick: () -> Unit): Modifier
