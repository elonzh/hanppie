package cn.elonzh.hanppie.ui.app

internal actual fun getPlatformBuildInfo(): HanppieBuildInfo {
    val osName = System.getProperty("os.name") ?: "Desktop"
    val osVersion = System.getProperty("os.version") ?: ""
    val osArch = System.getProperty("os.arch") ?: ""
    val javaVersion = System.getProperty("java.version") ?: ""
    val javaVendor = System.getProperty("java.vendor") ?: ""
    val platform = listOf(osName, osVersion, "($osArch)").filter(String::isNotBlank).joinToString(" ")
    val runtime = listOf("Java $javaVersion", "($javaVendor)").filter(String::isNotBlank).joinToString(" ")
    return HanppieBuildInfo(
        platformName = platform,
        runtimeVersion = runtime,
    )
}
