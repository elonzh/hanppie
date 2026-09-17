package cn.elonzh.hanppie.ui.robot.audio

import cn.elonzh.hanppie.robot.lab.LabAudioClip

/** One host audio file decoded into the container the Lab client stores for `<audio type="opus">`. */
internal class DecodedLabAudio(val durationMillis: Long, val packets: ByteArray)

/**
 * Platform capability: turn a host audio file into the DSP custom-audio container (48 kHz mono,
 * 20 ms frames, one little-endian 16-bit length followed by one Opus packet per frame).
 *
 * Implementations may spawn a codec process or use the platform codec, so callers must serialize
 * calls per instance and must not call this from the UI thread.
 */
internal interface LabAudioImporter : AutoCloseable {
    suspend fun decode(name: String, bytes: ByteArray): DecodedLabAudio
    override fun close() = Unit
}

/** Declared capability boundary for platforms without an Opus encode path. */
internal class NoLabAudioImporter(
    private val reason: String = "当前平台没有可用的音频导入",
) : LabAudioImporter {
    override suspend fun decode(name: String, bytes: ByteArray): DecodedLabAudio = error(reason)
}
