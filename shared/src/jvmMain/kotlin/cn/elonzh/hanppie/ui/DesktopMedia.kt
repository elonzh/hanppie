package cn.elonzh.hanppie.ui

import cn.elonzh.hanppie.resources.*

import java.io.EOFException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.*
import kotlin.concurrent.thread

/** Independent bounded decoder pipes; only owned FFmpeg children are terminated. */
internal class DesktopMedia(
    sound: Boolean,
    private val onVideo: (ByteArray) -> Unit,
    private val onStatus: (String) -> Unit,
    private val pcmSink: ((ByteArray, Int) -> Unit)? = null,
    executable: String = System.getenv("HANPPIE_FFMPEG") ?: "ffmpeg",
    videoEnabled: Boolean = true,
) : AutoCloseable {
    private val active = AtomicBoolean(true)
    private val threads = java.util.concurrent.CopyOnWriteArrayList<Thread>()
    private val videoQueue = ArrayBlockingQueue<ByteArray>(256)
    private val audioQueue = ArrayBlockingQueue<ByteArray>(64)
    private val processes = java.util.concurrent.CopyOnWriteArrayList<Process>()
    @Volatile private var line: SourceDataLine? = null
    val running: Boolean get() = active.get() && processes.all { it.isAlive }
    fun video(bytes: ByteArray) { if (active.get() && !videoQueue.offer(bytes)) fail(tr(Res.string.video_queue_overflow_restart_video)) }
    fun audio(bytes: ByteArray) { if (active.get() && !audioQueue.offer(bytes)) fail(tr(Res.string.audio_queue_overflow_restart_listening)) }

    init {
        try {
            if (videoEnabled) {
                val video = launch(executable, listOf("-probesize","32","-analyzeduration","0","-flags","low_delay",
                    "-f","h264","-i","pipe:0","-an","-vf","scale=1280:720","-pix_fmt","bgra","-f","rawvideo","pipe:1"))
                writer(video, videoQueue)
                threads += thread(name = "hanppie-video-output", isDaemon = true) {
                    try {
                        var count = 0
                        while (active.get()) {
                            val frame = ByteArray(1280*720*4)
                            var offset = 0
                            while (offset < frame.size) {
                                val n = video.inputStream.read(frame, offset, frame.size-offset)
                                if (n < 0) throw EOFException(tr(Res.string.video_decoder_exited))
                                offset += n
                            }
                            onVideo(frame)
                            if (++count == 1 || count % 30 == 0) onStatus(tr(Res.string.video_value_decoded_frames,count))
                        }
                    } catch (e: Exception) { if (active.get()) fail(e.message ?: tr(Res.string.video_decoding_failed)) }
                }
            }
            if (sound) {
                val audio = launch(executable, listOf("-probesize","32","-analyzeduration","0","-f","ogg","-i","pipe:0",
                    "-vn","-ar","48000","-ac","1","-f","s16le","pipe:1"))
                val ogg = OpusOgg()
                audio.outputStream.write(ogg.headers()); audio.outputStream.flush()
                writer(audio, audioQueue, ogg::packet)
                threads += thread(name = "hanppie-audio-output", isDaemon = true) {
                    try {
                        if (pcmSink == null) {
                            val format = AudioFormat(48000f,16,1,true,false)
                            val output = AudioSystem.getSourceDataLine(format)
                            line = output; output.open(format, 9600); output.start()
                        }
                        val bytes = ByteArray(3840); var count = 0L
                        while (active.get()) {
                            val n = audio.inputStream.readNBytes(bytes, 0, bytes.size)
                            if (n == 0 || n % 2 != 0) throw EOFException(tr(Res.string.audio_decoder_exited))
                            if (pcmSink != null) pcmSink.invoke(bytes,n) else line?.write(bytes,0,n)
                            count += n
                            if (count == n.toLong() || count % 19200 == 0L) onStatus(tr(Res.string.audio_value_decoded_bytes,count))
                        }
                    } catch (e: Exception) { if (active.get()) fail(e.message ?: tr(Res.string.audio_playback_failed)) }
                }
            }
        } catch (e: Exception) { close(); throw IllegalStateException(tr(Res.string.cannot_start_ffmpeg_install_it_or_set_hanppie_ffmpeg,e.message),e) }
    }
    private fun launch(executable: String, args: List<String>): Process {
        val process = ProcessBuilder(listOf(executable,"-hide_banner","-loglevel","error","-nostdin") + args).start()
        processes += process
        threads += thread(name = "hanppie-codec-errors", isDaemon = true) {
            // Drain stderr so a malformed stream cannot block the child. No device media is logged.
            runCatching { process.errorStream.use { stream -> val buffer = ByteArray(1024); while (stream.read(buffer) >= 0) { } } }
        }
        return process
    }
    private fun writer(process: Process, queue: ArrayBlockingQueue<ByteArray>, transform: (ByteArray) -> ByteArray = { it }) {
        threads += thread(name = "hanppie-codec-input", isDaemon = true) {
            try {
                while (active.get()) {
                    val bytes = queue.poll(100,TimeUnit.MILLISECONDS) ?: continue
                    process.outputStream.write(transform(bytes)); process.outputStream.flush()
                }
            } catch (e: Exception) { if (active.get()) fail(e.message ?: tr(Res.string.media_transport_failed)) }
        }
    }
    private fun fail(message: String) {
        if (active.getAndSet(false)) {
            onStatus(tr(Res.string.media_error_value,message))
            processes.forEach { it.destroyForcibly() }
            line?.stop(); line?.close()
        }
    }
    override fun close() {
        active.set(false)
        processes.forEach { it.destroyForcibly() }
        line?.stop(); line?.close(); line = null
        threads.forEach { if (it !== Thread.currentThread()) it.join(1000) }
        processes.forEach { it.waitFor(1,TimeUnit.SECONDS) }
        videoQueue.clear(); audioQueue.clear()
    }
}

