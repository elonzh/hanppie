package cn.elonzh.hanppie.robot.network

import cn.elonzh.hanppie.robot.protocol.hex
import cn.elonzh.hanppie.robot.session.*
import kotlinx.coroutines.*
import kotlin.test.*

class WifiProtocolTest {
    @Test fun apReadsModeAndLengthPrefixedUtf8WithoutConsumingTrailingBytes(): Unit = runBlocking {
        val calls = mutableListOf<Int>()
        val service = WifiCommands { node, set, id, payload ->
            assertEquals(7, node); assertEquals(7, set); assertTrue(payload.isEmpty()); calls += id
            when (id) { 0x39 -> byteArrayOf(0, 0); 7 -> reply("RMS1_Test"); 0x0e -> reply("密码测试1234") + byteArrayOf(99); else -> error("unexpected command") }
        }
        assertEquals(RobotWifiProfile(0, "RMS1_Test", "密码测试1234"), service.read())
        assertEquals(listOf(0x39, 7, 0x0e), calls)
    }

    @Test fun stationAndUnknownModesNeverQueryARouterPasswordAndMalformedRepliesFail(): Unit = runBlocking {
        var mode = byteArrayOf(0, 2)
        var name = reply("router")
        val ids = mutableListOf<Int>()
        val service = WifiCommands { _, _, id, _ -> ids += id; if (id == 0x39) mode else name }
        for (code in listOf(2, 255)) {
            mode = byteArrayOf(0, code.toByte())
            assertEquals(RobotWifiProfile(code, "router", null), service.read())
        }
        assertEquals(listOf(0x39, 7, 0x39, 7), ids)
        for (invalid in listOf(byteArrayOf(), byteArrayOf(0), byteArrayOf(0, 9, 65), byteArrayOf(0, 1, 0), byteArrayOf(0, 1, 0xff.toByte()))) {
            name = invalid
            assertEquals(RequestProblem.INVALID_RESPONSE, assertFailsWith<RobotRequestException> { service.read() }.problem)
        }
        mode = byteArrayOf(0)
        assertEquals(RequestProblem.INVALID_RESPONSE, assertFailsWith<RobotRequestException> { service.read() }.problem)
    }

    @Test fun writesUseUtf8ByteLengthsAndExposeEachAcknowledgementWithoutRebooting(): Unit = runBlocking {
        val calls = mutableListOf<Pair<Int, String>>()
        val confirmed = mutableListOf<WifiConfirmedField>()
        val service = WifiCommands { node, set, id, payload ->
            assertEquals(7, node); assertEquals(7, set); calls += id to payload.hex()
            if (id == 0x39) byteArrayOf(0, 0) else byteArrayOf(0)
        }
        val credentials = RobotApCredentials("RMS1_New", "密码测试1234")
        service.configure(credentials, confirmed::add)
        assertEquals(listOf(0x39 to "", 8 to "08524d53315f4e6577", 0x0d to "10e5af86e7a081e6b58be8af9531323334"), calls)
        assertEquals(listOf(WifiConfirmedField.SSID, WifiConfirmedField.PASSWORD), confirmed)
        service.reboot(); assertEquals(0x15 to "", calls.last())
    }

    @Test fun partialFailureAndCancellationNeverWriteTheNextFieldOrRetry(): Unit = runBlocking {
        val ids = mutableListOf<Int>()
        val confirmed = mutableListOf<WifiConfirmedField>()
        var reject = true
        val service = WifiCommands { _, _, id, _ ->
            ids += id
            if (id == 0x0d && reject) throw RobotRequestException(RequestProblem.REJECTED, 1)
            if (id == 0x39) byteArrayOf(0, 0) else byteArrayOf(0)
        }
        val credentials = RobotApCredentials("RMS1_New", "password")
        assertFailsWith<RobotRequestException> { service.configure(credentials, confirmed::add) }
        assertEquals(listOf(WifiConfirmedField.SSID), confirmed)
        assertEquals(listOf(0x39, 8, 0x0d), ids)
        reject = false; ids.clear()
        val task = launch { service.configure(credentials) { if (it == WifiConfirmedField.SSID) cancel() } }
        task.join(); assertTrue(task.isCancelled)
        assertEquals(listOf(0x39, 8), ids)
        service.reboot(); assertEquals(0x15, ids.last())
    }

    @Test fun modeAndConcurrentOperationGuardsReleaseAfterCancellationAndClose(): Unit = runBlocking {
        var mode = 2
        val gate = CompletableDeferred<Unit>()
        var wait = false
        var writes = 0
        val service = WifiCommands { _, _, id, _ ->
            if (wait) gate.await()
            if (id == 0x39) byteArrayOf(0, mode.toByte()) else { writes++; byteArrayOf(0) }
        }
        assertFailsWith<IllegalStateException> { service.configure(RobotApCredentials("test", "password")) {} }
        assertEquals(0, writes)
        mode = 0; wait = true
        val task = launch(start = CoroutineStart.UNDISPATCHED) { service.read() }
        assertFailsWith<IllegalStateException> { service.reboot() }
        task.cancelAndJoin(); wait = false
        service.reboot(); assertEquals(1, writes)
        service.close()
        assertEquals(RequestProblem.DISCONNECTED, assertFailsWith<RobotRequestException> { service.reboot() }.problem)
    }

    @Test fun credentialValidationMatchesTheOfficialInputBoundaryAndNeverPrintsSecrets() {
        for (ssid in listOf("", "a".repeat(33), "机器人", "a b", "a/b")) assertFalse(RobotApCredentials.validSsid(ssid))
        assertTrue(RobotApCredentials.validSsid("A_1-test"))
        for (password in listOf("short", "a".repeat(17), "1234\n678", "1234567\uD800")) assertFalse(RobotApCredentials.validPassword(password))
        assertTrue(RobotApCredentials.validPassword("密码测试1234"))
        assertFalse(RobotApCredentials("secretname", "password").toString().contains("secretname"))
        assertFalse(RobotWifiProfile(0, "secretname", "password").toString().contains("password"))
    }
}

private fun reply(value: String): ByteArray = value.encodeToByteArray().let { byteArrayOf(0, it.size.toByte()) + it }
