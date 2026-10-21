package cn.elonzh.hanppie.ui.media

import java.nio.file.Files
import java.nio.file.Path

/** The same application resources are supplied by Compose for Gradle runs and native packages. */
internal fun desktopFfmpegExecutable(): String {
    System.getenv("HANPPIE_FFMPEG")?.takeIf { it.isNotBlank() }?.let { return it }
    val resources = checkNotNull(System.getProperty("compose.application.resources.dir")) {
        "Hanppie media resources are missing. Run with :desktopApp:run or reinstall Hanppie."
    }
    val name = if (System.getProperty("os.name").startsWith("Windows")) "ffmpeg.exe" else "ffmpeg"
    val executable = Path.of(resources, "ffmpeg", name)
    check(Files.isRegularFile(executable) && Files.isExecutable(executable)) {
        "Hanppie FFmpeg is missing or not executable: $executable. Reinstall Hanppie."
    }
    return executable.toAbsolutePath().toString()
}
