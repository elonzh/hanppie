package cn.elonzh.hanppie.desktop

import android.media.*
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import cn.elonzh.hanppie.robot.AnnexB
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import top.yukonga.miuix.kmp.basic.*

/** Codec work never runs on the UDP receiver or UI thread. Queues are bounded. */
internal class RobotDecoder(private val surface: Surface, private val audio: Boolean, private val report: (String, Boolean) -> Unit) : AutoCloseable {
    private val videoQueue = ArrayBlockingQueue<ByteArray>(256)
    private val audioQueue = ArrayBlockingQueue<ByteArray>(64)
    private val active = AtomicBoolean(true)
    private val discontinuity = AtomicBoolean(false)
    fun video(bytes: ByteArray) { if (!videoQueue.offer(bytes)) { videoQueue.clear(); discontinuity.set(true) } }
    fun audio(bytes: ByteArray) { if (audio && !audioQueue.offer(bytes)) { audioQueue.poll(); audioQueue.offer(bytes) } }
    private val videoThread = Thread({
        var codec: MediaCodec? = null
        try {
            val parser = AnnexB(); val units = cn.elonzh.hanppie.robot.H264AccessUnits()
            var sps: ByteArray? = null; var pps: ByteArray? = null
            var frames = 0; var stamp = 0L
            val info = MediaCodec.BufferInfo()
            while (active.get()) {
                if (discontinuity.getAndSet(false)) { parser.reset(); units.reset(); codec?.stop(); codec?.release(); codec = null; sps = null; pps = null }
                val chunk = videoQueue.poll(30, TimeUnit.MILLISECONDS)
                if (chunk != null) for (nal in parser.accept(chunk)) {
                    val start = if (nal[2] == 1.toByte()) 3 else 4
                    if (nal.size <= start) continue
                    val type = nal[start].toInt() and 31
                    if (type == 7) sps = nal
                    if (type == 8) pps = nal
                    if (codec == null && sps != null && pps != null) {
                        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 720)
                        format.setByteBuffer("csd-0", ByteBuffer.wrap(sps))
                        format.setByteBuffer("csd-1", ByteBuffer.wrap(pps))
                        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2_000_000)
                        codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply { configure(format, surface, null, 0); start() }
                    }
                    val frame = units.accept(nal) ?: continue
                    val decoder = codec ?: continue
                    val index = decoder.dequeueInputBuffer(10000)
                    if (index >= 0) {
                        val buffer = requireNotNull(decoder.getInputBuffer(index)); buffer.clear()
                        check(frame.size <= buffer.remaining()) { tr("视频帧超过解码缓冲区") }
                        buffer.put(frame)
                        decoder.queueInputBuffer(index, 0, frame.size, stamp, 0)
                        stamp += 33333
                    } else { discontinuity.set(true); break }
                    var output = decoder.dequeueOutputBuffer(info, 0)
                    while (output >= 0) {
                        decoder.releaseOutputBuffer(output, true); frames++
                        if (frames == 1 || frames % 30 == 0) report(tr("视频已解码 {0} 帧",frames),false)
                        output = decoder.dequeueOutputBuffer(info, 0)
                    }
                }
            }
        } catch (e: Exception) { if (active.get()) report(tr("视频错误：{0}",e.message),false) }
        finally { runCatching { codec?.stop() }; codec?.release() }
    }, "hanppie-video").apply { start() }
    private val audioThread = if (audio) Thread({
        var codec: MediaCodec? = null; var track: AudioTrack? = null
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 48000, 1)
            val head = "OpusHead".toByteArray() + byteArrayOf(1,1,0,0,0x80.toByte(),0xbb.toByte(),0,0,0,0,0)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(head))
            format.setByteBuffer("csd-1", ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(0).apply { flip() })
            format.setByteBuffer("csd-2", ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(80000000).apply { flip() })
            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS).apply { configure(format, null, null, 0); start() }
            track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2).build()
            track.play()
            val info = MediaCodec.BufferInfo(); var stamp = 0L; var decodedBytes = 0L
            while (active.get()) {
                val packet = audioQueue.poll(30, TimeUnit.MILLISECONDS) ?: continue
                val input = codec.dequeueInputBuffer(10000)
                if (input >= 0) {
                    codec.getInputBuffer(input)!!.apply { clear(); put(packet) }
                    codec.queueInputBuffer(input, 0, packet.size, stamp, 0); stamp += 20000
                }
                var output = codec.dequeueOutputBuffer(info, 10000)
                while (output >= 0) {
                    codec.getOutputBuffer(output)?.let { buffer ->
                        buffer.position(info.offset); buffer.limit(info.offset + info.size)
                        val written = track.write(buffer, info.size, AudioTrack.WRITE_BLOCKING)
                        check(written >= 0) { tr("音频播放失败：{0}",written) }
                        decodedBytes += written
                        if (decodedBytes == written.toLong() || decodedBytes % 9600 == 0L) report(tr("音频已解码 {0} 字节",decodedBytes),true)
                    }
                    codec.releaseOutputBuffer(output, false)
                    output = codec.dequeueOutputBuffer(info, 0)
                }
            }
        } catch (e: Exception) { if (active.get()) report(tr("音频错误：{0}",e.message),true) }
        finally { runCatching { track?.stop() }; track?.release(); runCatching { codec?.stop() }; codec?.release() }
    }, "hanppie-audio").apply { start() } else null
    override fun close() { active.set(false); videoThread.join(1000); audioThread?.join(1000) }
}

@Composable internal actual fun RobotVideo(model: ConsoleModel, modifier: Modifier) {
    var surface by remember { mutableStateOf<Surface?>(null) }
    var playing by remember { mutableStateOf(false) }
    var sound by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(tr("视频未开启")) }
    var audioStatus by remember { mutableStateOf("") }
    val state by model.state.collectAsState()
    DisposableEffect(surface, playing, sound, state.connected) {
        status = if (playing) tr("等待视频帧…") else tr("视频未开启")
        audioStatus = if (sound) tr("等待音频…") else ""
        val decoder = if (surface != null && playing && state.connected) RobotDecoder(surface!!, sound) { message, isAudio ->
            if (isAudio) audioStatus = message else status = message
        } else null
        if (decoder != null) { model.videoSink = decoder::video; model.audioSink = decoder::audio; model.startMedia(sound) }
        onDispose { if (decoder != null) { model.stopMedia(); decoder.close() } }
    }
    Box(modifier) {
        AndroidView(factory = { context -> SurfaceView(context).apply {
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) { surface = holder.surface }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                override fun surfaceDestroyed(holder: SurfaceHolder) { surface = null }
            })
        } }, modifier = Modifier.fillMaxSize())
        Column(Modifier.padding(start=12.dp,top=64.dp)) {
        Row {
            HudButton(if(playing) tr("关闭视频") else tr("开启视频"),if(playing) tr("关闭视频") else tr("开启视频"),state.connected) { playing = !playing }
            HudButton(if(sound) tr("静音") else tr("监听机器人"),if(sound) tr("静音") else tr("监听机器人"),state.connected) { sound = !sound; if(sound) playing=true }
        }
        Text(tr(status),color=Color.White)
        if (sound) Text(tr(audioStatus),color=Color.White)
        }
    }
}
