package cn.elonzh.hanppie.robot.lab

import cn.elonzh.hanppie.robot.protocol.hex
import cn.elonzh.hanppie.robot.protocol.hexBytes
import cn.elonzh.hanppie.robot.protocol.u8
import cn.elonzh.hanppie.robot.session.RequestProblem
import cn.elonzh.hanppie.robot.session.RobotRequestException
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.*
import kotlin.test.*

class LabControllerTest {
    private data class Command(val receiver: Int, val attr: Int, val set: Int, val id: Int,
                               val payload: ByteArray, val sender: Int)
    private class Channel : LabChannel {
        override var connected = true
        val commands = mutableListOf<Command>()
        var running = false
        var failId: Int? = null
        var storedAudio = emptyMap<Int, String>()
        var respond: (suspend (Int, ByteArray) -> ByteArray)? = null
        override fun labMode(running: Boolean) { this.running = running }
        override fun setLabRun(runId: String?) {}
        override fun send(receiver: Int, attr: Int, set: Int, id: Int, payload: ByteArray,
                          sender: Int, flags: ByteArray): Int {
            check(connected)
            commands.add(Command(receiver, attr, set, id, payload.copyOf(), sender))
            if (id == failId) throw IOException("simulated uncertain delivery")
            return commands.size
        }
        override suspend fun requestLab(id: Int, payload: ByteArray): ByteArray {
            send(0xa9, 0x40, 0x3f, id, payload, 2, byteArrayOf(0x40, 0))
            respond?.let { return it(id, payload) }
            return if (id == 0xa3 && payload.u8(0) == 0x0d) {
                byteArrayOf(0, payload[34]) + (0 until payload.u8(34)).flatMap { index ->
                    val offset = 35 + index * 5
                    val slot = payload.u8(offset)
                    listOf(slot.toByte(), if (storedAudio[slot] == payload.copyOfRange(offset + 1, offset + 5).hex()) 1.toByte() else 0.toByte())
                }.toByteArray()
            } else byteArrayOf(0)
        }
    }

    @Test fun uploadRegisterStartStopAndRestartTranscript() = runBlocking {
        val channel = Channel()
        var uploaded = byteArrayOf()
        val controller = LabController(channel, { uploaded = it.copyOf() })
        val upload = controller.upload("def start():\n    pass\n", "fixture")
        val hash = upload.digest
        assertEquals(MessageDigest.getInstance("MD5").digest(uploaded).hex(), hash)
        assertEquals(32, upload.runId.length)
        assertTrue(uploaded.decodeToString().contains("def start():\n    pass\n"))
        assertTrue(uploaded.decodeToString().contains("<guid>${upload.runId}</guid>"))
        assertFalse(channel.commands.any { it.id == 0xab })
        val metadata = channel.commands.first { it.id == 0xa3 }.payload
        assertEquals(0x21, metadata[0].toInt())
        val size = channel.commands.single { it.id == 0xa1 }.payload
        val declared = (0..3).fold(0) { value, index -> value or ((size[index + 4].toInt() and 255) shl (index * 8)) }
        assertEquals(uploaded.size, declared)
        assertEquals(upload.runId, controller.start())
        assertTrue(channel.running)
        assertContentEquals(byteArrayOf(1, 0) + hash.hexBytes(), channel.commands.single { it.id == 0xa2 }.payload)
        assertEquals(listOf(0xa2, 0xa3, 0xa3, 0xba), channel.commands.takeLast(4).map { it.id })
        assertEquals(1, channel.commands.takeLast(3).first().payload.u8(0))
        assertContentEquals(metadata.copyOfRange(1, metadata.size), channel.commands.takeLast(2).first().payload.drop(1).toByteArray())
        assertFailsWith<IllegalStateException> { controller.start() }
        assertFailsWith<IllegalStateException> { controller.upload("def start():\n    pass\n", "replacement") }
        controller.stop()
        assertFalse(channel.running)
        assertEquals(0x55, channel.commands.takeLast(2).first().payload[0].toInt())
        assertEquals(0x42, channel.commands.last().sender)
        controller.start()
        assertFalse(channel.commands.any { it.id == 0xab })
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
        assertFailsWith<IllegalArgumentException> { controller.upload("", "empty") }
        assertTrue(channel.commands.isEmpty())
        channel.connected = false
        assertFailsWith<IllegalStateException> { controller.upload("pass", "offline") }
        Unit
    }

