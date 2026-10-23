package cn.elonzh.hanppie.robot.remote

import cn.elonzh.hanppie.robot.protocol.hex
import cn.elonzh.hanppie.robot.protocol.hexBytes
import cn.elonzh.hanppie.robot.session.RequestProblem
import cn.elonzh.hanppie.robot.session.RobotRequestException
import kotlinx.coroutines.*
import kotlin.test.*

class MotionCommandsTest {
    private data class Call(val node: Int, val set: Int, val id: Int, val payload: String)
    private class Peer {
        val hashes = listOf("810636fe", "d9980ced", "6fe6a05e", "1b175310", "0e1a53a0", "e96d5133", "dc7051c3")
        val calls = mutableListOf<Call>()
        var level = 4
        val values = hashes.associateWith { "0000803f" }.toMutableMap()
        var reply: suspend (Call, ByteArray) -> ByteArray = { _, bytes -> bytes }
        val commands = MotionCommands { node, set, id, payload, onDispatch ->
            val call = Call(node, set, id, payload.hex()); calls += call
            val bytes = when (node to set) {
                9 to 0x3f -> when (id) {
                    0x4f -> byteArrayOf(0, level.toByte())
                    0x3f -> { onDispatch(); level = payload[0].toInt(); byteArrayOf(0) }
                    else -> error("Unexpected command $call")
                }
                3 to 3 -> when (id) {
                    0xf7 -> { assertTrue(call.payload in hashes); "0008000400010000000000000040400000c03f".hexBytes() }
                    0xf8 -> "00${call.payload}${values.getValue(call.payload)}".hexBytes()
                    0xf9 -> {
                        onDispatch(); values[call.payload.take(8)] = call.payload.drop(8); byteArrayOf(0)
                    }
                    else -> error("Unexpected command $call")
                }
                else -> error("Unexpected route $call")
            }
            reply(call, bytes)
        }
    }

    @Test fun readsAllSevenNativeHashesRangesAndValuesWithoutWriting(): Unit = runBlocking {
        val peer = Peer()
        val value = peer.commands.read()
        assertEquals(4, value.speedLevelRaw)
        assertEquals(ChassisParameter.entries.toSet(), value.parameters.keys)
        value.parameters.values.forEach { assertEquals(ChassisParameterReading(ChassisParameterRange(0f, 3f, 1.5f, 8, 1), 1f), it) }
        assertEquals(listOf(Call(9, 0x3f, 0x4f, "")) + peer.hashes.flatMap {
            listOf(Call(3, 3, 0xf7, it), Call(3, 3, 0xf8, it))
        } + Call(9, 0x3f, 0x4f, ""), peer.calls)
        peer.level = 251
        assertEquals(251, peer.commands.read().speedLevelRaw)
    }

    @Test fun nativePresetWritesOneByteAndConfirmsEveryCurrentParameter(): Unit = runBlocking {
        val peer = Peer(); var dispatched = 0
        val value = peer.commands.setSpeedLevel(2) { dispatched++ }
        assertEquals(2, value.speedLevelRaw); assertEquals(1, dispatched)
        assertEquals(Call(9, 0x3f, 0x3f, "02"), peer.calls.first())
        assertEquals(17, peer.calls.size)
        assertFailsWith<IllegalArgumentException> { peer.commands.setSpeedLevel(0) {} }
        assertEquals(17, peer.calls.size)
    }

