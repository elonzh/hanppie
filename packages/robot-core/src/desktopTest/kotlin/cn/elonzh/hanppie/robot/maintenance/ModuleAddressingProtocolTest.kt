package cn.elonzh.hanppie.robot.maintenance

import cn.elonzh.hanppie.robot.protocol.*
import cn.elonzh.hanppie.robot.extensions.arm.ArmServoBinding
import cn.elonzh.hanppie.robot.session.*
import kotlinx.coroutines.*
import kotlin.test.*

class ModuleAddressingProtocolTest {
    @Test fun wheelAndArmorUseTheirOwnResetStopAndSkipSelectors(): Unit = runBlocking {
        val sent = mutableListOf<String>()
        val protocol = ModuleAddressingProtocol { node, set, id, p -> sent += "$node/$set/$id/${p.hex()}"; byteArrayOf(0) }
        protocol.begin(ModuleAddressingKind.WHEELS); protocol.end(ModuleAddressingKind.WHEELS)
        protocol.begin(ModuleAddressingKind.ARMOR); protocol.skipArmor(); protocol.end(ModuleAddressingKind.ARMOR)
        assertEquals(listOf("195/63/62/02", "195/63/62/01", "195/63/62/02", "24/63/98/00", "24/63/98/fe", "24/63/98/ff"), sent)
    }
    @Test fun servoAndTofHaveDifferentDestinationsAndExplicitStepAndIdFields(): Unit = runBlocking {
        val sent = mutableListOf<String>()
        val protocol = ModuleAddressingProtocol { node, set, id, p -> sent += "$node/$set/$id/${p.hex()}"; byteArrayOf(0) }
        for (kind in listOf(ModuleAddressingKind.SERVO, ModuleAddressingKind.TOF)) {
            protocol.begin(kind); protocol.assign(kind, 1)
            protocol.assign(kind, if (kind == ModuleAddressingKind.SERVO) 3 else 4); protocol.end(kind)
        }
        assertEquals(listOf("195/63/102/0000", "195/63/102/0101", "195/63/102/0103", "195/63/102/0200",
            "9/63/102/0000", "9/63/102/0101", "9/63/102/0104", "9/63/102/0200"), sent)
        for (id in listOf(0, 4, 5, 256)) assertFailsWith<IllegalArgumentException> { protocol.assign(ModuleAddressingKind.SERVO, id) }
        assertFailsWith<IllegalArgumentException> { protocol.assign(ModuleAddressingKind.ARMOR, 1) }
        assertFailsWith<IllegalStateException> { protocol.begin(ModuleAddressingKind.SENSOR_ADAPTER) }
        assertFailsWith<IllegalStateException> { protocol.end(ModuleAddressingKind.SENSOR_ADAPTER) }
        assertEquals(8, sent.size)
    }
    @Test fun armReservationsUseTheArmCommandSetAndPreserveUnknownIds(): Unit = runBlocking {
        var response = "000102ff".hexBytes()
        val protocol = ModuleAddressingProtocol { node, set, id, p ->
            assertEquals(0xc3, node); assertEquals(0x33, set); assertEquals(0x68, id); assertTrue(p.isEmpty()); response
        }
        assertEquals(ArmServoBinding(1, 2), protocol.armServoIds())
        response = "00ff00".hexBytes(); assertEquals(ArmServoBinding(255, 0), protocol.armServoIds())
        response = "0001".hexBytes()
        assertEquals(RequestProblem.INVALID_RESPONSE, assertFailsWith<RobotRequestException> { protocol.armServoIds() }.problem)
    }
    @Test fun failedResetCannotStartWheelsAndCloseOrCancellationCannotWriteLater(): Unit = runBlocking {
        val sent = mutableListOf<Int>(); var failed = true
        val protocol = ModuleAddressingProtocol { _, _, _, p ->
            sent += p.u8(0)
            if (failed) throw RobotRequestException(RequestProblem.REJECTED)
            byteArrayOf(0)
        }
        assertEquals(RequestProblem.REJECTED, assertFailsWith<RobotRequestException> { protocol.begin(ModuleAddressingKind.WHEELS) }.problem)
        assertEquals(listOf(2), sent)
        failed = false; protocol.close()
        assertEquals(RequestProblem.DISCONNECTED, assertFailsWith<RobotRequestException> { protocol.begin(ModuleAddressingKind.WHEELS) }.problem)
        assertEquals(1, sent.size)
        val gate = CompletableDeferred<Unit>(); var count = 0
        val cancelled = ModuleAddressingProtocol { _, _, _, _ -> count++; gate.await(); byteArrayOf(0) }
        val first = launch(start = CoroutineStart.UNDISPATCHED) { cancelled.begin(ModuleAddressingKind.WHEELS) }
        first.cancelAndJoin(); gate.complete(Unit); assertEquals(1, count)
    }
    @Test fun wheelProgressChecksFrameIdentityAndDoesNotSwapStateWithPercent() {
        val frame = DussFrame(0, 0xc3, 2, 71, 0, 0x3f, 0x2f, byteArrayOf(1, 75), true)
        assertEquals(WheelEncodingReport(1, 75), wheelEncodingReport(frame))
        for (wrong in listOf(frame.copy(valid = false), frame.copy(sender = 3), frame.copy(receiver = 1), frame.copy(attr = 0xc0),
            frame.copy(set = 0), frame.copy(id = 0x3e), frame.copy(payload = byteArrayOf(1)))) assertNull(wheelEncodingReport(wrong))
        assertEquals(WheelEncodingReport(255, 255), wheelEncodingReport(frame.copy(payload = "ffff00".hexBytes())))
        assertNull(wheelEncodingReport(frame.copy(payload = "01ff".hexBytes()))?.percent)
    }
    @Test fun armorSignalsPreserveSequenceAndRawValueWithoutInventingAnAssignedId() {
        val frame = DussFrame(0, 0x18, 2, 72, 0, 0x3f, 0x63, byteArrayOf(1), true)
        assertEquals(ArmorAddressingSignal(72, 1), armorAddressingSignal(frame))
        for (wrong in listOf(frame.copy(valid = false), frame.copy(sender = 9), frame.copy(receiver = 1), frame.copy(attr = 0x40),
            frame.copy(set = 0), frame.copy(id = 0x62), frame.copy(payload = byteArrayOf()))) assertNull(armorAddressingSignal(wrong))
        assertEquals(ArmorAddressingSignal(72, 255), armorAddressingSignal(frame.copy(payload = "ff00".hexBytes())))
    }
    @Test fun wheelTestsUseNativeCanNodeModeAndOneSignedLittleEndianRpmValue(): Unit = runBlocking {
        val modes = mutableListOf<String>()
        val protocol = ModuleAddressingProtocol { node, set, id, p ->
            assertEquals(3, node); assertEquals(0x3f, set); assertEquals(0x19, id); modes += p.hex(); byteArrayOf(0)
        }
        protocol.enterWheelTestMode(); protocol.exitWheelTestMode(); assertEquals(listOf("01", "00"), modes)
        assertEquals(listOf("c800000000000000", "0000c80000000000", "00000000c8000000", "000000000000c800"),
            AddressingWheel.entries.map { wheelAddressingTestPayload(it).hex() })
        assertEquals("0000000000000000", wheelAddressingTestPayload(null).hex())
    }
    @Test fun testWheelStopUsesTheSameCanNodeAsThePositiveSpeed(): Unit = runBlocking {
        var writes = 0
        val protocol = ModuleAddressingProtocol { node, set, id, payload ->
            assertEquals(3, node); assertEquals(0x3f, set); assertEquals(0x20, id)
            assertEquals("0000000000000000", payload.hex()); writes++; byteArrayOf(0)
        }
        protocol.stopWheelTest(); assertEquals(1, writes)
        protocol.close()
        assertEquals(RequestProblem.DISCONNECTED, assertFailsWith<RobotRequestException> { protocol.stopWheelTest() }.problem)
        assertEquals(1, writes)
    }
    @Test fun identificationAddressesEachLeafAndNormalizesFlashWithoutChangingBodyLights(): Unit = runBlocking {
        val sent = mutableListOf<String>()
        val protocol = ModuleAddressingProtocol { node, set, id, p -> sent += "$node/$set/$id/${p.hex()}"; byteArrayOf(0) }
        protocol.setIdentificationLight(ModuleAddressingKind.SERVO, 1, true)
        protocol.setIdentificationLight(ModuleAddressingKind.SERVO, 4, false)
        protocol.setIdentificationLight(ModuleAddressingKind.TOF, 1, true)
        protocol.setIdentificationLight(ModuleAddressingKind.TOF, 4, false)
        assertEquals(listOf("57/0/96/ff0200ff00", "153/0/96/ff0100ff00", "50/0/96/ff0200ff00", "146/0/96/ff0100ff00"), sent)
        for (kind in listOf(ModuleAddressingKind.SERVO, ModuleAddressingKind.TOF)) for (id in listOf(0, 5, 256))
            assertFailsWith<IllegalArgumentException> { protocol.setIdentificationLight(kind, id, true) }
        assertFailsWith<IllegalArgumentException> { protocol.setIdentificationLight(ModuleAddressingKind.SENSOR_ADAPTER, 1, true) }
        assertEquals(4, sent.size)
        protocol.close()
        assertEquals(RequestProblem.DISCONNECTED,
            assertFailsWith<RobotRequestException> { protocol.setIdentificationLight(ModuleAddressingKind.TOF, 1, false) }.problem)
        assertEquals(4, sent.size)
    }
}
