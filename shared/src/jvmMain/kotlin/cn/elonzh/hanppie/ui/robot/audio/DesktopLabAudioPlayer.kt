package cn.elonzh.hanppie.ui.robot.audio

import cn.elonzh.hanppie.robot.lab.LabAudioClip
import cn.elonzh.hanppie.ui.robot.remote.OpusOgg
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private val desktopAudioLogger = KotlinLogging.logger {}

internal class DesktopLabAudioPlayer(
    private val executable: String = System.getenv("HANPPIE_FFMPEG") ?: "ffmpeg",
) : LabAudioPlayer {
    private val _state = MutableStateFlow(AudioPlaybackState())
    override val state: StateFlow<AudioPlaybackState> = _state

    private val lock = Any()
    private var activeClip: LabAudioClip? = null
    private var pcmData: ByteArray? = null
    private var pcmPosition = 0
    private var line: SourceDataLine? = null
    private var playThread: Thread? = null
    private val isPaused = AtomicBoolean(false)
    private val shouldStop = AtomicBoolean(false)

    override fun play(clip: LabAudioClip) {
        synchronized(lock) {
            stopLocked()
            activeClip = clip
            isPaused.set(false)
            shouldStop.set(false)

            val pcm = try {
                decodeToPcm(clip)
            } catch (e: Exception) {
                desktopAudioLogger.error(e) { "Failed to decode audio clip ${clip.id} (${clip.name})" }
                _state.value = AudioPlaybackState()
                return
            }

            pcmData = pcm
            pcmPosition = 0

            val format = AudioFormat(48000f, 16, 1, true, false)
            val dataLine = try {
                AudioSystem.getSourceDataLine(format).apply {
                    open(format, 9600)
                    start()
                }
            } catch (e: Exception) {
                desktopAudioLogger.error(e) { "Failed to open audio line" }
                _state.value = AudioPlaybackState()
                return
            }
            line = dataLine

            _state.value = AudioPlaybackState(
                playingClipId = clip.id,
                isPlaying = true,
                positionMillis = 0L,
                durationMillis = clip.durationMillis,
            )

            lateinit var thisThread: Thread
            thisThread = thread(name = "desktop-audio-player-${clip.id}", isDaemon = true) {
                val chunkSize = 1920 // 20ms of 48kHz mono 16-bit PCM
                try {
                    while (!shouldStop.get()) {
                        if (isPaused.get()) {
                            try {
                                Thread.sleep(30)
                            } catch (e: InterruptedException) {
                                break
                            }
                            continue
                        }
                        val currentPos = synchronized(lock) { pcmPosition }
                        if (currentPos >= pcm.size) break

                        val toWrite = minOf(chunkSize, pcm.size - currentPos)
                        val written = dataLine.write(pcm, currentPos, toWrite)
                        if (written <= 0) break

                        val newPos = synchronized(lock) {
                            pcmPosition += written
                            pcmPosition
                        }
                        val elapsedMillis = (newPos.toLong() * 1000L) / (48000L * 2L)
                        if (!isPaused.get() && !shouldStop.get()) {
                            _state.value = AudioPlaybackState(
                                playingClipId = clip.id,
                                isPlaying = true,
                                positionMillis = elapsedMillis.coerceAtMost(clip.durationMillis),
                                durationMillis = clip.durationMillis,
                            )
                        }
                    }
                    if (!shouldStop.get() && !isPaused.get()) {
                        dataLine.drain()
                    }
                } catch (e: Exception) {
                    desktopAudioLogger.debug(e) { "Audio playback interrupted" }
                } finally {
                    synchronized(lock) {
                        if (playThread === thisThread) {
                            stopLocked()
                        }
                    }
                }
            }
            playThread = thisThread
        }
    }

    override fun pause() {
        synchronized(lock) {
            val clip = activeClip ?: return
            if (isPaused.compareAndSet(false, true)) {
                line?.stop()
                val elapsedMillis = (pcmPosition.toLong() * 1000L) / (48000L * 2L)
                _state.value = AudioPlaybackState(
                    playingClipId = clip.id,
                    isPlaying = false,
                    positionMillis = elapsedMillis.coerceAtMost(clip.durationMillis),
                    durationMillis = clip.durationMillis,
                )
            }
        }
    }

    override fun resume() {
        synchronized(lock) {
            val clip = activeClip ?: return
            if (isPaused.compareAndSet(true, false)) {
                line?.start()
                val elapsedMillis = (pcmPosition.toLong() * 1000L) / (48000L * 2L)
                _state.value = AudioPlaybackState(
                    playingClipId = clip.id,
                    isPlaying = true,
                    positionMillis = elapsedMillis.coerceAtMost(clip.durationMillis),
                    durationMillis = clip.durationMillis,
                )
            }
        }
    }

    override fun stop() {
        synchronized(lock) {
            stopLocked()
        }
    }

    private fun stopLocked() {
        shouldStop.set(true)
        val oldThread = playThread
        playThread = null
        oldThread?.interrupt()
        runCatching { line?.stop() }
        runCatching { line?.close() }
        line = null
        activeClip = null
        pcmData = null
        pcmPosition = 0
        isPaused.set(false)
        _state.value = AudioPlaybackState()
    }

    override fun close() {
        stop()
    }

    private fun decodeToPcm(clip: LabAudioClip): ByteArray {
        val oggBytes = encodeToOgg(clip.packets)
        val process = ProcessBuilder(
            listOf(
                executable, "-hide_banner", "-loglevel", "error", "-nostdin",
                "-f", "ogg", "-i", "pipe:0", "-vn", "-ar", "48000", "-ac", "1",
                "-f", "s16le", "pipe:1",
            ),
        ).start()

        val output = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()
        val streamFailure = AtomicReference<Throwable?>()

        val writer = thread(isDaemon = true) {
            runCatching { process.outputStream.use { it.write(oggBytes) } }
                .onFailure { streamFailure.compareAndSet(null, it) }
        }
        val reader = thread(isDaemon = true) {
            runCatching { process.inputStream.use { it.copyTo(output) } }
                .onFailure { streamFailure.compareAndSet(null, it) }
        }
        val errReader = thread(isDaemon = true) {
            runCatching { process.errorStream.use { it.copyTo(error) } }
                .onFailure { streamFailure.compareAndSet(null, it) }
        }

        try {
            check(process.waitFor(30, TimeUnit.SECONDS)) { "Audio decoding timed out" }
            writer.join(1000)
            reader.join(1000)
            errReader.join(1000)
            check(process.exitValue() == 0) {
                error.toString(Charsets.UTF_8).trim().ifBlank { "FFmpeg decode failed" }
            }
            streamFailure.get()?.let { throw IllegalStateException("Audio stream error", it) }
            return output.toByteArray()
        } finally {
            runCatching { process.outputStream.close() }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            if (process.isAlive) {
                process.destroy()
                if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly()
            }
        }
    }

    private fun encodeToOgg(packets: ByteArray): ByteArray {
        var offset = 0
        val frames = mutableListOf<ByteArray>()
        while (offset + 2 <= packets.size) {
            val length = (packets[offset].toInt() and 0xff) or ((packets[offset + 1].toInt() and 0xff) shl 8)
            if (offset + 2 + length <= packets.size) {
                frames += packets.copyOfRange(offset + 2, offset + 2 + length)
            }
            offset += 2 + length
        }
        val ogg = OpusOgg()
        val bos = ByteArrayOutputStream()
        bos.write(ogg.headers())
        for (frame in frames) {
            bos.write(ogg.packet(frame))
        }
        return bos.toByteArray()
    }
}
