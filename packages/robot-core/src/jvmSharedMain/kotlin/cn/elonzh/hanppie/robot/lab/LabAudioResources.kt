package cn.elonzh.hanppie.robot.lab

import cn.elonzh.hanppie.robot.protocol.hex
import java.security.MessageDigest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Renders the DSP `<audio-list>` payload that carries Lab custom audio resources.
 *
 * The RoboMaster client writes one `<audio>` node per resource: `id` 0..9, the user-visible `name`,
 * `type="opus"`, the integer `duration` in seconds, an eight-character MD5, a `modify` flag, and the
 * base64 body inside CDATA. Hanppie always sends the body with `modify="true"` because the native
 * "does this resource already exist" query (`0x0500_001C`) is not implemented; the MD5 therefore only
 * documents the body, and its exact casing against the machine-side comparison is unverified.
 */
@OptIn(ExperimentalEncodingApi::class)
fun labAudioListXml(clips: List<LabAudioClip>): String {
    require(clips.size <= LabAudioClip.MAX_CLIPS) { "自定义音频不能超过 ${LabAudioClip.MAX_CLIPS} 个" }
    require(clips.map { it.id }.toSet().size == clips.size) { "自定义音频编号重复" }
    if (clips.isEmpty()) return LabAudioClip.EMPTY_AUDIO_LIST
    return clips.sortedBy { it.id }
        .joinToString(prefix = "<audio-list>", postfix = "</audio-list>", separator = "") { labAudioNodeXml(it) }
}

@OptIn(ExperimentalEncodingApi::class)
internal fun labAudioNodeXml(clip: LabAudioClip): String {
    val body = Base64.Default.encode(clip.packets)
    val digest = MessageDigest.getInstance("MD5").digest(body.encodeToByteArray()).hex().take(8)
    return "<audio id=\"${clip.id}\" name=\"${xmlAttribute(clip.name)}\" type=\"opus\"" +
        " duration=\"${clip.durationSeconds}\" md5=\"$digest\" modify=\"true\">" +
        "<audio_data><![CDATA[$body]]></audio_data></audio>"
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
