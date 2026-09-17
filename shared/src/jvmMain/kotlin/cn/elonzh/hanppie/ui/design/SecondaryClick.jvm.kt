package cn.elonzh.hanppie.ui.design

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.PointerMatcher
import androidx.compose.foundation.onClick
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerButton

@OptIn(ExperimentalFoundationApi::class)
internal actual fun Modifier.secondaryClick(onClick: () -> Unit): Modifier =
    onClick(matcher = PointerMatcher.mouse(PointerButton.Secondary), onClick = onClick)
