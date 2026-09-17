package cn.elonzh.hanppie.robot.lab

/**
 * One Lab custom audio resource carried inside the DSP container.
 *
 * The RoboMaster client keeps at most ten resources per program and stores them in the DSP XML as
 * `<audio id="0..9" type="opus">`; the machine maps `id` to the global sound id `0x10010 + id`, which is
 * what `rm_define.media_custom_audio_<id>` resolves to. [packets] is the client's own `.opus` container:
 * one little-endian 16-bit length followed by one Opus packet, repeated per 20 ms frame.
 */
class LabAudioClip(
    val id: Int,
    val name: String,
    val durationMillis: Long,
    val packets: ByteArray,
) {
    init {
        require(id in 0 until MAX_CLIPS) { "自定义音频编号必须在 0..${MAX_CLIPS - 1}" }
        require(name.isNotBlank() && name.length <= MAX_NAME_LENGTH) { "自定义音频名称长度必须在 1..$MAX_NAME_LENGTH" }
        require(durationMillis > 0) { "自定义音频时长必须为正数" }
        require(packets.isNotEmpty()) { "自定义音频编码结果为空" }
    }

    /** The Lab Python constant the generated script uses to play this resource. */
    val soundConstant: String get() = "rm_define.media_custom_audio_$id"

    /** Integer seconds, matching the value the RoboMaster client writes into the DSP audio node. */
    val durationSeconds: Int get() = ((durationMillis + 999) / 1000).toInt()

    /** Base64 body size this resource adds to the DSP payload. */
    val encodedBytes: Int get() = 4 * ((packets.size + 2) / 3)

    companion object {
        /** The client only offers ten custom audio slots per program. */
        const val MAX_CLIPS = 10
        const val MAX_NAME_LENGTH = 64

        /** Encoding parameters the RoboMaster client uses for `<audio type="opus">` resources. */
        const val SAMPLE_RATE = 48_000
        const val FRAME_SAMPLES = 960
        const val FRAME_DURATION_MILLIS = 20L
        const val BIT_RATE = 12_000

        /** Empty audio list, written by every program that carries no custom audio. */
        const val EMPTY_AUDIO_LIST = "<audio-list />"

        /** Base64 body size of a whole audio list, used to weigh custom audio against the DSP byte budget. */
        fun totalEncodedBytes(clips: List<LabAudioClip>): Int = clips.sumOf { it.encodedBytes }
    }
}
