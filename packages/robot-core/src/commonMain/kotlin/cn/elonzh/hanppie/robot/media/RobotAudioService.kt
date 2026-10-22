package cn.elonzh.hanppie.robot.media

import cn.elonzh.hanppie.robot.protocol.Protocol
import cn.elonzh.hanppie.robot.protocol.DussFrame
import cn.elonzh.hanppie.robot.protocol.u8
import cn.elonzh.hanppie.robot.session.RobotRequestException
import cn.elonzh.hanppie.robot.session.RequestProblem
import cn.elonzh.hanppie.robot.session.RobotLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.Volatile

/** Wire values differ from the App language enum: English and Chinese are swapped upstream. */
enum class SpeakerLanguage(val wireValue: Int) {
    ENGLISH(0), CHINESE(1), JAPANESE(2), GERMAN(4), SPANISH(5), KOREAN(6), FRENCH(7), RUSSIAN(8);
    companion object { fun fromWire(value: Int): SpeakerLanguage? = entries.firstOrNull { it.wireValue == value } }
}

/** Values read from this connection, not the application's saved playback preference. */
data class RobotAudioSettings(val volume: Int, val languageCode: Int) {
    val language: SpeakerLanguage? get() = SpeakerLanguage.fromWire(languageCode)
}

/** Most recent device task push, not proof that this app's current recording was played. */
data class RobotSpeakerPlayback(val taskId: Int, val soundId: Long, val progress: Int, val rawStatus: Int) {
    val playing: Boolean get() = rawStatus and 3 == 0
    val percent: Int? get() = progress.takeIf { it in 0..100 }
}

interface RobotAudioService {
    val playback: StateFlow<RobotSpeakerPlayback?>
    suspend fun read(): RobotAudioSettings
    suspend fun applyVolume(volume: Int): RobotAudioSettings
    suspend fun setLanguage(language: SpeakerLanguage): RobotAudioSettings
    suspend fun readSoundEnabled(): Boolean
    /** Native feedback suppression is write-only; return after acknowledgement, not acoustic verification. */
    suspend fun setAntiFeedback(enabled: Boolean)
}

internal class SpeakerCommands(
    private val request: suspend (receiver: Int, set: Int, id: Int, payload: ByteArray) -> ByteArray,
    private val nowMillis: () -> Long,
) : RobotAudioService {
    private val lock = RobotLock()
    private val mutablePlayback = MutableStateFlow<RobotSpeakerPlayback?>(null)
    override val playback = mutablePlayback.asStateFlow()
    private var lastPlaybackAt = 0L
    @Volatile private var closed = false

    private suspend fun command(id: Int, payload: ByteArray = byteArrayOf(), receiver: Int = Protocol.HOST_HDVT_UAV): ByteArray {
        if (closed) throw RobotRequestException(RequestProblem.DISCONNECTED)
        return request(receiver, Protocol.CMDSET_RM, id, payload)
    }

    override suspend fun readSoundEnabled(): Boolean {
        val response = command(0x1f, receiver = Protocol.HOST_CAMERA)
        if (response.size != 2) throw RobotRequestException(RequestProblem.INVALID_RESPONSE)
        // The native converter tests for nonzero, rather than comparing strictly with 1.
        return response.u8(1) != 0
    }

    override suspend fun setAntiFeedback(enabled: Boolean) {
        command(0xe1, byteArrayOf(1, 0x0c, 1, if (enabled) 1 else 2), Protocol.HOST_CAMERA)
    }

    fun receive(frame: DussFrame) {
        if (!frame.valid || frame.sender != Protocol.HOST_HDVT_UAV || frame.receiver != Protocol.HOST_MOBILE ||
            frame.attr != Protocol.ATTR_NO_ACK || frame.set != Protocol.CMDSET_RM || frame.id != 0xb4 || frame.payload.size < 7) return
        lock.withLock {
            if (closed) return@withLock
            val p = frame.payload
            val soundId = (0..3).fold(0L) { value, index -> value or (p.u8(index + 3).toLong() shl (index * 8)) }
            lastPlaybackAt = nowMillis()
            mutablePlayback.value = RobotSpeakerPlayback(p.u8(0), soundId, p.u8(1), p.u8(2))
        }
    }

    fun expire() = lock.withLock {
        if (nowMillis() - lastPlaybackAt >= 1_500) mutablePlayback.value = null
    }

    fun close() = lock.withLock { closed = true; mutablePlayback.value = null }

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
