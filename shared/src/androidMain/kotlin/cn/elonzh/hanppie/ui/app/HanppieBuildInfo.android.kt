package cn.elonzh.hanppie.ui.app

import android.os.Build

internal actual fun getPlatformBuildInfo(): HanppieBuildInfo {
    val platform = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    val device = listOf(Build.MANUFACTURER, Build.MODEL).filter(String::isNotBlank).joinToString(" ")
    val abi = Build.SUPPORTED_ABIS.firstOrNull()?.let { "($it)" }.orEmpty()
    val runtime = listOf(device, abi).filter(String::isNotBlank).joinToString(" ")
    return HanppieBuildInfo(
        platformName = platform,
        runtimeVersion = runtime.ifBlank { "Android ART" },
    )
}
