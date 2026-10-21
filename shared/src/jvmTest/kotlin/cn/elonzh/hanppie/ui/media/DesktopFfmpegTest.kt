package cn.elonzh.hanppie.ui.media

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopFfmpegTest {
    @Test fun bundledExecutableRunsWithoutSystemFfmpegFromAPathWithSpaces() {
        val resources = Path.of(System.getProperty("compose.application.resources.dir"), "ffmpeg")
        val name = if (System.getProperty("os.name").startsWith("Windows")) "ffmpeg.exe" else "ffmpeg"
        val directory = Files.createTempDirectory("hanppie media ")
        val executable = directory.resolve(name)
        val output = directory.resolve("version.txt")
        try {
            Files.copy(resources.resolve(name), executable)
            assertTrue(executable.toFile().setExecutable(true))
            val builder = ProcessBuilder(executable.toString(), "-version")
                .redirectErrorStream(true).redirectOutput(output.toFile())
            builder.environment()["PATH"] = directory.toString()
            val process = builder.start()
            try {
                assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Bundled FFmpeg did not start")
                assertEquals(0, process.exitValue(), Files.readString(output))
                assertTrue(Files.readString(output).contains("ffmpeg version"))
            } finally {
                if (process.isAlive) process.destroyForcibly().waitFor()
            }
        } finally {
            Files.deleteIfExists(output)
            Files.deleteIfExists(executable)
            Files.deleteIfExists(directory)
        }
    }
}
