package cn.elonzh.hanppie.ui.robot.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import cn.elonzh.hanppie.robot.lab.LabAudioClip
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private val androidAudioLogger = KotlinLogging.logger {}

internal class AndroidLabAudioPlayer : LabAudioPlayer {
    private val _state = MutableStateFlow(AudioPlaybackState())
    override val state: StateFlow<AudioPlaybackState> = _state

    private val lock = Any()
    private var activeClip: LabAudioClip? = null
    private var track: AudioTrack? = null
    private var codec: MediaCodec? = null
    private var playThread: Thread? = null
    private val isPaused = AtomicBoolean(false)
    private val shouldStop = AtomicBoolean(false)

    override fun play(clip: LabAudioClip) {
        synchronized(lock) {
            stopLocked()
            activeClip = clip
            isPaused.set(false)
            shouldStop.set(false)

            _state.value = AudioPlaybackState(
                playingClipId = clip.id,
                isPlaying = true,
                positionMillis = 0L,
                durationMillis = clip.durationMillis,
            )

            lateinit var thisThread: Thread
            thisThread = thread(name = "android-audio-player-${clip.id}", isDaemon = true) {
                var localTrack: AudioTrack? = null
                var localCodec: MediaCodec? = null
                try {
                    val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 48000, 1)
                    val head = "OpusHead".toByteArray() + byteArrayOf(1, 1, 0, 0, 0x80.toByte(), 0xbb.toByte(), 0, 0, 0, 0, 0)
                    format.setByteBuffer("csd-0", ByteBuffer.wrap(head))
                    format.setByteBuffer("csd-1", ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(0).apply { flip() })
                    format.setByteBuffer("csd-2", ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(80000000).apply { flip() })

                    localCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS).apply {
                        configure(format, null, null, 0)
                        start()
                    }
                    val bufferSize = AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2
                    localTrack = AudioTrack.Builder()
                        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setSampleRate(48000)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .build(),
                        )
                        .setBufferSizeInBytes(bufferSize)
                        .build()
                    localTrack.play()

                    synchronized(lock) {
                        track = localTrack
                        codec = localCodec
                    }

                    // Extract all frames
                    val packets = clip.packets
                    var offset = 0
                    val frames = mutableListOf<ByteArray>()
                    while (offset + 2 <= packets.size) {
                        val len = (packets[offset].toInt() and 0xff) or ((packets[offset + 1].toInt() and 0xff) shl 8)
                        if (offset + 2 + len <= packets.size) {
                            frames += packets.copyOfRange(offset + 2, offset + 2 + len)
                        }
                        offset += 2 + len
                    }

                    var frameIndex = 0
                    val info = MediaCodec.BufferInfo()
                    var inputDone = false
                    var outputDone = false
                    var presentationTimeUs = 0L
                    var totalPcmBytesWritten = 0L

                    while (!outputDone && !shouldStop.get()) {
                        if (isPaused.get()) {
                            try {
                                Thread.sleep(30)
                            } catch (e: InterruptedException) {
                                break
                            }
                            continue
                        }

                        if (!inputDone) {
                            val inIndex = localCodec.dequeueInputBuffer(10_000)
                            if (inIndex >= 0) {
                                val buffer = localCodec.getInputBuffer(inIndex)!!
                                buffer.clear()
                                if (frameIndex < frames.size) {
                                    val frame = frames[frameIndex++]
                                    buffer.put(frame)
                                    localCodec.queueInputBuffer(inIndex, 0, frame.size, presentationTimeUs, 0)
                                    presentationTimeUs += 20_000
                                } else {
                                    localCodec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputDone = true
                                }
                            }
                        }

                        var outIndex = localCodec.dequeueOutputBuffer(info, 10_000)
                        while (outIndex >= 0) {
                            if (info.size > 0) {
                                val outBuffer = localCodec.getOutputBuffer(outIndex)!!
                                outBuffer.position(info.offset)
                                outBuffer.limit(info.offset + info.size)
                                val chunk = ByteArray(info.size)
                                outBuffer.get(chunk)

                                val written = localTrack.write(chunk, 0, chunk.size)
                                if (written > 0) {
                                    totalPcmBytesWritten += written
                                    val elapsedMillis = (totalPcmBytesWritten * 1000L) / (48000L * 2L)
                                    if (!isPaused.get() && !shouldStop.get()) {
                                        _state.value = AudioPlaybackState(
                                            playingClipId = clip.id,
                                            isPlaying = true,
                                            positionMillis = elapsedMillis.coerceAtMost(clip.durationMillis),
                                            durationMillis = clip.durationMillis,
                                        )
                                    }
                                }
                            }
                            outputDone = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            localCodec.releaseOutputBuffer(outIndex, false)
                            if (outputDone) break
                            outIndex = localCodec.dequeueOutputBuffer(info, 0)
                        }
                    }
                } catch (e: Exception) {
                    androidAudioLogger.debug(e) { "Android audio playback error" }
                } finally {
                    runCatching { localTrack?.stop() }
                    runCatching { localTrack?.release() }
                    runCatching { localCodec?.stop() }
                    runCatching { localCodec?.release() }
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
                runCatching { track?.pause() }
                _state.value = _state.value.copy(isPlaying = false)
            }
        }
    }

    override fun resume() {
        synchronized(lock) {
            val clip = activeClip ?: return
            if (isPaused.compareAndSet(true, false)) {
                runCatching { track?.play() }
                _state.value = _state.value.copy(isPlaying = true)
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
        runCatching { track?.stop() }
        runCatching { track?.release() }
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        track = null
        codec = null
        activeClip = null
        isPaused.set(false)
        _state.value = AudioPlaybackState()
    }

    override fun close() {
        stop()
    }
}