    @Test fun customAudioTravelsInsideTheUploadedDsp() = runBlocking {
        val channel = Channel()
        var uploaded = byteArrayOf()
        val controller = LabController(channel, { uploaded = it.copyOf() })
        val clip = LabAudioClip(1, "voice", 2_000, ByteArray(600) { it.toByte() })
        controller.upload("def start():\n    pass\n", "with audio", listOf(clip))
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder().parse(ByteArrayInputStream(uploaded))
        assertEquals("1", document.getElementsByTagName("audio").item(0).attributes.getNamedItem("id").nodeValue)
        val size = channel.commands.single { it.id == 0xa1 }.payload
        val declared = (0..3).fold(0) { value, index -> value or ((size[index + 4].toInt() and 255) shl (index * 8)) }
        assertEquals(uploaded.size, declared)
    }

    @Test fun oversizedAudioIsRejectedBeforeAnyCommand() = runBlocking {
        val channel = Channel()
        var uploaded = byteArrayOf()
        val controller = LabController(channel, { uploaded = it.copyOf() })
        val clip = LabAudioClip(0, "long", 60_000, ByteArray(LabProgram.MAX_DSP_BYTES))
        assertFailsWith<IllegalArgumentException> { controller.upload("def start():\n    pass\n", "too large", listOf(clip)) }
        assertTrue(channel.commands.isEmpty())
        assertTrue(uploaded.isEmpty())
        assertEquals(true, channel.running.not())
    }

    @Test fun repeatedUploadWithSameAudioUsesIncrementalModifyFalse() = runBlocking {
        val channel = Channel()
        var uploaded = byteArrayOf()
        val controller = LabController(channel, { uploaded = it.copyOf() })
        val script = "def start():\n    pass\n"
        val clipA = LabAudioClip(0, "clipA", 1000, ByteArray(2000) { it.toByte() })

        // First upload: cold slot -> modify="true" with full audio body
        val upload1 = controller.upload(script, "fixture", listOf(clipA))
        val doc1 = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(uploaded))
        val node1 = doc1.getElementsByTagName("audio").item(0)
        assertEquals("true", node1.attributes.getNamedItem("modify").nodeValue)
        assertEquals(1, doc1.getElementsByTagName("audio_data").length)
        val initialSize = uploaded.size
        assertTrue(initialSize > 2000)

