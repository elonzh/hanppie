package cn.elonzh.hanppie.robot.network

import cn.elonzh.hanppie.robot.protocol.Protocol
import cn.elonzh.hanppie.robot.protocol.put16
import cn.elonzh.hanppie.robot.protocol.u8
import cn.elonzh.hanppie.robot.protocol.u16
import cn.elonzh.hanppie.robot.session.RequestProblem
import cn.elonzh.hanppie.robot.session.RobotRequestException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlin.concurrent.Volatile

// These enums belong to SDR. In particular, its selection mode is the reverse of Wi-Fi's.
enum class SdrSelection(val code: Int) { MANUAL(0), AUTO(1) }
enum class SdrBandwidth(val code: Int) { MHZ_20(0), MHZ_10(1) }
enum class SdrBand(val code: Int) { DUAL(0), GHZ_2_4(1), GHZ_5_8(2) }

data class SdrSettings(val selectionCode: Int, val bandwidthCode: Int, val point: Int,
    val bandCode: Int, val dualBandSupported: Boolean) {
    /** Fixed client's manual slider bounds, not a country-specific availability list. */
    val manualPoints: IntRange? get() = when {
        selectionCode != 0 -> null
        bandCode == 1 && bandwidthCode == 0 -> 1011..1073
        bandCode == 1 && bandwidthCode == 1 -> 1006..1078
        bandCode == 2 && dualBandSupported && bandwidthCode == 0 -> 2549..2653
        bandCode == 2 && dualBandSupported && bandwidthCode == 1 -> 2544..2658
        else -> null
    }
}

sealed interface SdrChange {
    val selector: Int
    val value: Int
    data class Point(override val value: Int) : SdrChange { override val selector = 1 }
    data class Band(val band: SdrBand) : SdrChange { override val selector = 2; override val value = band.code }
    data class Selection(val selection: SdrSelection) : SdrChange { override val selector = 3; override val value = selection.code }
    data class Bandwidth(val bandwidth: SdrBandwidth) : SdrChange { override val selector = 4; override val value = bandwidth.code }
    fun matches(settings: SdrSettings): Boolean = value == when (this) {
        is Point -> settings.point; is Band -> settings.bandCode
        is Selection -> settings.selectionCode; is Bandwidth -> settings.bandwidthCode
    }
}

/** Native channel encoding; retain an unknown channel as raw data rather than guessing MHz. */
fun sdrFrequencyMHz(point: Int): String? {
    val halfMHz = when (point) {
        in 901..1200 -> 2 * point + 2799
        in 2084..2283 -> 2 * point + 6133
        in 2284..2663 -> 2 * point + 6373
        else -> return null
    }
    return "${halfMHz / 2}.5"
}

interface RobotSdrService {
    /** Null means this session currently reports a different link mode, not that its model lacks SDR. */
    suspend fun read(): SdrSettings?
    /** Validates fresh link/configuration data, then confirms only the write ACK. Never retries. */
    suspend fun set(change: SdrChange, onDispatch: () -> Unit)
}

internal class SdrCommands(private val request: suspend (Int, Int, Int, ByteArray) -> ByteArray) : RobotSdrService {
    private val access = Mutex()
    @Volatile private var closed = false

    private suspend fun command(node: Int, set: Int, id: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        currentCoroutineContext().ensureActive()
        if (closed) throw RobotRequestException(RequestProblem.DISCONNECTED)
        return request(node, set, id, payload)
    }

    override suspend fun read(): SdrSettings? = task { readUnlocked() }
    private suspend fun readUnlocked(): SdrSettings? {
        val mode = command(Protocol.HOST_WIFI, Protocol.CMDSET_WIFI, Protocol.CMD_WIFI_GET_WORK_MODE)
        if (mode.size < 2) invalid()
        if (RobotLinkMode.fromCode(mode.u8(1)) != RobotLinkMode.SDR) return null
        val p = command(0x0e, Protocol.CMDSET_HDVT, 0x21)
        if (p.size < 7) invalid()
        return SdrSettings(p.u8(1), p.u8(2), p.u16(3), p.u8(5), p.u8(6) != 0)
    }

    override suspend fun set(change: SdrChange, onDispatch: () -> Unit) = task {
        val current = checkNotNull(readUnlocked()) { "当前链路不是 SDR，请重新查询" }
        when (change) {
            is SdrChange.Point -> check(current.manualPoints?.contains(change.value) == true) { "频点不在当前配置的手动范围内" }
            is SdrChange.Band -> check(current.selectionCode == 0 && change.band != SdrBand.DUAL &&
                (change.band == SdrBand.GHZ_2_4 || current.dualBandSupported)) { "当前配置不可切换至此频段" }
            is SdrChange.Bandwidth -> check(current.selectionCode == 0) { "请先切换至手动模式" }
            is SdrChange.Selection -> Unit
        }
        onDispatch()
        command(0x0e, Protocol.CMDSET_HDVT, 0x0d, ByteArray(3).apply { this[0] = change.selector.toByte(); put16(1, change.value) })
        Unit
    }

    private suspend fun <T> task(action: suspend () -> T): T {
        check(access.tryLock()) { "SDR 请求正在进行" }
        try { return action() } finally { access.unlock() }
    }
    fun close() { closed = true }
}

private fun invalid(): Nothing = throw RobotRequestException(RequestProblem.INVALID_RESPONSE)
