package cn.elonzh.hanppie.ui.robot.remote

import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import cn.elonzh.hanppie.ui.app.activity

@Composable
internal actual fun RemoteOrientation(onBack: (() -> Unit)?) {
    val activity = LocalContext.current.activity()
    val previous = rememberSaveable { activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    BackHandler(enabled = onBack != null) { onBack?.invoke() }
    DisposableEffect(activity) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            if (activity?.isChangingConfigurations == false) activity.requestedOrientation = previous
        }
    }
}
