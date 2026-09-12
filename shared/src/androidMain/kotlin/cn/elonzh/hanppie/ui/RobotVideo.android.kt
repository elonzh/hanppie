package cn.elonzh.hanppie.ui

import cn.elonzh.hanppie.resources.*

import android.media.*
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.PixelCopy
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import cn.elonzh.hanppie.robot.AnnexB
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*

/** Codec work never runs on the UDP receiver or UI thread. Queues are bounded. */
internal class RobotDecoder(
    private val surface: Surface,
    private val audio: Boolean,
    private val report: (String, Boolean) -> Unit,
    private val onRecordingStopped: () -> Unit = {},
) : AutoCloseable {
    private val videoQueue = ArrayBlockingQueue<ByteArray>(256)
    private val audioQueue = ArrayBlockingQueue<ByteArray>(64)
    private val active = AtomicBoolean(true)
    private val discontinuity = AtomicBoolean(false)
    private val recorder = AtomicReference<AndroidVideoRecorder?>(null)
    fun video(bytes: ByteArray) { if (!videoQueue.offer(bytes)) { videoQueue.clear(); discontinuity.set(true) } }
    fun audio(bytes: ByteArray) { if (audio && !audioQueue.offer(bytes)) { audioQueue.poll(); audioQueue.offer(bytes) } }
    @Synchronized fun startRecording(context: Context) {
        check(recorder.get() == null) { tr(Res.string.recording_already_running) }
        val next = AndroidVideoRecorder(context, audio)
        recorder.set(next)
    }
    @Synchronized fun stopRecording(): String? = recorder.getAndSet(null)?.finish()
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
                    recorder.get()?.let { activeRecorder ->
                        runCatching { activeRecorder.frame(frame, sps, pps, System.nanoTime() / 1_000) }.onFailure { error ->
                            if (recorder.compareAndSet(activeRecorder, null)) {
                                activeRecorder.discard()
                                onRecordingStopped()
                                report(tr(Res.string.recording_failed_value,
                                    error.message ?: error.javaClass.simpleName), false)
                            }
                        }
                    }
                    val index = decoder.dequeueInputBuffer(10000)
                    if (index >= 0) {
                        val buffer = requireNotNull(decoder.getInputBuffer(index)); buffer.clear()
                        check(frame.size <= buffer.remaining()) { tr(Res.string.video_frame_exceeds_decoder_buffer) }
                        buffer.put(frame)
                        decoder.queueInputBuffer(index, 0, frame.size, stamp, 0)
                        stamp += 33333
                    } else { discontinuity.set(true); break }
                    var output = decoder.dequeueOutputBuffer(info, 0)
                    while (output >= 0) {
                        decoder.releaseOutputBuffer(output, true); frames++
                        if (frames == 1 || frames % 30 == 0) report(tr(Res.string.video_value_decoded_frames,frames),false)
                        output = decoder.dequeueOutputBuffer(info, 0)
                    }
                }
            }
        } catch (e: Exception) { if (active.get()) report(tr(Res.string.video_error_value,e.message),false) }
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
                        val pcm = ByteArray(info.size)
                        buffer.get(pcm)
                        recorder.get()?.let { activeRecorder ->
                            runCatching { activeRecorder.audio(pcm, System.nanoTime() / 1_000) }.onFailure { error ->
                                if (recorder.compareAndSet(activeRecorder, null)) {
                                    activeRecorder.discard()
                                    onRecordingStopped()
                                    report(tr(Res.string.recording_failed_value,
                                        error.message ?: error.javaClass.simpleName), false)
                                }
                            }
                        }
                        buffer.position(info.offset); buffer.limit(info.offset + info.size)
                        val written = track.write(buffer, info.size, AudioTrack.WRITE_BLOCKING)
                        check(written >= 0) { tr(Res.string.audio_playback_failed_value,written) }
                        decodedBytes += written
                        if (decodedBytes == written.toLong() || decodedBytes % 9600 == 0L) report(tr(Res.string.audio_value_decoded_bytes,decodedBytes),true)
                    }
                    codec.releaseOutputBuffer(output, false)
                    output = codec.dequeueOutputBuffer(info, 0)
                }
            }
        } catch (e: Exception) { if (active.get()) report(tr(Res.string.audio_error_value,e.message),true) }
        finally { runCatching { track?.stop() }; track?.release(); runCatching { codec?.stop() }; codec?.release() }
    }, "hanppie-audio").apply { start() } else null
    override fun close() {
        active.set(false); videoThread.join(1000); audioThread?.join(1000)
        recorder.getAndSet(null)?.let { runCatching { it.finish() }
            .onSuccess { path -> report(tr(Res.string.video_saved_to_value, path), false) }
            .onFailure { error -> report(tr(Res.string.recording_failed_value,
                error.message ?: error.javaClass.simpleName), false) } }
    }
}

