package cn.elonzh.hanppie.robot.maintenance.firmware

import cn.elonzh.hanppie.robot.protocol.DussFrame
import cn.elonzh.hanppie.robot.protocol.hex
import cn.elonzh.hanppie.robot.session.RequestProblem
import cn.elonzh.hanppie.robot.session.RobotRequestException
import kotlinx.coroutines.*
import kotlin.test.*

class FirmwareProtocolTest {
    @Test fun configUsesNativeOffsetAndContinuationWithoutTreatingItAsAnOffsetEcho(): Unit = runBlocking {
        val requests = mutableListOf<String>()
        val p = FirmwareProtocol { node, set, id, payload ->
            assertEquals(0x28, node); assertEquals(0, set); assertEquals(0x4f, id)
            requests += payload.hex()
            if (requests.size == 1) part("abc".encodeToByteArray(), 99) else part("def".encodeToByteArray(), 0)
        }
        assertEquals("abcdef", p.configuration().decodeToString())
        assertEquals(listOf("0100000000ffffffff", "0103000000ffffffff"), requests)
    }

    @Test fun configRejectsMissingHeadersLengthsOversizeAndNonProgress(): Unit = runBlocking {
        for (response in listOf(byteArrayOf(0), part(byteArrayOf(1), 0).copyOf(9), part(byteArrayOf(), 1),
            part(byteArrayOf(1), 0).apply { this[4] = 1 }, part(byteArrayOf(1), 0) + 0)) {
            val p = FirmwareProtocol { _, _, _, _ -> response }
            assertEquals(RequestProblem.INVALID_RESPONSE, assertFailsWith<RobotRequestException> { p.configuration() }.problem)
        }
        var calls = 0
        val p = FirmwareProtocol { _, _, _, _ -> calls++; part(ByteArray(524_289), 1) }
        assertEquals(RequestProblem.INVALID_RESPONSE, assertFailsWith<RobotRequestException> { p.configuration() }.problem)
        assertEquals(2, calls)
    }

    @Test fun loaderAndFtpNegotiationUseDifferentPayloadsAndTheReturnedEndpoint(): Unit = runBlocking {
        val sent = mutableListOf<Pair<Int, String>>()
        val p = FirmwareProtocol { node, set, id, payload ->
            assertEquals(0x28, node); assertEquals(0, set); sent += id to payload.hex()
            if (id == 8) endpoint() else byteArrayOf(0)
        }
        p.enterLoader()
        assertEquals(FirmwareFtpTarget("192.168.2.1", 2121, "/upgrade/package.tar"), p.requestTransfer(0x12345678))
        assertEquals(listOf(7 to "000000000000000000", 8 to "00785634120000000000000204"), sent)
    }

    @Test fun endpointRejectsUnterminatedOrUnsafePathsAndInvalidAddressOrPort(): Unit = runBlocking {
        val bad = listOf(endpoint().copyOf(70), endpoint().apply { for (i in 7..70) this[i] = 65 },
            endpoint("/upgrade/../package.tar"), endpoint("/upgrade/file\r\n.tar"), endpoint("relative.tar"), endpoint("/"),
            endpoint().apply { this[8] = 255.toByte() },
            endpoint().apply { fill(0, 1, 5) }, endpoint().apply { fill(0, 5, 7) })
        for (response in bad) {
            val p = FirmwareProtocol { _, _, _, _ -> response }
            assertEquals(RequestProblem.INVALID_RESPONSE, assertFailsWith<RobotRequestException> { p.requestTransfer(1) }.problem)
        }
    }

    @Test fun transferEndCarriesRawDigestAndF7DoesNotBecomeInstallSuccess(): Unit = runBlocking {
        val sent = mutableListOf<Pair<Int, String>>()
        var result = 0
        val p = FirmwareProtocol { _, _, id, payload -> sent += id to payload.hex(); byteArrayOf(result.toByte()) }
        val md5 = ByteArray(16) { it.toByte() }
        assertEquals(0, p.finishTransfer(md5))
        result = 0xf7; assertEquals(0xf7, p.finishTransfer(md5))
        result = 7
        assertEquals(7, assertFailsWith<RobotRequestException> { p.finishTransfer(md5) }.responseCode)
        result = 0; p.stopReports { _, _, id, payload -> sent += id to payload.hex() }
        assertEquals(0x0a to "00000102030405060708090a0b0c0d0e0f", sent.first())
        assertEquals(0x41 to "", sent.last())
    }

