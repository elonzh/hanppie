package cn.elonzh.hanppie.robot.product

import cn.elonzh.hanppie.robot.protocol.*
import cn.elonzh.hanppie.robot.session.*
import kotlinx.coroutines.*
import kotlin.test.*

class RobotPowerProtocolTest {
    @Test fun policyQueryPreservesNativeBooleanAndUnsignedTimeoutWithoutInventingDefaults(): Unit = runBlocking {
        var reply = "00002c01".hexBytes()
        val commands = PowerCommands({ node, set, id, payload ->
            assertEquals(9, node); assertEquals(0x3f, set); assertEquals(0x4b, id); assertTrue(payload.isEmpty()); reply
        })
        assertEquals(LowPowerPolicy(false, 300), commands.readPolicy())
        reply = "00ff ffff 00".replace(" ", "").hexBytes()
        assertEquals(LowPowerPolicy(true, 65535), commands.readPolicy())
        for (length in listOf(0, 1, 3)) {
            reply = ByteArray(length)
            assertEquals(RequestProblem.INVALID_RESPONSE, assertFailsWith<RobotRequestException> { commands.readPolicy() }.problem)
        }
    }

    @Test fun configurationStandbyAndBatteryPowerUseTheirDistinctNativeMessages(): Unit = runBlocking {
        val sent = mutableListOf<String>(); var dispatched = 0
        val commands = PowerCommands({ node, set, id, payload -> sent += "$node/$set/$id/${payload.hex()}"; byteArrayOf(0) })
        commands.setPolicy(LowPowerPolicy(true, 30)) { dispatched++ }
        commands.setPolicy(LowPowerPolicy(false, 65535)) { dispatched++ }
        for (action in RobotPowerAction.entries) commands.perform(action) { dispatched++ }
        assertEquals(listOf("9/63/74/011e00", "9/63/74/00ffff", "9/63/76/01", "9/63/76/00",
            "11/0/11/", "11/0/11/0002000000000000000000000000"), sent)
        assertEquals(6, dispatched)
        assertFailsWith<IllegalArgumentException> { commands.setPolicy(LowPowerPolicy(true, -1)) { dispatched++ } }
        assertFailsWith<IllegalArgumentException> { commands.setPolicy(LowPowerPolicy(true, 65536)) { dispatched++ } }
        assertEquals(6, dispatched); assertEquals(6, sent.size)
    }

    @Test fun errorsNeverRetryAndClosedServicesCannotDispatch(): Unit = runBlocking {
        var sent = 0; var dispatched = 0; var failure = RequestProblem.REJECTED
        val commands = PowerCommands({ _, _, _, _ -> sent++; throw RobotRequestException(failure) })
        for (problem in listOf(RequestProblem.REJECTED, RequestProblem.TIMED_OUT)) {
            failure = problem
            assertEquals(problem, assertFailsWith<RobotRequestException> { commands.perform(RobotPowerAction.SHUTDOWN) { dispatched++ } }.problem)
            assertEquals(problem, assertFailsWith<RobotRequestException> { commands.setPolicy(LowPowerPolicy(true, 60)) { dispatched++ } }.problem)
        }
        commands.close()
        assertEquals(RequestProblem.DISCONNECTED, assertFailsWith<RobotRequestException> { commands.perform(RobotPowerAction.REBOOT) { dispatched++ } }.problem)
        assertEquals(RequestProblem.DISCONNECTED, assertFailsWith<RobotRequestException> { commands.readPolicy() }.problem)
        assertEquals(4, sent); assertEquals(4, dispatched)
    }

    @Test fun overlappingRequestsDoNotQueueAndCancellationReleasesAccess(): Unit = runBlocking {
        var waiting = true; var sent = 0
        val commands = PowerCommands({ _, _, _, _ -> sent++; if (waiting) awaitCancellation(); byteArrayOf(0, 1, 30, 0) })
        val first = launch(start = CoroutineStart.UNDISPATCHED) { commands.readPolicy() }
        assertFailsWith<IllegalStateException> { commands.perform(RobotPowerAction.REBOOT) { error("Must not dispatch") } }
        first.cancelAndJoin(); waiting = false
        assertEquals(LowPowerPolicy(true, 30), commands.readPolicy()); assertEquals(2, sent)
    }
}
