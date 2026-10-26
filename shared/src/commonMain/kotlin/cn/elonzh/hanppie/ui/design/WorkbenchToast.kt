package cn.elonzh.hanppie.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Brief operation feedback floats above the page without changing its layout or taking focus. */
@Composable
internal fun BoxScope.WorkbenchToast(message: String?, onDismiss: () -> Unit) {
    val dismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(message) { if (message != null) { delay(3_000); dismiss() } }
    if (message != null) Text(message,
        Modifier.align(Alignment.BottomCenter).padding(20.dp).widthIn(max = 480.dp)
            .background(MiuixTheme.colorScheme.onSurface, RoundedCornerShape(12.dp))
            .padding(horizontal = 20.dp, vertical = 12.dp).testTag("workbench-toast")
            .semantics { liveRegion = LiveRegionMode.Polite },
        color = MiuixTheme.colorScheme.surface, fontSize = 14.sp)
}
