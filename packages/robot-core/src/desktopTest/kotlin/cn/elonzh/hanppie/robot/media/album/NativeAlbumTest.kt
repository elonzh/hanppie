package cn.elonzh.hanppie.robot.media.album

import cn.elonzh.hanppie.robot.media.CameraCommands
import cn.elonzh.hanppie.robot.media.CameraDate
import cn.elonzh.hanppie.robot.protocol.*
import cn.elonzh.hanppie.robot.session.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.test.*

class NativeAlbumTest {
    @Test fun listCreationTimeUsesTheCameraCalendarAndInvalidDatesDoNotHideFiles(): Unit = runBlocking {
        lateinit var album: NativeAlbum
        val date = ((2024L - 1980) shl 25) or (2L shl 21) or (29L shl 16) or (12L shl 11) or (34L shl 5) or 28L
        val records = entry(1).apply { put32(0, date) } + entry(2, format = 3).apply { put32(0, date and (15L shl 21).inv()) }
        album = NativeAlbum(Mutex(), { true }) { _, _, _, p ->
            if (p.u8(1) == 0) album.receive(chunk(p.u16(4), 1, 0, listBody(2, records)))
            byteArrayOf(0)
        }
        val files = album.list()
        assertEquals(CameraDate(2024, 2, 29, 12, 34, 56), files[0].createdAt)
        assertNull(files[1].createdAt)
        assertTrue(files[1].isVideo)
        assertEquals(2, files.size)
    }
    private fun ByteArray.put32(at: Int, value: Long) { repeat(4) { this[at + it] = (value ushr (it * 8)).toByte() } }
    private fun chunk(id: Int, command: Int, sequence: Long, body: ByteArray, last: Boolean = true) =
        DussFrame(0, 1, 2, 10, 0, 0, 0x27, ByteArray(10 + body.size).apply {
            this[0] = 0x4a; this[1] = command.toByte()
            put16(2, size or if (last) 0x1000 else 0); put16(4, id); put32(6, sequence)
            body.copyInto(this, 10)
        }, true)
    private fun entry(index: Long, size: Long = 4, format: Int = 0, extra: ByteArray = byteArrayOf()) =
        ByteArray(18 + extra.size).apply { put32(4, size); put32(8, index); this[16] = format.toByte(); this[17] = extra.size.toByte(); extra.copyInto(this, 18) }
    private fun listBody(count: Int, entries: ByteArray) = ByteArray(8 + entries.size).apply {
        put32(0, count.toLong()); put32(4, size.toLong()); entries.copyInto(this, 8)
    }
    private fun fileBody(index: Long, bytes: ByteArray) = ByteArray(13 + bytes.size).apply {
        put32(0, index); put32(4, size.toLong()); bytes.copyInto(this, 13)
    }

    @Test fun nativeListVectorAndVariableExtraRecordsSurviveReorderingBeforeCommandAck(): Unit = runBlocking {
        lateinit var album: NativeAlbum
        val sent = mutableListOf<String>()
        val records = entry(3, extra = byteArrayOf(0xff.toByte(), 0, 0x3f)) + entry(8, 500, 3)
        val body = listBody(2, records)
        album = NativeAlbum(Mutex(), { true }) { receiver, set, command, p ->
            assertEquals(1, receiver); assertEquals(0, set); assertEquals(0x26, command)
            sent += p.hex()
            if (p.u8(1) == 0) {
                album.receive(chunk(999, 1, 0, body))
                val second = chunk(p.u16(4), 1, 1, body.copyOfRange(20, body.size))
                album.receive(second); album.receive(second)
                album.receive(chunk(p.u16(4), 1, 0, body.copyOfRange(0, 20), false))
            }
            byteArrayOf(0)
        }
        assertEquals(listOf(RobotMediaEntry(3, 4, 0), RobotMediaEntry(8, 500, 3)), album.list())
        assertEquals(listOf("4a00111001000000000001000000ffff00", "4a221010020000000000020000000000"), sent)
        assertFalse(album.busy.value)
    }

