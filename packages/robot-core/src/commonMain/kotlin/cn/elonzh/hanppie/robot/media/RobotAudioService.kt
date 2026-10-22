package cn.elonzh.hanppie.robot.media

import cn.elonzh.hanppie.robot.protocol.Protocol
import cn.elonzh.hanppie.robot.protocol.u8
import cn.elonzh.hanppie.robot.session.RobotRequestException
import cn.elonzh.hanppie.robot.session.RequestProblem

/** Wire values differ from the App language enum: English and Chinese are swapped upstream. */
enum class SpeakerLanguage(val wireValue: Int) {
    ENGLISH(0), CHINESE(1), JAPANESE(2), GERMAN(4), SPANISH(5), KOREAN(6), FRENCH(7), RUSSIAN(8);
    companion object { fun fromWire(value: Int): SpeakerLanguage? = entries.firstOrNull { it.wireValue == value } }
}

/** Values read from this connection, not the application's saved playback preference. */
data class RobotAudioSettings(val volume: Int, val languageCode: Int) {
    val language: SpeakerLanguage? get() = SpeakerLanguage.fromWire(languageCode)
}

interface RobotAudioService {
    suspend fun read(): RobotAudioSettings
    suspend fun applyVolume(volume: Int): RobotAudioSettings
    suspend fun setLanguage(language: SpeakerLanguage): RobotAudioSettings
}

internal class SpeakerCommands(
    private val request: suspend (receiver: Int, set: Int, id: Int, payload: ByteArray) -> ByteArray,
) : RobotAudioService {
    private suspend fun command(id: Int, payload: ByteArray = byteArrayOf(), receiver: Int = Protocol.HOST_HDVT_UAV) =
        request(receiver, Protocol.CMDSET_RM, id, payload)

    override suspend fun read(): RobotAudioSettings {
        val volume = command(Protocol.CMD_RM_GET_SPEAKER_VOLUME)
        val language = command(0x17)
        if (volume.size != 2 || volume.u8(1) !in 0..100 || language.size != 2)
            throw RobotRequestException(RequestProblem.INVALID_RESPONSE)
        return RobotAudioSettings(volume.u8(1), language.u8(1))
    }

    override suspend fun applyVolume(volume: Int): RobotAudioSettings {
        require(volume in 0..100)
        // Preserve the current, packet-derived write route; do not replace it with the native App's 0x09 route.
        command(Protocol.CMD_RM_SET_SPEAKER_VOLUME, byteArrayOf(volume.toByte()), Protocol.HOST_CAMERA)
        return read().also {
            if (it.volume != volume) throw RobotRequestException(RequestProblem.NOT_APPLIED)
        }
    }

    override suspend fun setLanguage(language: SpeakerLanguage): RobotAudioSettings {
        command(0x16, byteArrayOf(language.wireValue.toByte()))
        return read().also {
            if (it.language != language) throw RobotRequestException(RequestProblem.NOT_APPLIED)
        }
    }
}