private class AndroidVideoOutput(private val context: Context) {
    private val resolver = context.contentResolver
    private val name = androidMediaName("mp4")
    private val uri: Uri?
    private val file: File?
    private val descriptor: android.os.ParcelFileDescriptor?
    val muxer: MediaMuxer
    val displayPath: String

    init {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/Hanppie")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            uri = checkNotNull(resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values))
            file = null
            descriptor = checkNotNull(resolver.openFileDescriptor(uri, "rw"))
            muxer = MediaMuxer(descriptor.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            displayPath = "${Environment.DIRECTORY_MOVIES}/Hanppie/$name"
        } else {
            uri = null
            descriptor = null
            val directory = checkNotNull(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)).resolve("Hanppie")
            check(directory.mkdirs() || directory.isDirectory)
            file = directory.resolve(name)
            muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            displayPath = file.absolutePath
        }
    }

    fun complete(success: Boolean) {
        val closeResult = runCatching { descriptor?.close() }
        try {
            if (uri != null) {
                if (success) resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                else resolver.delete(uri, null, null)
            } else if (!success) file?.delete()
        } finally { closeResult.getOrThrow() }
    }
}

private class AndroidVideoRecorder(context: Context, private val withAudio: Boolean) {
    private data class Sample(val bytes: ByteArray, val timestamp: Long, val flags: Int)

    private val output = AndroidVideoOutput(context.applicationContext)
    private val muxer = output.muxer
    private val audioEncoder = if (withAudio) MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48_000, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8_192)
        }
        configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        start()
    } else null
    private val pendingVideo = ArrayDeque<Sample>()
    private val pendingAudio = ArrayDeque<Sample>()
    private var videoTrack = -1
    private var audioTrack = -1
    private var started = false
    private var closed = false
    private var firstTimestamp = -1L
    private var videoSamples = 0
    private var audioSamples = 0
    private var audioInputs = 0
    private var lastAudioTimestamp = 0L

    @Synchronized fun frame(bytes: ByteArray, sps: ByteArray?, pps: ByteArray?, timestamp: Long) {
        if (closed) return
        val sample = bytes.withoutNalTypes(setOf(7, 8))
        val keyFrame = sample.hasNalType(5)
        if (videoTrack < 0) {
            if (!keyFrame || sps == null || pps == null) return
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 720).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(sps))
                setByteBuffer("csd-1", ByteBuffer.wrap(pps))
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            }
            videoTrack = muxer.addTrack(format)
            firstTimestamp = timestamp
            startMuxerIfReady()
        }
        val next = Sample(sample, timestamp, if (keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
        if (started) write(videoTrack, next) else {
            check(pendingVideo.size < 300) { tr(Res.string.no_robot_audio_recorded) }
            pendingVideo.addLast(next)
        }
    }

    @Synchronized fun audio(bytes: ByteArray, timestamp: Long) {
        if (closed || !withAudio || bytes.isEmpty()) return
        require(bytes.size % 2 == 0)
        val encoder = checkNotNull(audioEncoder)
        var offset = 0
        var attempts = 0
        while (offset < bytes.size) {
            drainAudio(end = false)
            val index = encoder.dequeueInputBuffer(10_000)
            if (index < 0) {
                check(++attempts < 100) { tr(Res.string.audio_encoder_not_accepting_input) }
                continue
            }
            attempts = 0
            val buffer = checkNotNull(encoder.getInputBuffer(index)).apply { clear() }
            var size = minOf(buffer.remaining(), bytes.size - offset)
            size -= size % 2
            check(size > 0)
            buffer.put(bytes, offset, size)
            val sampleTimestamp = timestamp + (offset / 2L) * 1_000_000L / 48_000L
            encoder.queueInputBuffer(index, 0, size, sampleTimestamp, 0)
            offset += size
            audioInputs += size
            lastAudioTimestamp = sampleTimestamp + (size / 2L) * 1_000_000L / 48_000L
        }
        drainAudio(end = false)
    }

    @Synchronized fun finish(): String {
        check(!closed) { tr(Res.string.recording_already_finished) }
        closed = true
        var success = false
        try {
            if (withAudio) {
                check(audioInputs > 0) { tr(Res.string.no_robot_audio_recorded) }
                finishAudio()
            }
            check(started && videoSamples > 0) { tr(Res.string.no_key_frame_recorded) }
            check(!withAudio || audioSamples > 0) { tr(Res.string.no_robot_audio_recorded) }
            muxer.stop()
            success = true
            return output.displayPath
        } finally {
            runCatching { audioEncoder?.stop() }
            audioEncoder?.release()
            val releaseResult = runCatching { muxer.release() }
            output.complete(success)
            releaseResult.getOrThrow()
        }
    }

    @Synchronized fun discard() {
        if (closed) return
        closed = true
        runCatching { audioEncoder?.stop() }
        audioEncoder?.release()
        if (started) runCatching { muxer.stop() }
        runCatching { muxer.release() }
        runCatching { output.complete(false) }
    }

    private fun finishAudio() {
        val encoder = checkNotNull(audioEncoder)
        var attempts = 0
        while (true) {
            val index = encoder.dequeueInputBuffer(10_000)
            if (index >= 0) {
                encoder.queueInputBuffer(index, 0, 0, lastAudioTimestamp, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                break
            }
            check(++attempts < 100) { tr(Res.string.audio_encoder_did_not_stop) }
        }
        drainAudio(end = true)
    }

    private fun drainAudio(end: Boolean) {
        val encoder = checkNotNull(audioEncoder)
        val info = MediaCodec.BufferInfo()
        var emptyPolls = 0
        while (true) {
            val index = encoder.dequeueOutputBuffer(info, if (end) 10_000 else 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!end || ++emptyPolls >= 100) {
                        check(!end) { tr(Res.string.audio_encoder_did_not_stop) }
                        return
                    }
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(audioTrack < 0)
                    audioTrack = muxer.addTrack(encoder.outputFormat)
                    startMuxerIfReady()
                }
                index >= 0 -> {
                    emptyPolls = 0
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val buffer = checkNotNull(encoder.getOutputBuffer(index)).apply {
                            position(info.offset); limit(info.offset + info.size)
                        }
                        val encoded = ByteArray(info.size)
                        buffer.get(encoded)
                        val sample = Sample(encoded, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv())
                        if (started) write(audioTrack, sample) else {
                            check(pendingAudio.size < 300) { tr(Res.string.no_key_frame_recorded) }
                            pendingAudio.addLast(sample)
                        }
                    }
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    encoder.releaseOutputBuffer(index, false)
                    if (eos) return
                }
            }
        }
    }

    private fun startMuxerIfReady() {
        if (started || videoTrack < 0 || (withAudio && audioTrack < 0)) return
        muxer.start()
        started = true
        pendingVideo.forEach { write(videoTrack, it) }
        pendingVideo.clear()
        pendingAudio.filter { it.timestamp >= firstTimestamp }.forEach { write(audioTrack, it) }
        pendingAudio.clear()
    }

    private fun write(track: Int, sample: Sample) {
        if (sample.timestamp < firstTimestamp) return
        val info = MediaCodec.BufferInfo().apply {
            set(0, sample.bytes.size, sample.timestamp - firstTimestamp, sample.flags)
        }
        muxer.writeSampleData(track, ByteBuffer.wrap(sample.bytes), info)
        if (track == videoTrack) videoSamples++ else audioSamples++
    }
}