    @Test fun nativeFileVectorsStreamExactBytesWithProgressAndIndependentThumbnailSize(): Unit = runBlocking {
        lateinit var album: NativeAlbum
        val sent = mutableListOf<String>()
        val progress = mutableListOf<Pair<Long, Long>>()
        album = NativeAlbum(Mutex(), { true }) { _, _, _, p ->
            sent += p.hex()
            if (p.u8(1) == 0x20) {
                val data = if (p.u8(16) == 1) byteArrayOf(9, 8, 7) else byteArrayOf(1, 2, 3, 4)
                album.receive(chunk(p.u16(4), 0x21, 0, fileBody(3, data)))
            }
            byteArrayOf(0)
        }
        val original = Buffer(); album.download(RobotMediaEntry(3, 4, 0), RobotMediaPart.ORIGINAL, original) { got, total -> progress += got to total }
        assertContentEquals(byteArrayOf(1, 2, 3, 4), original.readByteArray())
        assertEquals(listOf(4L to 4L), progress)
        val thumb = Buffer(); album.download(RobotMediaEntry(3, 4, 0), RobotMediaPart.THUMBNAIL, thumb)
        assertContentEquals(byteArrayOf(9, 8, 7), thumb.readByteArray())
        assertEquals("4a201a1001000000000003000000010000000000000004000000", sent[0])
        assertEquals("4a201a1003000000000003000000010001000000000004000000", sent[2])
    }

    @Test fun sourceCrcDestinationAttributeAndTransferTypeAreFiltered(): Unit = runBlocking {
        lateinit var album: NativeAlbum
        album = NativeAlbum(Mutex(), { true }) { _, _, _, p ->
            if (p.u8(1) == 0) {
                val good = chunk(p.u16(4), 1, 0, listBody(0, byteArrayOf()))
                for (bad in listOf(good.copy(valid = false), good.copy(sender = 9), good.copy(receiver = 3),
                    good.copy(attr = 0x80), good.copy(id = 0x26), good.copy(set = 2),
                    chunk(p.u16(4), 0x21, 0, byteArrayOf(1)))) album.receive(bad)
                album.receive(good)
            }
            byteArrayOf(0)
        }
        assertEquals(emptyList(), album.list())
    }

    @Test fun malformedRecordsAreNeverReturnedAndOversizedTransfersAreRejected(): Unit = runBlocking {
        val invalidBodies = listOf(listBody(2, entry(1) + entry(1)), listBody(1, entry(1).apply { this[17] = 10 }),
            listBody(0, entry(1)), listBody(1, entry(1)).apply { put32(4, 0xffffffffL) })
        for (body in invalidBodies) {
            lateinit var album: NativeAlbum
            var sends = 0
            album = NativeAlbum(Mutex(), { true }) { _, _, _, p ->
                sends++
                if (p.u8(1) == 0) album.receive(chunk(p.u16(4), 1, 0, body))
                byteArrayOf(0)
            }
            assertEquals(RequestProblem.INVALID_RESPONSE, assertFailsWith<RobotRequestException> { album.list() }.problem)
            // Structural record validation follows complete transport acknowledgement, never a successful list.
            assertTrue(sends in 1..2)
            assertFalse(album.busy.value)
        }
    }

    @Test fun originalSizeMismatchTruncationAndInvalidPacketHeaderAreRejected(): Unit = runBlocking {
        for (damage in 0..2) {
            lateinit var album: NativeAlbum
            var sends = 0
            album = NativeAlbum(Mutex(), { true }) { _, _, _, p ->
                sends++
                if (p.u8(1) == 0x20) {
                    val frame = chunk(p.u16(4), 0x21, 0, fileBody(1, byteArrayOf(1, 2, 3, 4)))
                    when (damage) {
                        0 -> frame.payload.put32(14, 18)
                        1 -> frame.payload.put16(2, 10 or 0x1000)
                        2 -> frame.payload[0] = 0x49
                    }
                    album.receive(frame)
                }
                byteArrayOf(0)
            }
            assertEquals(RequestProblem.INVALID_RESPONSE, assertFailsWith<RobotRequestException> {
                album.download(RobotMediaEntry(1, 4, 0), RobotMediaPart.ORIGINAL, Buffer())
            }.problem)
            assertEquals(1, sends)
        }
    }

