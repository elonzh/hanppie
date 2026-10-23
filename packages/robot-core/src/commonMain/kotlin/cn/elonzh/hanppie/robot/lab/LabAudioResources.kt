package cn.elonzh.hanppie.robot.lab

import cn.elonzh.hanppie.robot.protocol.hex
import cn.elonzh.hanppie.robot.session.robotMd5
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** First four MD5 bytes of the Base64 body, encoded as eight lowercase hex characters. */
@OptIn(ExperimentalEncodingApi::class)
internal fun labAudioDigest(packets: ByteArray): String {
    val body = Base64.Default.encode(packets)
    return robotMd5(body.encodeToByteArray()).hex().take(8)
}

/**
 * Renders the DSP `<audio-list>` payload that carries Lab custom audio resources.
 *
 * The RoboMaster client writes one `<audio>` node per resource: `id` 0..9, the user-visible `name`,
 * `type="opus"`, the integer `duration` in seconds, an eight-character MD5, a `modify` flag, and the
 * base64 body inside CDATA.
 *
 * Only a current device digest match may authorize [isSlotCached] to omit the body with
 * `modify="false"`. Host history and FTP completion do not establish a device cache hit.
 */
@OptIn(ExperimentalEncodingApi::class)
fun labAudioListXml(
    clips: List<LabAudioClip>,
    isSlotCached: (slotId: Int, md5: String) -> Boolean = { _, _ -> false },
): String {
    require(clips.size <= LabAudioClip.MAX_CLIPS) { "自定义音频不能超过 ${LabAudioClip.MAX_CLIPS} 个" }
    require(clips.map { it.id }.toSet().size == clips.size) { "自定义音频编号重复" }
    if (clips.isEmpty()) return LabAudioClip.EMPTY_AUDIO_LIST
    return clips.sortedBy { it.id }
        .joinToString(prefix = "<audio-list>", postfix = "</audio-list>", separator = "") { clip ->
            val body = Base64.Default.encode(clip.packets)
            val digest = robotMd5(body.encodeToByteArray()).hex().take(8)
            val cached = isSlotCached(clip.id, digest)
            labAudioNodeXml(clip, digest, body, cached)
        }
}

internal fun labAudioNodeXml(
    clip: LabAudioClip,
    digest: String,
    body: String,
    cached: Boolean = false,
): String {
    val escapedName = xmlAttribute(clip.name)
    val header = "<audio id=\"${clip.id}\" name=\"$escapedName\" type=\"opus\"" +
        " duration=\"${clip.durationSeconds}\" md5=\"$digest\""
    return if (cached) {
        "$header modify=\"false\"></audio>"
    } else {
        "$header modify=\"true\"><audio_data><![CDATA[$body]]></audio_data></audio>"
    }
}

private fun xmlAttribute(value: String): String = buildString(value.length) {
    value.forEach { character ->
        when (character) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(character)
        }
    }
}