    @Test fun parameterChecksLiveModeAndBoundsThenWritesHashFloatAndConfirms(): Unit = runBlocking {
        val peer = Peer(); var dispatched = 0
        assertEquals(.5f, peer.commands.setParameter(ChassisParameter.FORWARD_SPEED, .5f) { dispatched++ }.value)
        assertEquals(1, dispatched)
        assertEquals(listOf(Call(9, 0x3f, 0x4f, ""), Call(3, 3, 0xf7, "810636fe"),
            Call(3, 3, 0xf9, "810636fe0000003f"), Call(3, 3, 0xf8, "810636fe"), Call(9, 0x3f, 0x4f, "")), peer.calls)
        peer.level = 1
        assertEquals(RequestProblem.NOT_APPLIED, assertFailsWith<RobotRequestException> {
            peer.commands.setParameter(ChassisParameter.FORWARD_SPEED, .5f) { dispatched++ }
        }.problem)
        peer.level = 4
        assertFailsWith<IllegalArgumentException> { peer.commands.setParameter(ChassisParameter.FORWARD_SPEED, 3.1f) { dispatched++ } }
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { peer.commands.setParameter(ChassisParameter.FORWARD_SPEED, bad) { dispatched++ } }
        }
        assertEquals(1, dispatched)
    }

    @Test fun malformedRangesValuesAndEchoesDoNotBecomeConfiguration(): Unit = runBlocking {
        for (kind in 0..7) {
            val peer = Peer()
            peer.reply = { call, bytes -> when {
                kind == 0 && call.id == 0x4f -> byteArrayOf(0)
                kind == 1 && call.id == 0xf7 -> bytes.copyOf(18)
                kind == 2 && call.id == 0xf7 -> bytes.copyOf().apply { this[3] = 8 }
                kind == 3 && call.id == 0xf7 -> bytes.copyOf().apply { "0000807f".hexBytes().copyInto(this, 11) }
                kind == 4 && call.id == 0xf7 -> bytes.copyOf().apply { "00008040".hexBytes().copyInto(this, 7) }
                kind == 5 && call.id == 0xf8 -> bytes.copyOf().apply { this[1] = 0 }
                kind == 6 && call.id == 0xf8 -> bytes + byteArrayOf(0)
                kind == 7 && call.id == 0xf8 -> bytes.copyOf().apply { "0000c07f".hexBytes().copyInto(this, 5) }
                else -> bytes
            } }
            assertEquals(RequestProblem.INVALID_RESPONSE, assertFailsWith<RobotRequestException> { peer.commands.read() }.problem, "case $kind")
            assertTrue(peer.calls.none { it.id == 0xf9 || it.id == 0x3f })
        }
    }

    @Test fun rejectsDeviceErrorsAndUnconfirmedWrites(): Unit = runBlocking {
        val denied = Peer().apply { reply = { _, _ -> byteArrayOf(7) } }
        assertEquals(7, assertFailsWith<RobotRequestException> { denied.commands.read() }.responseCode)
        val stale = Peer().apply { reply = { call, bytes -> if (call.id == 0xf8) "00810636fe0000803f".hexBytes() else bytes } }
        assertEquals(RequestProblem.NOT_APPLIED, assertFailsWith<RobotRequestException> {
            stale.commands.setParameter(ChassisParameter.FORWARD_SPEED, .5f) {}
        }.problem)
        val changed = Peer().apply { reply = { call, bytes -> if (call.id == 0x4f && calls.size > 1) byteArrayOf(0, 2) else bytes } }
        assertEquals(RequestProblem.NOT_APPLIED, assertFailsWith<RobotRequestException> { changed.commands.read() }.problem)
    }

    @Test fun closedCancelledAndConcurrentRequestsNeverContinue(): Unit = runBlocking {
        val peer = Peer().apply { reply = { _, bytes -> commands.close(); bytes } }
        assertEquals(RequestProblem.DISCONNECTED, assertFailsWith<RobotRequestException> { peer.commands.read() }.problem)
        assertEquals(1, peer.calls.size)
        val pending = Peer(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        pending.reply = { _, bytes -> entered.complete(Unit); release.await(); bytes }
        val reading = async { pending.commands.read() }
        entered.await()
        assertFailsWith<IllegalStateException> { pending.commands.setSpeedLevel(1) { fail("concurrent dispatch") } }
        reading.cancelAndJoin(); release.complete(Unit)
        assertEquals(1, pending.calls.size)
        pending.reply = { _, bytes -> bytes }
        assertEquals(4, pending.commands.read().speedLevelRaw)
    }
}
