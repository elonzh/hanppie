package cn.elonzh.hanppie.robot.network

import cn.elonzh.hanppie.robot.protocol.Protocol
import cn.elonzh.hanppie.robot.protocol.DussFrame
import cn.elonzh.hanppie.robot.protocol.put16
import cn.elonzh.hanppie.robot.protocol.u16
import cn.elonzh.hanppie.robot.protocol.u8
import cn.elonzh.hanppie.robot.session.RequestProblem
import cn.elonzh.hanppie.robot.session.RobotRequestException
import cn.elonzh.hanppie.robot.session.RobotLock
import cn.elonzh.hanppie.robot.session.robotNanoTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val signals: StateFlow<WifiSignals>
    suspend fun read(): RobotWifiProfile
    suspend fun readRadio(): WifiRadioSettings
    /** These confirm a write acknowledgement only. The client must query the resulting configuration. */
    suspend fun setChannelMode(mode: WifiChannelMode, onDispatch: () -> Unit)
    suspend fun setChannel(channel: Int, onDispatch: () -> Unit)
    suspend fun setSignalReports(enabled: Boolean)
    /** Reports each command acknowledgement separately; no atomic update or physical activation is implied. */
    suspend fun configure(credentials: RobotApCredentials, onConfirmed: (WifiConfirmedField) -> Unit)
    /** The connection can disappear before its acknowledgement arrives. Never retry automatically. */
    suspend fun reboot()
}

internal class WifiCommands(private val nowMillis: () -> Long = { robotNanoTime() / 1_000_000 },
    private val request: suspend (Int, Int, Int, ByteArray) -> ByteArray) : RobotWifiService {
    private val access = Mutex()
    private val signalLock = RobotLock()
    private val mutableSignals = MutableStateFlow(WifiSignals())
    override val signals = mutableSignals.asStateFlow()
    private var channelSignalAt: Long? = null
    private var interferenceAt: Long? = null
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

    override suspend fun readRadio(): WifiRadioSettings = task { readRadioUnlocked() }

    private suspend fun readRadioUnlocked(): WifiRadioSettings {
        val mode = mode()
        val (channel, available) = WifiChannelProtocol.channels(command(0x28))
        val selection = command(0x17)
        if (selection.size < 2) invalid()
        return WifiRadioSettings(mode, selection.u8(1), channel, available)
    }

    override suspend fun setChannelMode(mode: WifiChannelMode, onDispatch: () -> Unit) = task {
        check(mode() == 0) { "设备不在热点模式，请重新查询网络状态" }
        onDispatch()
        command(0x16, byteArrayOf(mode.code.toByte(), 0, 0, 0)); Unit
    }

    override suspend fun setChannel(channel: Int, onDispatch: () -> Unit) = task {
        val actual = readRadioUnlocked()
        check(actual.modeCode == 0 && actual.selectionCode == WifiChannelMode.MANUAL.code) { "请先查询并启用手动信道" }
        check(channel in actual.selectableChannels) { "设备未报告可设置的此信道" }
        onDispatch()
        command(0x2b, ByteArray(3).apply { this[0] = WifiChannelProtocol.band(channel)!!.toByte(); put16(1, channel) }); Unit
    }

    override suspend fun setSignalReports(enabled: Boolean) { command(0x29, byteArrayOf(if (enabled) 1 else 0)) }

    fun receive(frame: DussFrame) {
        if (!frame.valid || frame.sender != Protocol.HOST_WIFI || frame.receiver != Protocol.HOST_MOBILE ||
            frame.attr != Protocol.ATTR_NO_ACK || frame.set != Protocol.CMDSET_WIFI) return
        signalLock.withLock {
            if (closed) return@withLock
            when (frame.id) {
                0x2a -> WifiChannelProtocol.signals(frame.payload)?.let {
                    channelSignalAt = nowMillis(); mutableSignals.value = signals.value.copy(channels = it)
                }
                0x3f -> if (frame.payload.size >= 2) {
                    interferenceAt = nowMillis(); mutableSignals.value = signals.value.copy(interferenceCode = frame.payload.u16(0))
                }
            }
        }
    }

    fun expire() = signalLock.withLock {
        val now = nowMillis()
        mutableSignals.value = signals.value.copy(
            channels = signals.value.channels.takeUnless { channelSignalAt?.let { now - it >= 1_500 } == true },
            interferenceCode = signals.value.interferenceCode.takeUnless { interferenceAt?.let { now - it >= 1_500 } == true })
    }

    private suspend fun <T> task(action: suspend () -> T): T {
        check(access.tryLock()) { "网络请求正在进行" }
        try {
            if (closed) throw RobotRequestException(RequestProblem.DISCONNECTED)
            return action()
        } finally { access.unlock() }
    }

    fun close() = signalLock.withLock { closed = true; mutableSignals.value = WifiSignals() }

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