    @Test fun aMissingMiddlePacketNeverCompletesAndCommandRejectionDoesNotWriteBufferedData(): Unit = runBlocking {
        lateinit var album: NativeAlbum
        album = NativeAlbum(Mutex(), { true }) { _, _, _, p ->
            album.receive(chunk(p.u16(4), 0x21, 0, fileBody(1, byteArrayOf()).apply { put32(4, 17) }, false))
            album.receive(chunk(p.u16(4), 0x21, 2, byteArrayOf(1, 2, 3, 4)))
            byteArrayOf(0)
        }
        assertEquals(RequestProblem.TIMED_OUT, assertFailsWith<RobotRequestException> {
            album.download(RobotMediaEntry(1, 4, 0), RobotMediaPart.ORIGINAL, Buffer())
        }.problem)
        val destination = Buffer()
        album = NativeAlbum(Mutex(), { true }) { _, _, _, p ->
            album.receive(chunk(p.u16(4), 0x21, 0, fileBody(1, byteArrayOf(1, 2, 3, 4))))
            throw RobotRequestException(RequestProblem.REJECTED)
        }
        assertEquals(RequestProblem.REJECTED, assertFailsWith<RobotRequestException> {
            album.download(RobotMediaEntry(1, 4, 0), RobotMediaPart.ORIGINAL, destination)
        }.problem)
        assertEquals(0L, destination.size)
    }

    @Test fun cancellationAndDisconnectReleaseStorageAndIgnoreLatePackets(): Unit = runBlocking {
        val access = Mutex()
        val requests = Channel<Int>(Channel.UNLIMITED)
        val album = NativeAlbum(access, { true }) { _, _, _, p -> requests.send(p.u16(4)); byteArrayOf(0) }
        val cancelled = launch { album.list() }
        val oldId = withTimeout(2_000) { requests.receive() }
        assertTrue(album.busy.value); assertTrue(access.isLocked)
        cancelled.cancelAndJoin()
        assertFalse(album.busy.value); assertFalse(access.isLocked)
        val pending = async { runCatching { album.list() } }
        withTimeout(2_000) { requests.receive() }
        album.receive(chunk(oldId, 1, 0, listBody(0, byteArrayOf())))
        assertFalse(pending.isCompleted)
        album.close()
        assertEquals(RequestProblem.DISCONNECTED, (pending.await().exceptionOrNull() as RobotRequestException).problem)
        assertFalse(album.busy.value); assertFalse(access.isLocked)
        assertEquals(RequestProblem.DISCONNECTED, assertFailsWith<RobotRequestException> { album.list() }.problem)
    }

    @Test fun downloadBlocksFormattingButNeverBlocksExplicitRecordingStop(): Unit = runBlocking {
        val access = Mutex()
        lateinit var camera: CameraCommands
        val status = DussFrame(0, 1, 2, 1, 0, 2, 0xe4, ByteArray(26).apply { this[2] = 0x10; put16(5, 0xc1); put16(8, 100) }, true)
        val sent = mutableListOf<Int>()
        camera = CameraCommands({ _, _, id, _ -> sent += id; camera.receive(status); byteArrayOf(0) }, storageAccess = access) { 1 }
        camera.receive(status)
        val album = NativeAlbum(access, camera::canReadStorage) { _, _, _, _ -> byteArrayOf(0) }
        val download = launch { album.list() }
        withTimeout(2_000) { album.busy.first { it } }
        assertFailsWith<IllegalStateException> { camera.formatStorage() }
        camera.setRecording(false)
        assertEquals(listOf(2), sent)
        download.cancelAndJoin(); camera.formatStorage()
        assertEquals(listOf(2, 0x72), sent)
    }
}
