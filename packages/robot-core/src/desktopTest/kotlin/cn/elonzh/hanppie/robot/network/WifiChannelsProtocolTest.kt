package cn.elonzh.hanppie.robot.network

import cn.elonzh.hanppie.robot.protocol.*
import cn.elonzh.hanppie.robot.session.*
import kotlinx.coroutines.*
import kotlin.test.*

class WifiChannelsProtocolTest {
    @Test fun channelInfoUsesNativeOffsetsAndBoundedVariableGroups(): Unit = runBlocking {
        var response = channelInfo(149, listOf(1, 6, 11), listOf(36, 149, 233))
        val ids = mutableListOf<Int>()
        val service = WifiCommands { node, set, id, p ->
            assertEquals(7, node); assertEquals(7, set); assertTrue(p.isEmpty()); ids += id
            when (id) { 0x39 -> byteArrayOf(0, 0); 0x17 -> byteArrayOf(0, 255.toByte()); 0x28 -> response; else -> error("unexpected") }
        }
        val radio = service.readRadio()
        assertEquals(WifiRadioSettings(0, 255, 149, listOf(1, 6, 11, 36, 149, 233)), radio)
        assertEquals(listOf(1, 6, 11, 36, 149), radio.selectableChannels)
        assertEquals(listOf(0x39, 0x28, 0x17), ids)
        for (bad in listOf(ByteArray(9), response.copyOf(response.size - 1), response.copyOf().apply { write32(6, 0xffffffffL) },
            response.copyOf().apply { write32(14, 0xffffffffL) })) {
            response = bad
            assertEquals(RequestProblem.INVALID_RESPONSE, assertFailsWith<RobotRequestException> { service.readRadio() }.problem)
        }
        response = channelInfo(0, listOf(0, 1, 1, 233))
        assertEquals(listOf(0, 1, 1, 233), service.readRadio().availableChannels)
    }

    @Test fun modeAndChannelWritesUseDifferentNativePayloadsAndFreshDeviceGuards(): Unit = runBlocking {
        var connectionMode = 0; var selection = 1
        val calls = mutableListOf<String>()
        var dispatched = 0
        val service = WifiCommands { _, _, id, p ->
            calls += "$id/${p.hex()}"
            when (id) { 0x39 -> byteArrayOf(0, connectionMode.toByte()); 0x28 -> channelInfo(1, listOf(1, 6), listOf(149));
                0x17 -> byteArrayOf(0, selection.toByte()); else -> byteArrayOf(0) }
        }
        service.setChannelMode(WifiChannelMode.AUTO) { dispatched++ }
        service.setChannelMode(WifiChannelMode.MANUAL) { dispatched++ }
        service.setChannel(6) { dispatched++ }; service.setChannel(149) { dispatched++ }
        assertEquals(listOf("22/00000000", "22/01000000", "43/000600", "43/019500"), calls.filter { it.startsWith("22/") || it.startsWith("43/") })
        assertEquals(4, dispatched)
        assertFailsWith<IllegalStateException> { service.setChannel(11) { dispatched++ } }
        selection = 0; assertFailsWith<IllegalStateException> { service.setChannel(6) { dispatched++ } }
        connectionMode = 2; assertFailsWith<IllegalStateException> { service.setChannelMode(WifiChannelMode.AUTO) { dispatched++ } }
        assertEquals(4, dispatched)
    }

    @Test fun channelReportsUseTheirOwnRequestWhileQueriesRemainNonQueued(): Unit = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val reports = mutableListOf<String>()
        val service = WifiCommands { _, _, id, p ->
            when (id) {
                0x39 -> { gate.await(); byteArrayOf(0, 0) }
                0x28 -> channelInfo(1, listOf(1))
                0x17 -> byteArrayOf(0, 1)
                0x29 -> { reports += p.hex(); byteArrayOf(0) }
                else -> error("unexpected")
            }
        }
        val query = async(start = CoroutineStart.UNDISPATCHED) { service.readRadio() }
        assertFailsWith<IllegalStateException> { service.setChannelMode(WifiChannelMode.AUTO) {} }
        service.setSignalReports(true); service.setSignalReports(false)
        assertEquals(listOf("01", "00"), reports)
        gate.complete(Unit); query.await(); service.close()
        assertEquals(RequestProblem.DISCONNECTED, assertFailsWith<RobotRequestException> { service.setSignalReports(true) }.problem)
    }

    @Test fun nativeSignalsKeepSignedValuesFilterIdentityAndExpireIndependently() {
        var clock = 0L
        val service = WifiCommands(nowMillis = { clock }) { _, _, _, _ -> error("push only") }
        val values = signalPayload(listOf(1 to -80, 149 to -65))
        val valid = DussFrame(0, 7, 2, 1, 0, 7, 0x2a, values, true)
        for (bad in listOf(valid.copy(valid = false), valid.copy(sender = 9), valid.copy(receiver = 7),
            valid.copy(attr = 0xc0), valid.copy(set = 3), valid.copy(id = 0x29), valid.copy(payload = values.copyOf(17)),
            valid.copy(payload = values.copyOf().apply { write32(0, 99999) }),
            valid.copy(payload = values.copyOf().apply { put16(8, 500) }))) {
            service.receive(bad); assertNull(service.signals.value.channels)
        }
        service.receive(valid)
        assertEquals(listOf(WifiChannelSignal(1, -80), WifiChannelSignal(149, -65)), service.signals.value.channels)
        val grouped = (values + signalPayload(listOf(149 to -70, -1 to -95)).drop(4).toByteArray() + byteArrayOf(9, 8, 7))
            .apply { write32(0, size.toLong()) }
        service.receive(valid.copy(payload = grouped))
        val expected = listOf(WifiChannelSignal(1, -80), WifiChannelSignal(149, -65), WifiChannelSignal(149, -70), WifiChannelSignal(-1, -95))
        assertEquals(expected, service.signals.value.channels)
        service.receive(valid.copy(payload = grouped.copyOf().apply { put16(values.size + 4, 500) }))
        assertEquals(expected, service.signals.value.channels) // A malformed later group cannot publish a partial result.
        clock = 500; service.receive(valid.copy(id = 0x3f, payload = byteArrayOf(0xff.toByte(), 0xff.toByte())))
        assertEquals(65535, service.signals.value.interferenceCode)
        clock = 1500; service.expire(); assertNull(service.signals.value.channels); assertEquals(65535, service.signals.value.interferenceCode)
        clock = 2000; service.expire(); assertNull(service.signals.value.interferenceCode)
        service.close(); service.receive(valid); assertEquals(WifiSignals(), service.signals.value)
    }
}

internal fun channelInfo(current: Int, vararg groups: List<Int>): ByteArray = ByteArray(10 + groups.sumOf { 8 + it.size * 2 }).apply {
    this[5] = current.toByte(); write32(6, groups.size.toLong())
    var cursor = 10
    groups.forEachIndexed { group, channels ->
        write32(cursor, (900 + group).toLong()); write32(cursor + 4, channels.size.toLong()); cursor += 8
        channels.forEach { put16(cursor, it); cursor += 2 }
    }
}
internal fun signalPayload(values: List<Pair<Int, Int>>): ByteArray = ByteArray(10 + values.size * 8).apply {
    write32(0, size.toLong()); write32(4, 998); put16(8, values.size)
    values.forEachIndexed { index, (channel, value) -> write32(10 + index * 8, channel.toLong()); write32(14 + index * 8, value.toLong()) }
}
private fun ByteArray.write32(offset: Int, value: Long) { repeat(4) { this[offset + it] = (value ushr (it * 8)).toByte() } }
