package cn.elonzh.hanppie.robot.product

import cn.elonzh.hanppie.robot.telemetry.RobotSystemStatus
import cn.elonzh.hanppie.robot.protocol.*
import cn.elonzh.hanppie.robot.session.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class RobotInformationProtocolTest {
    @Test fun firmwareQueriesUseNativeNodesEmptyPayloadAndBothNativeVersionFormats(): Unit = runBlocking {
        val destinations = listOf(0x28, 0x49, 0xa9, 1, 0xc3, 0x0b, 4, 0x24, 0x0c, 0x2c, 0x4c, 0x6c,
            0x38, 0x58, 0x78, 0x98, 0xb8, 0xd8, 0x17, 0x37, 0x39, 0x59, 0x79, 0x99,
            0x32, 0x52, 0x72, 0x92, 0x36, 0x56, 0x76, 0x96, 0xb6, 0xd6, 0x3b, 0xf1, 0xc9)
        var node = 0
        var response = ByteArray(28).apply { this[22] = 74; this[23] = 6; this[24] = 2; this[25] = 1 }
        val commands = InformationCommands({ receiver, set, id, payload ->
            node = receiver; assertEquals(0, set); assertEquals(1, id); assertTrue(payload.isEmpty()); response
        })
        RobotVersionTarget.entries.forEachIndexed { index, target ->
            assertEquals(if (target == RobotVersionTarget.CAMERA) "01.02.16.10" else "01.02.06.74", commands.readFirmware(target))
            assertEquals(destinations[index], node)
        }
        response = ByteArray(26) { 0xff.toByte() }.apply { this[0] = 0 }
        assertEquals("255.255.255.255", commands.readFirmware(RobotVersionTarget.SYSTEM))
        // Native camera build conversion uses an eight-bit quotient, without a guessed version range.
        assertEquals("255.255.143.35", commands.readFirmware(RobotVersionTarget.CAMERA))
        for (length in listOf(0, 4, 22, 25)) {
            response = ByteArray(length)
            assertEquals(RequestProblem.INVALID_RESPONSE,
                assertFailsWith<RobotRequestException> { commands.readFirmware(RobotVersionTarget.SYSTEM) }.problem)
        }
    }

    @Test fun activationIsOnlyTheNativeReadOperationAndUsesBitZero(): Unit = runBlocking {
        var response = byteArrayOf(0, 0)
        var sent = 0
        val commands = InformationCommands({ receiver, set, id, payload ->
            sent++; assertEquals(0x28, receiver); assertEquals(0, set); assertEquals(0x32, id)
            assertEquals("1100", payload.hex()); response
        })
        for ((flag, active) in listOf(0 to false, 1 to true, 0xfe to false, 0xff to true)) {
            response = byteArrayOf(0, flag.toByte(), 0x11)
            assertEquals(active, commands.readActivated())
        }
        for (length in listOf(0, 1)) {
            response = ByteArray(length)
            assertEquals(RequestProblem.INVALID_RESPONSE, assertFailsWith<RobotRequestException> { commands.readActivated() }.problem)
        }
        commands.close()
        assertEquals(RequestProblem.DISCONNECTED, assertFailsWith<RobotRequestException> { commands.readActivated() }.problem)
        assertEquals(RequestProblem.DISCONNECTED, assertFailsWith<RobotRequestException> { commands.readFirmware(RobotVersionTarget.SYSTEM) }.problem)
        assertEquals(6, sent)
    }

    @Test fun failedQueriesRemainFailuresWithoutInventingVersionsOrActivation(): Unit = runBlocking {
        var sent = 0
        var failure = RequestProblem.REJECTED
        val commands = InformationCommands({ _, _, _, _ -> sent++; throw RobotRequestException(failure) })
        for (problem in listOf(RequestProblem.REJECTED, RequestProblem.TIMED_OUT)) {
            failure = problem
            assertEquals(problem, assertFailsWith<RobotRequestException> { commands.readActivated() }.problem)
            assertEquals(problem, assertFailsWith<RobotRequestException> { commands.readFirmware(RobotVersionTarget.SYSTEM) }.problem)
        }
        assertEquals(4, sent)
    }

    @Test fun usageFiltersFramesRetainsRobotSnapshotsWithoutInventingZeroOrElapsedTime() {
        var clock = 1000L
        val commands = InformationCommands({ _, _, _, _ -> error("Pushes do not query") }, { clock })
        val frame = usageFrame("00000000e8030000100e0000")
        for (wrong in listOf(frame.copy(valid = false), frame.copy(sender = 9), frame.copy(receiver = 1),
            frame.copy(attr = 0x40), frame.copy(set = 0), frame.copy(id = 4), frame.copy(payload = ByteArray(11)))) {
            commands.receive(wrong); assertNull(commands.systemStatus.value)
        }
        commands.receive(frame); assertEquals(RobotSystemStatus(1000, 3600, false), commands.systemStatus.value)
        clock += 123_000; commands.updateAge()
        assertEquals(RobotSystemStatus(1000, 3600, false, 123), commands.systemStatus.value)
        commands.receive(usageFrame("00000000ffffffff00000000"))
        assertEquals(RobotSystemStatus(null, 0, false), commands.systemStatus.value)
        commands.receive(usageFrame("000000000000000000000080aa"))
        assertEquals(RobotSystemStatus(0, null, false), commands.systemStatus.value)
        commands.receive(usageFrame("ff0000000000000000000000"))
        assertEquals(true, commands.systemStatus.value?.lowPower)
        commands.receive(usageFrame("fe0000000000000000000000"))
        assertEquals(false, commands.systemStatus.value?.lowPower)
        commands.close(); commands.receive(frame); commands.updateAge(); assertNull(commands.systemStatus.value)
    }
}

private fun usageFrame(payload: String) = DussFrame(0, 0x28, 2, 7, 0, 0x3f, 3, payload.hexBytes(), true)
