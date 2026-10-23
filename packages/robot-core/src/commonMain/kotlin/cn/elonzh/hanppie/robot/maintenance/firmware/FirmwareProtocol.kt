package cn.elonzh.hanppie.robot.maintenance.firmware

import cn.elonzh.hanppie.robot.files.RobotFilePath
import cn.elonzh.hanppie.robot.protocol.DussFrame
import cn.elonzh.hanppie.robot.protocol.u8
import cn.elonzh.hanppie.robot.session.RequestProblem
import cn.elonzh.hanppie.robot.session.RobotRequestException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.concurrent.Volatile

internal data class FirmwareFtpTarget(val ip: String, val port: Int, val path: String)
enum class FirmwareReportKind { CONFIRM, PROGRESS, END, REBOOT, UNKNOWN }
data class FirmwareUpgradeReport(val typeRaw: Int, val valueRaw: Int, val detailRaw: Int) {
    val kind: FirmwareReportKind get() = when (typeRaw) {
        2 -> FirmwareReportKind.CONFIRM
        3 -> FirmwareReportKind.PROGRESS
        4 -> FirmwareReportKind.END
        6 -> FirmwareReportKind.REBOOT
        else -> FirmwareReportKind.UNKNOWN
    }
    val progress: Int? get() = valueRaw.takeIf { kind == FirmwareReportKind.PROGRESS && it in 0..100 }
    // Native's ongoing detail packs a 5-bit module and 3-bit step; it is not a global error code.
    val module: Int? get() = detailRaw.takeIf { kind == FirmwareReportKind.PROGRESS }?.and(31)
    val step: Int? get() = detailRaw.takeIf { kind == FirmwareReportKind.PROGRESS }?.ushr(5)
}

/** Native macOS 1.1.5 robot upgrade path. GamePad's V1 stream is a separate protocol. */
internal class FirmwareProtocol(private val request: suspend (Int, Int, Int, ByteArray) -> ByteArray) {
    @Volatile private var closed = false

    suspend fun configuration(): ByteArray {
        val output = Buffer()
        while (true) {
            val payload = ByteArray(9).apply { this[0] = 1; putU32(1, output.size); putU32(5, 0xffffffffL) }
            val reply = call(0x4f, payload)
            if (reply.size < 9) invalid()
            val size = reply.u32(1)
            val continuation = reply.u32(5)
            if (size > MAX_CONFIGURATION_BYTES - output.size || size != reply.size.toLong() - 9 || size == 0L && continuation != 0L) invalid()
            output.write(reply, 9, reply.size)
            // The native handler only tests this field for zero. Its unit is not established.
            if (continuation == 0L) return output.readByteArray()
        }
    }

    suspend fun enterLoader() { call(7, ByteArray(9)) }

    suspend fun requestTransfer(size: Long): FirmwareFtpTarget {
        require(size in 1..0xffffffffL)
        val p = ByteArray(13).apply { putU32(1, size); this[11] = 2; this[12] = 4 }
        val reply = call(8, p)
        if (reply.size < 71) invalid()
        // The native callback converts uint32LE to dotted IPv4 in most-significant-byte order.
        val ip = (4 downTo 1).joinToString(".") { reply.u8(it).toString() }
        val port = reply.u8(5) or (reply.u8(6) shl 8)
        val end = (7 until 71).firstOrNull { reply[it] == 0.toByte() } ?: invalid()
        val path = runCatching { reply.decodeToString(7, end, throwOnInvalidSequence = true) }.getOrElse { invalid() }
        if (ip == "0.0.0.0" || ip == "255.255.255.255" || port == 0 || path == "/" ||
            runCatching { RobotFilePath.normalize(path) }.isFailure) invalid()
        return FirmwareFtpTarget(ip, port, path)
    }

    /** Accepts native 0xf7 as transfer acceptance only; installation still needs device reports. */
    suspend fun finishTransfer(md5: ByteArray): Int {
        require(md5.size == 16)
        return try { call(0x0a, byteArrayOf(0) + md5); 0 } catch (error: RobotRequestException) {
            if (error.problem != RequestProblem.REJECTED || error.responseCode != 0xf7) throw error
            ensureOpen(); 0xf7
        }
    }

    /** Stops upgrade reporting, never the device's flash operation. */
    fun stopReports(send: (Int, Int, Int, ByteArray) -> Unit) {
        if (!closed) send(UPGRADE_NODE, 0, 0x41, byteArrayOf())
    }
    fun close() { closed = true }
    private suspend fun call(id: Int, payload: ByteArray): ByteArray {
        ensureOpen()
        val response = request(UPGRADE_NODE, 0, id, payload)
        ensureOpen()
        if (response.isEmpty()) invalid()
        if (response.u8(0) != 0) throw RobotRequestException(RequestProblem.REJECTED, response.u8(0))
        return response
    }
    private suspend fun ensureOpen() {
        currentCoroutineContext().ensureActive()
        if (closed) throw RobotRequestException(RequestProblem.DISCONNECTED)
    }
    companion object { private const val MAX_CONFIGURATION_BYTES = 1_048_576L }
}

internal fun firmwareUpgradeReport(frame: DussFrame): FirmwareUpgradeReport? {
    if (!frame.upgradePush(0x42) || frame.payload.size < 3) return null
    return FirmwareUpgradeReport(frame.payload.u8(0), frame.payload.u8(1), frame.payload.u8(2))
}
internal fun firmwareRebootReport(frame: DussFrame): Int? =
    frame.payload.takeIf { frame.upgradePush(0x47) && it.isNotEmpty() }?.u8(0)

private const val UPGRADE_NODE = 0x28
private fun DussFrame.upgradePush(command: Int): Boolean =
    valid && sender == UPGRADE_NODE && receiver == 2 && attr == 0 && set == 0 && id == command
private fun ByteArray.u32(offset: Int): Long = (0..3).fold(0L) { result, index -> result or (u8(offset + index).toLong() shl (index * 8)) }
private fun ByteArray.putU32(offset: Int, value: Long) { repeat(4) { this[offset + it] = (value ushr (it * 8)).toByte() } }
private fun invalid(): Nothing = throw RobotRequestException(RequestProblem.INVALID_RESPONSE)