    @Test fun sessionRejectionF7IsAcceptedButOtherFailuresPropagate(): Unit = runBlocking {
        val p = FirmwareProtocol { _, _, _, _ -> throw RobotRequestException(RequestProblem.REJECTED, 0xf7) }
        assertEquals(0xf7, p.finishTransfer(ByteArray(16)))
        for (problem in listOf(RequestProblem.TIMED_OUT, RequestProblem.DISCONNECTED)) {
            val failed = FirmwareProtocol { _, _, _, _ -> throw RobotRequestException(problem) }
            assertEquals(problem, assertFailsWith<RobotRequestException> { failed.finishTransfer(ByteArray(16)) }.problem)
        }
    }

    @Test fun invalidInputsNeverWriteAndCloseRejectsLateResultsOrFurtherDispatch(): Unit = runBlocking {
        var count = 0
        lateinit var p: FirmwareProtocol
        p = FirmwareProtocol { _, _, _, _ -> count++; p.close(); byteArrayOf(0) }
        assertFailsWith<IllegalArgumentException> { p.requestTransfer(0) }
        assertFailsWith<IllegalArgumentException> { p.requestTransfer(0x100000000) }
        assertFailsWith<IllegalArgumentException> { p.finishTransfer(ByteArray(32)) }
        assertEquals(0, count)
        assertEquals(RequestProblem.DISCONNECTED, assertFailsWith<RobotRequestException> { p.enterLoader() }.problem)
        assertEquals(RequestProblem.DISCONNECTED, assertFailsWith<RobotRequestException> { p.enterLoader() }.problem)
        p.stopReports { _, _, _, _ -> count++ }
        assertEquals(1, count)
    }

    @Test fun canceledConfigurationCannotRequestTheNextPart(): Unit = runBlocking {
        var count = 0
        val gate = CompletableDeferred<Unit>()
        val p = FirmwareProtocol { _, _, _, _ -> count++; gate.await(); part(byteArrayOf(1), 1) }
        val job = launch(start = CoroutineStart.UNDISPATCHED) { p.configuration() }
        job.cancelAndJoin(); gate.complete(Unit); assertEquals(1, count)
    }

    @Test fun reportsPreserveNativeTypesAndPackedDetail() {
        val report = assertNotNull(firmwareUpgradeReport(frame(byteArrayOf(3, 83, 0xa9.toByte()))))
        assertEquals(FirmwareReportKind.PROGRESS, report.kind); assertEquals(83, report.progress)
        assertEquals(9, report.module); assertEquals(5, report.step)
        for ((code, kind) in listOf(2 to FirmwareReportKind.CONFIRM, 4 to FirmwareReportKind.END, 6 to FirmwareReportKind.REBOOT, 5 to FirmwareReportKind.UNKNOWN)) {
            val r = assertNotNull(firmwareUpgradeReport(frame(byteArrayOf(code.toByte(), 1, 77))))
            assertEquals(kind, r.kind); assertNull(r.progress); assertNull(r.module); assertNull(r.step)
            assertEquals(77, r.detailRaw)
        }
        assertNull(firmwareUpgradeReport(frame(byteArrayOf(3, 255.toByte(), 0)))?.progress)
    }

    @Test fun reportsRejectWrongSourceAttributesAndMalformedData() {
        val f = frame(byteArrayOf(3, 50, 2))
        for (bad in listOf(f.copy(valid = false), f.copy(sender = 8), f.copy(receiver = 1), f.copy(attr = 0x80),
            f.copy(set = 0x3f), f.copy(id = 0x47), f.copy(payload = byteArrayOf(3, 50)))) assertNull(firmwareUpgradeReport(bad))
        assertEquals(FirmwareUpgradeReport(4, 1, 3), firmwareUpgradeReport(frame(byteArrayOf(4, 1, 3, 99))))
        assertEquals(1, firmwareRebootReport(f.copy(id = 0x47, payload = byteArrayOf(1))))
        assertNull(firmwareRebootReport(f.copy(id = 0x47, payload = byteArrayOf())))
        assertNull(firmwareRebootReport(f.copy(id = 0x47, sender = 8)))
    }
}

private fun frame(p: ByteArray) = DussFrame(0, 0x28, 2, 71, 0, 0, 0x42, p, true)
private fun part(data: ByteArray, continuation: Int) = ByteArray(9).apply {
    repeat(4) { this[1 + it] = (data.size ushr (it * 8)).toByte(); this[5 + it] = (continuation ushr (it * 8)).toByte() }
} + data
private fun endpoint(path: String = "/upgrade/package.tar") = ByteArray(71).apply {
    this[1] = 1; this[2] = 2; this[3] = 168.toByte(); this[4] = 192.toByte()
    this[5] = 0x49; this[6] = 8
    path.encodeToByteArray().copyInto(this, 7)
}
