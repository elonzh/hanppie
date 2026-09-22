package cn.elonzh.hanppie.ui.settings

import cn.elonzh.hanppie.robot.media.VideoResolution
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

internal object VideoResolutionSerializer : KSerializer<VideoResolution> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("VideoResolution", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: VideoResolution) {
        encoder.encodeString(value.id)
    }

    override fun deserialize(decoder: Decoder): VideoResolution {
        return VideoResolution.fromId(decoder.decodeString())
    }
}

@Serializable
internal data class MediaSettings(
    @Serializable(with = VideoResolutionSerializer::class)
    val videoResolution: VideoResolution = VideoResolution.R720P,
    val speakerVolume: Int = 50,
) {
    init {
        require(speakerVolume in 0..100) { "扬声器音量必须在 0 到 100 之间" }
    }
}