private fun androidMediaName(extension: String): String {
    val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss-SSS", java.util.Locale.US).format(java.util.Date())
    return "Hanppie-$stamp.$extension"
}

private fun saveAndroidPhoto(context: Context, bitmap: Bitmap): String {
    val name = androidMediaName("jpg")
    if (Build.VERSION.SDK_INT >= 29) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Hanppie")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        var success = false
        try {
            resolver.openOutputStream(uri, "w")!!.use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            success = true
            return "${Environment.DIRECTORY_PICTURES}/Hanppie/$name"
        } finally { if (!success) resolver.delete(uri, null, null) }
    }
    val directory = checkNotNull(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)).resolve("Hanppie")
    check(directory.mkdirs() || directory.isDirectory)
    val output = directory.resolve(name)
    output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
    return output.absolutePath
}

@Composable internal actual fun RobotVideo(model: ConsoleModel, controls: RemoteMediaController, modifier: Modifier) {
    val context = LocalContext.current.applicationContext
    val uiScope = rememberCoroutineScope()
    var surface by remember { mutableStateOf<Surface?>(null) }
    var surfaceView by remember { mutableStateOf<SurfaceView?>(null) }
    var decoder by remember { mutableStateOf<RobotDecoder?>(null) }
    var playing by remember { mutableStateOf(true) }
    var sound by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var captureStatus by remember { mutableStateOf("") }
    var status by remember { mutableStateOf(tr(Res.string.video_off)) }
    var audioStatus by remember { mutableStateOf("") }
    val state by model.state.collectAsState()
    val requests by controls.requests.collectAsState()
    fun takePhoto() {
        val view = surfaceView ?: return
        if (view.width <= 0 || view.height <= 0 || !state.connected || !playing) return
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        captureStatus = tr(Res.string.saving_photo)
        PixelCopy.request(view, bitmap, { result ->
            if (result != PixelCopy.SUCCESS) {
                bitmap.recycle()
                captureStatus = tr(Res.string.photo_failed_value, "PixelCopy $result")
            } else uiScope.launch {
                val saved = withContext(Dispatchers.IO) { runCatching { saveAndroidPhoto(context, bitmap) } }
                bitmap.recycle()
                captureStatus = saved.fold(
                    { tr(Res.string.photo_saved_to_value, it) },
                    { tr(Res.string.photo_failed_value, it.message ?: it.javaClass.simpleName) })
            }
        }, Handler(Looper.getMainLooper()))
    }
    fun toggleRecording() {
        val activeDecoder = decoder ?: return
        if (!state.connected || !playing) return
        if (!recording) try {
            activeDecoder.startRecording(context)
            recording = true
            controls.recording(true)
            captureStatus = tr(Res.string.recording_waiting_for_key_frame)
        } catch (error: Exception) {
            controls.recording(false)
            captureStatus = tr(Res.string.recording_failed_value, error.message ?: error.javaClass.simpleName)
        } else {
            recording = false
            controls.recording(false)
            captureStatus = tr(Res.string.saving_recording)
            uiScope.launch {
                val saved = withContext(Dispatchers.IO) { runCatching { activeDecoder.stopRecording() } }
                captureStatus = saved.fold(
                    { path -> path?.let { tr(Res.string.video_saved_to_value, it) } ?: tr(Res.string.recording_failed_value, tr(Res.string.recording_already_finished)) },
                    { tr(Res.string.recording_failed_value, it.message ?: it.javaClass.simpleName) })
            }
        }
    }
    LaunchedEffect(requests.photo) { if (requests.photo > 0) takePhoto() }
    LaunchedEffect(requests.recording) { if (requests.recording > 0) toggleRecording() }
    LaunchedEffect(requests.robotMicrophone) {
        if (requests.robotMicrophone > 0 && !recording) { sound = !sound; if (sound) playing = true }
    }
    DisposableEffect(surface, playing, sound, state.connected) {
        status = if (!state.connected) "" else if (playing) tr(Res.string.waiting_for_video) else tr(Res.string.video_off)
        audioStatus = if (sound && state.connected) tr(Res.string.waiting_for_audio) else ""
        val activeDecoder = if (surface != null && playing && state.connected) RobotDecoder(surface!!, sound,
            report = { message, isAudio -> uiScope.launch { if (isAudio) audioStatus = message else status = message } },
            onRecordingStopped = { uiScope.launch { recording = false; controls.recording(false) } }) else null
        decoder = activeDecoder
        if (activeDecoder != null) { model.videoSink = activeDecoder::video; model.audioSink = activeDecoder::audio; model.startMedia(sound) }
        onDispose {
            if (decoder === activeDecoder) decoder = null
            recording = false
            controls.recording(false)
            if (activeDecoder != null) { model.stopMedia(); activeDecoder.close() }
        }
    }
    Box(modifier) {
        AndroidView(factory = { viewContext -> SurfaceView(viewContext).apply {
            surfaceView = this
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) { surface = holder.surface }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                override fun surfaceDestroyed(holder: SurfaceHolder) { surface = null }
            })
        } }, modifier = Modifier.fillMaxSize())
        Column(Modifier.align(Alignment.TopEnd).padding(end = HanppieDesignTokens.RemoteEdgePadding,
            top = HanppieDesignTokens.RemoteEdgePadding), horizontalAlignment = Alignment.End) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HudIconButton(if(playing) tr(Res.string.stop_video) else tr(Res.string.start_video), if(playing) WorkbenchGlyph.VIDEO else WorkbenchGlyph.VIDEO_OFF, state.connected) { playing = !playing }
                HudIconButton(if(sound) tr(Res.string.mute) else tr(Res.string.listen), if(sound) WorkbenchGlyph.SPEAKER else WorkbenchGlyph.MUTED,
                    state.connected && !recording) { controls.toggleRobotMicrophone() }
                HudIconButton(tr(Res.string.take_photo), WorkbenchGlyph.CAMERA, state.connected && playing && surfaceView != null) { controls.takePhoto() }
                HudIconButton(if(recording) tr(Res.string.stop_recording) else tr(Res.string.start_recording), if(recording) WorkbenchGlyph.STOP else WorkbenchGlyph.RECORD,
                    state.connected && playing && decoder != null) { controls.toggleRecording() }
            }
            Text(status, Modifier.widthIn(max = 300.dp), color = Color.White, fontSize = 11.sp, maxLines = 1)
            if (sound) Text(audioStatus, Modifier.widthIn(max = 300.dp), color = Color.White, fontSize = 11.sp, maxLines = 1)
            if (captureStatus.isNotBlank()) Text(captureStatus, Modifier.widthIn(max = 300.dp), color = Color.White, fontSize = 11.sp, maxLines = 1)
        }
    }
}