internal fun saveDesktopPhoto(frame: ByteArray): String {
    require(frame.size == 1280 * 720 * 4)
    val output = desktopMediaPath("Pictures", "jpg")
    val image = org.jetbrains.skia.Image.makeRaster(
        org.jetbrains.skia.ImageInfo(1280, 720, org.jetbrains.skia.ColorType.BGRA_8888,
            org.jetbrains.skia.ColorAlphaType.OPAQUE), frame, 1280 * 4)
    val encoded = checkNotNull(image.encodeToData(org.jetbrains.skia.EncodedImageFormat.JPEG, 95))
    try { Files.write(output, encoded.bytes) }
    finally { encoded.close(); image.close() }
    return output.toString()
}

/** Records already decoded frames, so recording can start at any displayed frame. */
internal class DesktopVideoRecorder(
    executable: String = System.getenv("HANPPIE_FFMPEG") ?: "ffmpeg",
    private val output: Path = desktopMediaPath("Movies", "mp4"),
) {
    private val temporary = output.resolveSibling("${output.fileName}.part")
    private val closing = AtomicBoolean(false)
    private val errors = StringBuilder()
    private val process = ProcessBuilder(executable, "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
        "-f", "rawvideo", "-pix_fmt", "bgra", "-s", "1280x720", "-r", "30", "-i", "pipe:0", "-an",
        "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-movflags", "+faststart",
        "-f", "mp4", temporary.toString()).start()
    private val errorThread = thread(name = "hanppie-recorder-errors", isDaemon = true) {
        process.errorStream.bufferedReader().useLines { lines ->
            lines.forEach { line -> synchronized(errors) { if (errors.length < 4096) errors.appendLine(line) } }
        }
    }
    private var frames = 0

    @Synchronized fun frame(bytes: ByteArray) {
        if (closing.get()) return
        require(bytes.size == 1280 * 720 * 4)
        process.outputStream.write(bytes)
        frames++
    }

    fun finish(): String {
        check(closing.compareAndSet(false, true)) { "Recording already finished" }
        synchronized(this) { process.outputStream.close() }
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            check(process.waitFor(2, TimeUnit.SECONDS)) { "FFmpeg recorder did not stop" }
        }
        errorThread.join(1000)
        val detail = synchronized(errors) { errors.toString().trim() }
        if (frames == 0 || process.exitValue() != 0) {
            Files.deleteIfExists(temporary)
            error(detail.ifBlank { "FFmpeg did not produce a recording" })
        }
        Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING)
        return output.toString()
    }

    fun discard() {
        if (!closing.compareAndSet(false, true)) return
        synchronized(this) { runCatching { process.outputStream.close() } }
        process.destroyForcibly()
        process.waitFor(2, TimeUnit.SECONDS)
        Files.deleteIfExists(temporary)
    }
}

private fun desktopMediaPath(folder: String, extension: String): Path {
    val directory = Path.of(System.getProperty("user.home"), folder, "Hanppie")
    Files.createDirectories(directory)
    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))
    return directory.resolve("Hanppie-$stamp.$extension")
}
