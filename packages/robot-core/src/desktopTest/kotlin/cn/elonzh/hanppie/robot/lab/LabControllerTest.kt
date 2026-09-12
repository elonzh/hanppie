package cn.elonzh.hanppie.robot.lab

import cn.elonzh.hanppie.robot.protocol.hex
import cn.elonzh.hanppie.robot.protocol.hexBytes
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class LabControllerTest {
    private data class Command(val receiver: Int, val attr: Int, val set: Int, val id: Int,
                               val payload: ByteArray, val sender: Int)
    private class Channel : LabChannel {
        override var connected = true
        val commands = mutableListOf<Command>()
        var running = false
        var failId: Int? = null
        override fun labMode(running: Boolean) { this.running = running }
        override fun send(receiver: Int, attr: Int, set: Int, id: Int, payload: ByteArray,
                          sender: Int, flags: ByteArray): Int {
            check(connected)
            commands.add(Command(receiver, attr, set, id, payload.copyOf(), sender))
            if (id == failId) throw IOException("simulated uncertain delivery")
            return commands.size
        }
    }

    @Test fun uploadRegisterStartStopAndRestartTranscript() = runBlocking {
        val channel = Channel()
        var uploaded = byteArrayOf()
        val controller = LabController(channel, { uploaded = it.copyOf() })
        val upload = controller.upload("def start():\n    pass\n", "fixture")
        val hash = upload.digest
        assertEquals(MessageDigest.getInstance("MD5").digest(uploaded).hex(), hash)
        assertTrue(uploaded.decodeToString().contains("__HANPPIE_RUN__|${upload.runId}|"))
        assertFalse(channel.commands.any { it.id == 0xab })
        val metadata = channel.commands.first { it.id == 0xa3 }.payload
        assertEquals(0x21, metadata[0].toInt())
        val size = channel.commands.single { it.id == 0xa1 }.payload
        val declared = (0..3).fold(0) { value, index -> value or ((size[index + 4].toInt() and 255) shl (index * 8)) }
        assertEquals(uploaded.size, declared)
        assertEquals(upload.runId, controller.start())
        assertTrue(channel.running)
        assertContentEquals(byteArrayOf(1, 0) + hash.hexBytes(), channel.commands.single { it.id == 0xa2 }.payload)
        assertEquals(listOf(0xa2, 0xa3, 0xba, 0xab), channel.commands.takeLast(4).map { it.id })
        assertContentEquals(metadata.copyOfRange(1, metadata.size), channel.commands.takeLast(3).first().payload.drop(1).toByteArray())
        assertFailsWith<IllegalStateException> { controller.start() }
        assertFailsWith<IllegalStateException> { controller.upload("def start():\n    pass\n", "replacement") }
        controller.stop()
        assertFalse(channel.running)
        assertEquals(0x55, channel.commands.takeLast(2).first().payload[0].toInt())
        assertEquals(0x42, channel.commands.last().sender)
        controller.start()
        assertEquals(2, channel.commands.count { it.id == 0xab })
        controller.stop()
    }

    @Test fun matchingCompletionClearsRunAndStaleCompletionDoesNot() = runBlocking {
        val channel = Channel()
        val controller = LabController(channel, {})
        val upload = controller.upload("def start():\n    pass\n", "fixture")
        controller.start()
        assertFalse(controller.complete("0000000000000000"))
        assertTrue(channel.running)
        assertTrue(controller.complete(upload.runId))
        assertFalse(channel.running)
        controller.upload("def start():\n    pass\n", "next")
        Unit
    }

    @Test fun failedTransferCannotStart() = runBlocking {
        val channel = Channel()
        val controller = LabController(channel, { throw IOException("FTP unavailable") })
        assertFailsWith<IOException> { controller.upload("def start():\n    pass\n", "fixture") }
        assertFailsWith<IllegalStateException> { controller.start() }
        assertFalse(channel.commands.any { it.id == 0xa2 || it.id == 0xab })
    }

    @Test fun partialStartRequiresStopBeforeRetry() = runBlocking {
        val channel = Channel()
        val controller = LabController(channel, {})
        controller.upload("def start():\n    pass\n", "fixture")
        channel.failId = 0xa2
        assertFailsWith<IOException> { controller.start() }
        channel.failId = null
        assertFailsWith<IllegalStateException> { controller.start() }
        assertFailsWith<IllegalStateException> { controller.upload("def start():\n    pass\n", "replacement") }
        controller.stop()
        controller.start()
        controller.stop()
    }

    @Test fun invalidInputDoesNotChangeRobotMode() = runBlocking {
        val channel = Channel()
        val controller = LabController(channel, {})
        assertFailsWith<IllegalArgumentException> { controller.upload(" ", "invalid") }
        assertTrue(channel.commands.isEmpty())
        channel.connected = false
        assertFailsWith<IllegalStateException> { controller.upload("pass", "offline") }
        Unit
    }
}
