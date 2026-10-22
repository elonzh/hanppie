package cn.elonzh.hanppie.robot.network

import cn.elonzh.hanppie.robot.protocol.Protocol
import cn.elonzh.hanppie.robot.protocol.u8
import cn.elonzh.hanppie.robot.session.RequestProblem
import cn.elonzh.hanppie.robot.session.RobotRequestException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.concurrent.Volatile

data class RobotApCredentials(val ssid: String, val password: String) {
    init { require(validSsid(ssid) && validPassword(password)) { "热点名称或密码格式无效" } }
    override fun toString(): String = "RobotApCredentials(<redacted>)"
    companion object {
        fun validSsid(value: String): Boolean = Regex("[-_a-zA-Z0-9]{1,32}").matches(value)
        fun validPassword(value: String): Boolean = value.length in 8..16 && value.none { it.isISOControl() } &&
            runCatching { value.encodeToByteArray(throwOnInvalidSequence = true) }.isSuccess
    }
}

data class RobotWifiProfile(val modeCode: Int, val ssid: String, val password: String?) {
    val accessPoint: Boolean get() = modeCode == 0
    override fun toString(): String = "RobotWifiProfile(mode=$modeCode, <redacted>)"
}

enum class WifiConfirmedField { SSID, PASSWORD }

interface RobotWifiService {
    suspend fun read(): RobotWifiProfile
    /** Reports each command acknowledgement separately; no atomic update or physical activation is implied. */
    suspend fun configure(credentials: RobotApCredentials, onConfirmed: (WifiConfirmedField) -> Unit)
    /** The connection can disappear before its acknowledgement arrives. Never retry automatically. */
    suspend fun reboot()
}

internal class WifiCommands(private val request: suspend (Int, Int, Int, ByteArray) -> ByteArray) : RobotWifiService {
    private val access = Mutex()
    @Volatile private var closed = false

    private suspend fun command(id: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        currentCoroutineContext().ensureActive()
        if (closed) throw RobotRequestException(RequestProblem.DISCONNECTED)
        return request(Protocol.HOST_WIFI, Protocol.CMDSET_WIFI, id, payload)
    }

    private suspend fun mode(): Int {
        val p = command(Protocol.CMD_WIFI_GET_WORK_MODE)
        if (p.size < 2) invalid()
        return p.u8(1)
    }

    override suspend fun read(): RobotWifiProfile = task {
        val mode = mode()
        val ssid = decode(command(Protocol.CMD_WIFI_AP_GET_SSID))
        val password = if (mode == 0) decode(command(Protocol.CMD_WIFI_AP_GET_PASSWORD)) else null
        RobotWifiProfile(mode, ssid, password)
    }

    override suspend fun configure(credentials: RobotApCredentials, onConfirmed: (WifiConfirmedField) -> Unit) = task {
        check(mode() == 0) { "设备不在热点模式，请重新查询网络状态" }
        command(Protocol.CMD_WIFI_AP_SET_SSID, encode(credentials.ssid))
        onConfirmed(WifiConfirmedField.SSID)
        command(Protocol.CMD_WIFI_AP_SET_PASSWORD, encode(credentials.password))
        onConfirmed(WifiConfirmedField.PASSWORD)
    }

    override suspend fun reboot() = task { command(Protocol.CMD_WIFI_REBOOT); Unit }

    private suspend fun <T> task(action: suspend () -> T): T {
        check(access.tryLock()) { "网络请求正在进行" }
        try {
            if (closed) throw RobotRequestException(RequestProblem.DISCONNECTED)
            return action()
        } finally { access.unlock() }
    }

    fun close() { closed = true }

    private fun encode(value: String): ByteArray {
        val bytes = value.encodeToByteArray(throwOnInvalidSequence = true)
        return byteArrayOf(bytes.size.toByte()) + bytes
    }

    private fun decode(p: ByteArray): String {
        if (p.size < 2 || p.u8(1) > p.size - 2) invalid()
        val value = try { p.decodeToString(2, 2 + p.u8(1), throwOnInvalidSequence = true) }
            catch (_: CharacterCodingException) { invalid() }
        if ('\u0000' in value) invalid()
        return value
    }
}

private fun invalid(): Nothing = throw RobotRequestException(RequestProblem.INVALID_RESPONSE)
