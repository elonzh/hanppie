package cn.elonzh.hanppie.desktop

import java.io.EOFException
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
    fun video(bytes: ByteArray) { if (active.get() && !videoQueue.offer(bytes)) fail(tr("视频输入积压，请重新开启视频")) }
    fun audio(bytes: ByteArray) { if (active.get() && !audioQueue.offer(bytes)) fail(tr("音频输入积压，请重新开启监听")) }

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
                                if (n < 0) throw EOFException(tr("视频解码器已退出"))
                                offset += n
                            }
                            onVideo(frame)
                            if (++count == 1 || count % 30 == 0) onStatus(tr("视频已解码 {0} 帧",count))
                        }
                    } catch (e: Exception) { if (active.get()) fail(e.message ?: tr("视频解码失败")) }
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
                            if (n == 0 || n % 2 != 0) throw EOFException(tr("音频解码器已退出"))
                            if (pcmSink != null) pcmSink.invoke(bytes,n) else line?.write(bytes,0,n)
                            count += n
                            if (count == n.toLong() || count % 19200 == 0L) onStatus(tr("音频已解码 {0} 字节",count))
                        }
                    } catch (e: Exception) { if (active.get()) fail(e.message ?: tr("音频播放失败")) }
                }
            }
        } catch (e: Exception) { close(); throw IllegalStateException(tr("无法启动 FFmpeg；请安装或设置 HANPPIE_FFMPEG：{0}",e.message),e) }
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
            } catch (e: Exception) { if (active.get()) fail(e.message ?: tr("媒体传输失败")) }
        }
    }
    private fun fail(message: String) {
        if (active.getAndSet(false)) {
            onStatus(tr("媒体错误：{0}",message))
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
