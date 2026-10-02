package cn.elonzh.hanppie.ui.app

import androidx.compose.runtime.Composable
import platform.UIKit.UIDevice

@Composable
internal actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit

internal actual fun getPlatformBuildInfo(): HanppieBuildInfo = HanppieBuildInfo(
    platformName = "${UIDevice.currentDevice.systemName} ${UIDevice.currentDevice.systemVersion}",
    runtimeVersion = "Kotlin/Native",
)
