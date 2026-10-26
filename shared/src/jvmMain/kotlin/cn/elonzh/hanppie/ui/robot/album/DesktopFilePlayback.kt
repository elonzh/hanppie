package cn.elonzh.hanppie.ui.robot.album

import cn.elonzh.hanppie.ui.media.desktopFfmpegExecutable
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.concurrent.thread

/** File playback uses the bundled decoder. Each play/seek owns and closes its processes and sound line. */
internal class DesktopFilePlayback(path: String, startAt: Double, playing: Boolean,
    private val onFrame: (ByteArray) -> Unit, private val onPosition: (Double) -> Unit,
    private val onDuration: (Double) -> Unit, private val onEnded: () -> Unit, private val onError: () -> Unit,
    private val playAudio: Boolean = true, private val onAudio: (ByteArray, Int) -> Unit = { _, _ -> },
) : AutoCloseable {
    private val active = AtomicBoolean(true)
    private val children = CopyOnWriteArrayList<Process>()
    private val audioLock = Any()
    @Volatile private var line: SourceDataLine? = null
    init {
        require(File(path).isFile)
        require(startAt.isFinite() && startAt >= 0)
        try {
            val input = (if (playing) listOf("-re") else emptyList()) + listOf("-ss", startAt.toString(), "-i", path)
            val video = launch(input + listOf("-an", "-vf", "scale=640:360:force_original_aspect_ratio=decrease,pad=640:360:(ow-iw)/2:(oh-ih)/2,fps=30") +
                (if (playing) emptyList() else listOf("-frames:v", "1")) + listOf("-pix_fmt", "bgra", "-f", "rawvideo", "pipe:1"))
            thread(name = "hanppie-file-video", isDaemon = true) {
                try {
                    var frames = 0
                    val bytes = ByteArray(640 * 360 * 4)
                    while (active.get()) {
                        val size = video.inputStream.readNBytes(bytes, 0, bytes.size)
                        if (size == 0) {
                            if (active.get()) { if (frames == 0 || video.waitFor() != 0) onError() else if (playing) onEnded() }
                            break
                        }
                        check(size == bytes.size)
                        if (!active.get()) break
                        onFrame(bytes.copyOf()); onPosition(startAt + frames++ / 30.0)
                    }
                } catch (_: Exception) { if (active.get()) onError() }
            }
            if (playing) {
                // Optional audio mapping also accepts silent recordings without manufacturing an audio track.
                val audio = launch(input + listOf("-vn", "-map", "0:a:0?", "-ar", "48000", "-ac", "1", "-f", "s16le", "pipe:1"))
                thread(name = "hanppie-file-audio", isDaemon = true) {
                    try {
                        val bytes = ByteArray(3840)
                        while (active.get()) {
                            val size = audio.inputStream.read(bytes)
                            if (size <= 0) break
                            if (playAudio && line == null) {
                                val output = AudioSystem.getSourceDataLine(AudioFormat(48000f, 16, 1, true, false))
                                synchronized(audioLock) {
                                    if (active.get()) { output.open(); output.start(); line = output } else output.close()
                                }

                            }
                            onAudio(bytes, size)
                            if (active.get()) line?.write(bytes, 0, size)
                        }
                        line?.drain()
                    } catch (_: Exception) { if (active.get()) onError() }
                }
            }
        } catch (error: Exception) { close(); throw error }
    }
    private fun launch(args: List<String>): Process {
        val process = ProcessBuilder(listOf(desktopFfmpegExecutable(), "-hide_banner", "-nostdin", "-loglevel", "info") + args).start()
        children += process
        thread(name = "hanppie-file-metadata", isDaemon = true) {
            runCatching { process.errorStream.bufferedReader().useLines { lines -> lines.forEach { text ->
                Regex("Duration: ([0-9]+):([0-9]+):([0-9.]+)").find(text)?.groupValues?.let {
                    if (active.get()) onDuration(it[1].toDouble() * 3600 + it[2].toDouble() * 60 + it[3].toDouble())
                }
            } } }
        }
        return process
    }
    override fun close() {
        active.set(false)
        children.forEach { it.destroyForcibly() }
        synchronized(audioLock) { line?.stop(); line?.close(); line = null }
    }
}
