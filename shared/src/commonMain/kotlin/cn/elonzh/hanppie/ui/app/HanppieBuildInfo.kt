package cn.elonzh.hanppie.ui.app

internal data class HanppieBuildInfo(
    val appName: String = "Hanppie",
    val versionName: String = "0.1.0",
    val platformName: String,
    val runtimeVersion: String,
    val frameworkVersion: String = "Kotlin 2.4.10 · Compose 1.12.0",
    val protocols: String = "RoboMaster SDK & Lab API",
    val repositoryUrl: String = "https://github.com/elonzh/hanppie",
    val license: String = "Apache-2.0",
    val copyright: String = "© 2020-2026 elonzh",
) {
    fun formatDiagnosticReport(): String = buildString {
        appendLine("$appName v$versionName")
        appendLine("Platform: $platformName")
        appendLine("Runtime: $runtimeVersion")
        appendLine("Framework: $frameworkVersion")
        appendLine("Protocols: $protocols")
        appendLine("Repository: $repositoryUrl")
        appendLine("License: $license")
    }.trimEnd()
}

internal expect fun getPlatformBuildInfo(): HanppieBuildInfo