        // Only a current device match authorizes omission, even in the same host session.
        channel.storedAudio = mapOf(0 to labAudioDigest(clipA.packets))
        val upload2 = controller.upload("def start():\n    # modified code\n    pass\n", "fixture", listOf(clipA))
        val doc2 = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(uploaded))
        val node2 = doc2.getElementsByTagName("audio").item(0)
        assertEquals("false", node2.attributes.getNamedItem("modify").nodeValue)
        assertEquals(0, doc2.getElementsByTagName("audio_data").length)
        assertTrue(uploaded.size < 1500)
        assertNotEquals(upload1.runId, upload2.runId) // fresh random runId per run

        // Third upload: modified audio in slot 0 -> modify="true" with new body
        val clipB = LabAudioClip(0, "clipB", 2000, ByteArray(3000) { it.toByte() })
        val upload3 = controller.upload(script, "fixture", listOf(clipB))
        val doc3 = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(uploaded))
        val node3 = doc3.getElementsByTagName("audio").item(0)
        assertEquals("true", node3.attributes.getNamedItem("modify").nodeValue)
        assertEquals(1, doc3.getElementsByTagName("audio_data").length)
        assertTrue(uploaded.size > 3000)
    }

    @Test fun resourceReuseSurvivesNewHostControllersButNeverIgnoresDeviceChanges() = runBlocking {
        val channel = Channel()
        var uploaded = byteArrayOf()
        val controller = LabController(channel, { uploaded = it.copyOf() })
        val script = "def start():\n    pass\n"
        val clip = LabAudioClip(0, "clip", 1000, ByteArray(2000) { it.toByte() })

        controller.upload(script, "fixture", listOf(clip))
        val doc1 = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(uploaded))
        assertEquals("true", doc1.getElementsByTagName("audio").item(0).attributes.getNamedItem("modify").nodeValue)

        channel.storedAudio = mapOf(0 to labAudioDigest(clip.packets))
        controller.upload(script, "fixture", listOf(clip))
        val doc2 = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(uploaded))
        assertEquals("false", doc2.getElementsByTagName("audio").item(0).attributes.getNamedItem("modify").nodeValue)

        controller.invalidateMode()
        val replacement = LabController(channel, { uploaded = it.copyOf() })
        replacement.upload(script, "fixture", listOf(clip))
        val doc3 = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(uploaded))
        assertEquals("false", doc3.getElementsByTagName("audio").item(0).attributes.getNamedItem("modify").nodeValue)
        channel.storedAudio = emptyMap() // Another client removed/replaced the global slot.
        replacement.upload(script, "fixture", listOf(clip))
        assertTrue(uploaded.decodeToString().contains("modify=\"true\""))
    }

    @Test fun failedOrMalformedResourceQueriesAlwaysKeepFullBodies() = runBlocking {
        val channel = Channel()
        var uploaded = byteArrayOf()
        val controller = LabController(channel, { uploaded = it.copyOf() })
        val clip = LabAudioClip(0, "clip", 1000, byteArrayOf(1, 2, 3))
        controller.upload("pass", "fixture", listOf(clip))
        channel.storedAudio = mapOf(0 to labAudioDigest(clip.packets))
        for (problem in listOf(RequestProblem.TIMED_OUT, RequestProblem.REJECTED, RequestProblem.INVALID_RESPONSE)) {
            channel.respond = { _, _ -> throw RobotRequestException(problem) }
            controller.upload("pass", "fixture", listOf(clip))
            assertTrue(uploaded.decodeToString().contains("modify=\"true\""))
        }
        channel.respond = { _, _ -> byteArrayOf(0, 2, 0, 1, 0, 1) } // Duplicate IDs cannot omit a body.
        controller.upload("pass", "fixture", listOf(clip))
        assertTrue(uploaded.decodeToString().contains("<audio_data>"))
    }

    @Test fun unconfirmedRegistrationOrProgramNeverSendsExecuteAndRequiresExplicitStop() = runBlocking {
        val channel = Channel()
        val controller = LabController(channel, {})
        controller.upload("pass", "fixture")
        val cases: List<suspend (Int, ByteArray) -> ByteArray> = listOf(
            { _, _ -> byteArrayOf(7) },
            { id, _ -> if (id == 0xa2) byteArrayOf(0) else byteArrayOf(1) },
            { id, _ -> if (id == 0xa2) byteArrayOf(0) else byteArrayOf(0, 1) },
            { id, _ -> if (id == 0xa2) byteArrayOf(0) else throw RobotRequestException(RequestProblem.TIMED_OUT) },
        )
        for ((index, response) in cases.withIndex()) {
            channel.respond = response
            val failure = assertFailsWith<Exception> { controller.start() }
            if (index == 1) assertTrue(failure is IllegalStateException && failure.message.orEmpty().contains("程序摘要不匹配"))
            else assertEquals(listOf(RequestProblem.REJECTED, RequestProblem.NOT_APPLIED,
                RequestProblem.INVALID_RESPONSE, RequestProblem.TIMED_OUT)[index], (failure as RobotRequestException).problem)
            assertFalse(channel.commands.any { it.id == 0xab || it.id == 0xa3 && it.payload.u8(0) == 0x52 })
            assertFailsWith<IllegalStateException> { controller.start() }
            controller.stop()
        }
        channel.respond = null
        controller.start()
        assertFalse(channel.commands.any { it.id == 0xab })
        controller.stop()
    }

    @Test fun resourcesAreVerifiedAgainBeforeExecutionAndReplacementCanBeUploaded() = runBlocking {
        val channel = Channel()
        val clip = LabAudioClip(3, "clip", 1000, byteArrayOf(1, 2, 3))
        channel.storedAudio = mapOf(3 to labAudioDigest(clip.packets))
        var uploaded = byteArrayOf()
        val controller = LabController(channel, { uploaded = it.copyOf() })
        controller.upload("pass", "fixture", listOf(clip))
        assertTrue(uploaded.decodeToString().contains("modify=\"false\""))
        channel.storedAudio = emptyMap()
        assertFailsWith<IllegalStateException> { controller.start() }
        assertFalse(channel.commands.any { it.id == 0xab })
        controller.stop()
        controller.upload("pass", "fixture", listOf(clip))
        assertTrue(uploaded.decodeToString().contains("modify=\"true\""))
        channel.storedAudio = mapOf(3 to labAudioDigest(clip.packets))
        controller.start()
        assertFalse(channel.commands.any { it.id == 0xab })
        assertEquals(4, channel.commands.count { it.id == 0xa3 && it.payload.u8(0) == 0x0d })
        controller.stop()
    }

    @Test fun cancelledQueriesCannotUploadOrRestoreAnOlderProgram(): Unit = runBlocking {
        val channel = Channel()
        var uploads = 0
        val controller = LabController(channel, { uploads++ })
        controller.upload("pass", "old")
        val requested = CompletableDeferred<Unit>()
        channel.respond = { _, _ -> requested.complete(Unit); awaitCancellation() }
        val upload = launch { controller.upload("pass", "replacement", listOf(LabAudioClip(0, "clip", 1000, byteArrayOf(1)))) }
        requested.await()
        val sent = channel.commands.size
        upload.cancelAndJoin()
        assertEquals(sent, channel.commands.size)
        assertEquals(1, uploads)
        assertFailsWith<IllegalStateException> { controller.start() }
    }

    @Test fun disconnectedOrInvalidatedQueriesCannotContinueAndInputsAreSnapshotted() = runBlocking {
        val channel = Channel()
        var uploaded = byteArrayOf()
        val controller = LabController(channel, { uploaded = it.copyOf() })
        controller.upload("pass", "old")
        val packets = byteArrayOf(1, 2, 3)
        val originalDigest = labAudioDigest(packets)
        val clip = LabAudioClip(0, "clip", 1000, packets)
        channel.respond = { _, _ -> packets[0] = 9; byteArrayOf(0, 1, 0, 0) }
        controller.upload("pass", "snapshot", listOf(clip))
        assertTrue(uploaded.decodeToString().contains("md5=\"$originalDigest\""))
        for (invalidate in listOf(false, true)) {
            uploaded = byteArrayOf()
            channel.respond = { _, _ ->
                if (invalidate) controller.invalidateMode() else channel.connected = false
                byteArrayOf(0, 1, 0, 1)
            }
            assertFailsWith<IllegalStateException> { controller.upload("pass", "retired", listOf(clip)) }
            assertTrue(uploaded.isEmpty())
            channel.connected = true
        }
    }

    @Test fun cancelledStartCannotExecuteOrAutomaticallyRetry() = runBlocking {
        val channel = Channel()
        val controller = LabController(channel, {})
        controller.upload("pass", "fixture")
        val requested = CompletableDeferred<Unit>()
        channel.respond = { _, _ -> requested.complete(Unit); awaitCancellation() }
        val start = launch { controller.start() }
        requested.await(); start.cancelAndJoin()
        assertFalse(channel.commands.any { it.id == 0xab })
        assertFailsWith<IllegalStateException> { controller.start() }
        channel.respond = null
        controller.stop()
    }
}
