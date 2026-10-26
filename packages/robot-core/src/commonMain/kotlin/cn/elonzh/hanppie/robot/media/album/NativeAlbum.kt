package cn.elonzh.hanppie.robot.media.album

import cn.elonzh.hanppie.robot.protocol.DussFrame
import cn.elonzh.hanppie.robot.protocol.Protocol
import cn.elonzh.hanppie.robot.protocol.put16
import cn.elonzh.hanppie.robot.protocol.u16
import cn.elonzh.hanppie.robot.protocol.u8
import cn.elonzh.hanppie.robot.media.CameraDate
import cn.elonzh.hanppie.robot.session.RequestProblem
import cn.elonzh.hanppie.robot.session.RobotLock
import cn.elonzh.hanppie.robot.session.RobotRequestException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.Sink
import kotlinx.io.readByteArray

/** Command 00:26 requests and 00:27 transfer packets, independent of the FTP chroot. */
internal class NativeAlbum(
    private val storageAccess: Mutex,
    private val canRead: () -> Boolean,
    private val request: suspend (Int, Int, Int, ByteArray) -> ByteArray,
) : RobotAlbumService {
    private class Transfer(val id: Int, val command: Int, val packets: Channel<ByteArray>) {
        var failure: RobotRequestException? = null
    }
    private val lock = RobotLock()
    private val mutableBusy = MutableStateFlow(false)
    override val busy = mutableBusy.asStateFlow()
    private var active: Transfer? = null
    private var nextId = 1
    private var closed = false

    private fun id(): Int = lock.withLock { val value = nextId; nextId = (nextId + 1) and 0xffff; value }

    fun receive(frame: DussFrame) {
        if (!frame.valid || frame.sender != Protocol.HOST_CAMERA || frame.receiver != Protocol.HOST_MOBILE ||
            frame.attr != Protocol.ATTR_NO_ACK || frame.set != 0 || frame.id != 0x27 || frame.payload.size < 10) return
        lock.withLock {
            val transfer = active ?: return@withLock
            if (frame.payload.u16(4) != transfer.id || frame.payload.u8(1) != transfer.command) return@withLock
            if (!transfer.packets.trySend(frame.payload).isSuccess) failCurrent()
        }
    }

    fun close() = lock.withLock {
        closed = true
        active?.packets?.close(RobotRequestException(RequestProblem.DISCONNECTED))
    }

    fun invalidateStorage() = lock.withLock { failCurrent() }
    private fun failCurrent() {
        active?.let {
            it.failure = RobotRequestException(RequestProblem.INVALID_RESPONSE)
            it.packets.close(it.failure)
        }
    }

    private fun checkActive(current: Transfer) = lock.withLock {
        if (closed) throw RobotRequestException(RequestProblem.DISCONNECTED)
        current.failure?.let { throw it }
    }

    override suspend fun list(): List<RobotMediaEntry> = withContext(Dispatchers.IO) {
        val buffer = Buffer()
        var count = 0L
        transfer(0x01, { session -> header(0, session, 17).apply {
            put32(10, 1); put16(14, 0xffff)
        } }, buffer, limit = 32L * 1024 * 1024, expectedSize = null, onFirst = { p ->
            count = p.u32(10)
            p.u32(14).also { if (it < 8) invalid() } - 8
        }, firstSkip = 18, onProgress = { _, _ -> })
        val data = buffer.readByteArray()
        if (count > data.size / 18) invalid()
        var cursor = 0
        val result = ArrayList<RobotMediaEntry>(count.toInt())
        val indices = mutableSetOf<Long>()
        repeat(count.toInt()) {
            if (cursor > data.size - 18) invalid()
            val index = data.u32(cursor + 8)
            if (index == 0L || !indices.add(index)) invalid()
            val date = data.u32(cursor)
            val createdAt = runCatching { CameraDate(1980 + (date ushr 25).toInt(), ((date ushr 21) and 15).toInt(),
                ((date ushr 16) and 31).toInt(), ((date ushr 11) and 31).toInt(), ((date ushr 5) and 63).toInt(), ((date and 31) * 2).toInt()) }.getOrNull()
            result += RobotMediaEntry(index, data.u32(cursor + 4), data.u8(cursor + 16), createdAt)
            cursor += 18 + data.u8(cursor + 17)
            if (cursor > data.size) invalid()
        }
        if (cursor != data.size) invalid()
        result
    }

    override suspend fun download(entry: RobotMediaEntry, part: RobotMediaPart, destination: Sink,
        onProgress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        transfer(0x21, { session -> header(0x20, session, 26).apply {
            put32(10, entry.index); put16(14, 1); this[16] = part.wireValue.toByte(); put32(22, entry.size)
        } }, destination, limit = if (part == RobotMediaPart.THUMBNAIL) 8L * 1024 * 1024 else 0xffffffffL,
            expectedSize = entry.size.takeIf { part == RobotMediaPart.ORIGINAL },
            onFirst = { p -> p.u32(14).also { if (it < 13) invalid() } - 13 }, firstSkip = 23, onProgress)
    }

    private suspend fun transfer(command: Int, payload: (Int) -> ByteArray, destination: Sink,
        limit: Long, expectedSize: Long?, onFirst: (ByteArray) -> Long, firstSkip: Int,
        onProgress: (Long, Long) -> Unit) {
        check(storageAccess.tryLock()) { "存储卡传输或格式化正在进行" }
        val current = Transfer(id(), command, Channel(128))
        try {
            lock.withLock {
                if (closed) throw RobotRequestException(RequestProblem.DISCONNECTED)
                check(canRead()) { "没有可读取的存储卡状态" }
                active = current
                mutableBusy.value = true
            }
            send(payload(current.id))
            val ahead = mutableMapOf<Long, ByteArray>()
            var expected = 0L
            var total = -1L
            var received = 0L
            while (true) {
                val p = withTimeoutOrNull(3_000) {
                    while (expected !in ahead) {
                        val candidate = current.packets.receive()
                        if (candidate.u8(0) != 0x4a || (candidate.u16(2) and 0xfff) != candidate.size || candidate.u8(3) ushr 4 !in 0..1)
                            invalid()
                        val sequence = candidate.u32(6)
                        if (sequence < expected) continue
                        if (sequence - expected >= 128) invalid()
                        val previous = ahead.put(sequence, candidate)
                        if (previous != null && !previous.contentEquals(candidate)) invalid()
                    }
                    ahead.remove(expected)!!
                } ?: throw RobotRequestException(RequestProblem.TIMED_OUT)
                checkActive(current)
                val skip = if (expected == 0L) {
                    if (p.size < firstSkip) invalid()
                    total = onFirst(p)
                    if (total > limit || expectedSize != null && total != expectedSize) invalid()
                    firstSkip
                } else 10
                received += p.size - skip
                if (received > total) invalid()
                destination.write(p, skip, p.size)
                onProgress(received, total)
                expected++
                if (p.u8(3) ushr 4 == 1) {
                    if (received != total || ahead.isNotEmpty()) invalid()
                    destination.flush()
                    checkActive(current)
                    // The native client gives this acknowledgement a new transfer ID, even after a list.
                    send(header(0x22, id(), 16).apply { put32(10, expected) })
                    return
                }
            }
        } finally {
            lock.withLock { if (active === current) { active = null; mutableBusy.value = false } }
            current.packets.cancel()
            storageAccess.unlock()
        }
    }

    private suspend fun send(payload: ByteArray) { request(Protocol.HOST_CAMERA, 0, 0x26, payload) }
    private fun header(command: Int, session: Int, size: Int) = ByteArray(size).apply {
        this[0] = 0x4a; this[1] = command.toByte(); put16(2, size or 0x1000); put16(4, session)
    }
}

private fun invalid(): Nothing = throw RobotRequestException(RequestProblem.INVALID_RESPONSE)
private fun ByteArray.u32(offset: Int): Long = (0..3).fold(0L) { bits, i -> bits or (u8(offset + i).toLong() shl (i * 8)) }
private fun ByteArray.put32(offset: Int, value: Long) { repeat(4) { this[offset + it] = (value ushr (it * 8)).toByte() } }
