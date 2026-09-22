package cn.elonzh.hanppie.robot.media

import cn.elonzh.hanppie.robot.protocol.hexBytes

enum class VideoResolution(
    val id: String,
    val label: String,
    val width: Int,
    val height: Int,
    val payloadHex: String,
) {
    R720P("720p", "720p", 1280, 720, "0403000000"),
    R1080P("1080p", "1080p", 1920, 1080, "0a03000000");

    val payload: ByteArray get() = payloadHex.hexBytes()

    companion object {
        fun fromId(id: String): VideoResolution =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: R720P
    }
}
