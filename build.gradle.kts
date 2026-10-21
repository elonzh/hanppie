plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kmp) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room3) apply false
}

val releaseVersion = java.util.Properties().apply {
    rootProject.file("version.xcconfig").inputStream().use(::load)
}
allprojects { version = releaseVersion.getProperty("MARKETING_VERSION") }
extra["releaseBuild"] = releaseVersion.getProperty("CURRENT_PROJECT_VERSION").toInt()

// Only the host executable is resolved. It is a separate process, without the JAVE Java wrapper.
val desktopOs = System.getProperty("os.name")
val desktopArch = System.getProperty("os.arch")
val desktopArm64 = desktopArch in listOf("aarch64", "arm64")
val desktopX64 = desktopArch in listOf("amd64", "x86_64")
val ffmpegPlatform = when {
    desktopOs.startsWith("Mac") && desktopArm64 -> "osxm1"
    desktopOs.startsWith("Mac") && desktopX64 -> "osx64"
    desktopOs.startsWith("Windows") && desktopArm64 -> "win-arm64"
    desktopOs.startsWith("Windows") && desktopX64 -> "win64"
    desktopOs.startsWith("Linux") && desktopArm64 -> "linux-arm64"
    desktopOs.startsWith("Linux") && desktopX64 -> "linux64"
    else -> error("No bundled FFmpeg for $desktopOs / $desktopArch")
}
val desktopFfmpeg = configurations.create("desktopFfmpeg") {
    isCanBeConsumed = false
    isTransitive = false
}
dependencies {
    desktopFfmpeg("ws.schild:jave-nativebin-$ffmpegPlatform:${libs.versions.jave.nativebin.get()}")
}
tasks.register<Sync>("prepareDesktopFfmpeg") {
    group = "build"
    description = "Prepare the host FFmpeg executable and redistribution notices for desktop runs and packages"
    into(layout.buildDirectory.dir("desktop-resources/common/ffmpeg"))
    from(desktopFfmpeg.map { zipTree(it) }) {
        include("ws/schild/jave/nativebin/ffmpeg-*")
        eachFile {
            relativePath = RelativePath(true, if (desktopOs.startsWith("Windows")) "ffmpeg.exe" else "ffmpeg")
        }
        includeEmptyDirs = false
        filePermissions { unix("rwxr-xr-x") }
    }
    from("third-party/ffmpeg")
}
