package cn.elonzh.hanppie.robot.extensions

import cn.elonzh.hanppie.robot.product.*
import cn.elonzh.hanppie.robot.protocol.*
import kotlin.test.*

class ExtensionProtocolTest {
    @Test fun topicsMatchNativeUidsTenHertzLengthsAndSeparateMessageIds() {
        assertEquals("0211000001e759005f090002000a00", ExtensionKind.SERVO.topic().add().hex())
        assertEquals("02120000015ac0e486090002000a00", ExtensionKind.TOF.topic().add().hex())
        assertEquals("0213000001fc9fbbee090002000a00", ExtensionKind.ADAPTER.topic().add().hex())
        assertEquals(listOf("000211", "000212", "000213"), ExtensionKind.entries.map { it.topic().remove().hex() })
    }
    @Test fun servoFlagsAndSignedSpeedsAndAnglesFollowTheNativeFieldOrder() {
        val p = "09ffff00800001ff7f000000040008ffff".hexBytes()
        val values = ExtensionKind.SERVO.readings(extensionFrame(ExtensionKind.SERVO, p))!!.map { it as ServoReading }
        assertEquals(listOf(true, false, false, true), values.map { it.valid })
        assertEquals(listOf(-1, -32768, 256, 32767), values.map { it.speedRaw })
        assertEquals(listOf(0, 1024, 2048, -1), values.map { it.angleRaw })
        assertEquals(listOf(-180.0, 0.0, 180.0), values.take(3).map { it.angleDegrees })
        assertEquals(RobotComponent.SERVO_4, values.last().component)
    }
    @Test fun tofUsesBigEndianDistanceAndKeepsItsUninterpretedHeaderBytes() {
        val p = "feab800123000000000000000000000000000000".hexBytes()
        val values = ExtensionKind.TOF.readings(extensionFrame(ExtensionKind.TOF, p))!!.map { it as TofReading }
        assertEquals(TofReading(RobotComponent.TOF_1, 254, 171, 128, 291), values.first())
        assertEquals(RobotComponent.TOF_4, values.last().component)
    }
    @Test fun adapterKeepsTwoIoBytesAndUnsignedLittleEndianAdcWordsForEachOfSixModules() {
        val p = ByteArray(36).apply { this[30] = -1; this[31] = -128; put16(32, 0x1234); put16(34, 65535) }
        val values = ExtensionKind.ADAPTER.readings(extensionFrame(ExtensionKind.ADAPTER, p))!!.map { it as AdapterReading }
        assertEquals(AdapterReading(RobotComponent.SENSOR_ADAPTER_6, 255, 128, 4660, 65535), values.last())
    }
    @Test fun reportsRejectWrongHeadersSourcesAttributesIdsAndAnyWrongLength() {
        for (kind in ExtensionKind.entries) {
            val p = ByteArray(kind.topic().bytes)
            val frame = extensionFrame(kind, p)
            assertNotNull(kind.readings(frame))
            for (bad in listOf(frame.copy(valid = false), frame.copy(sender = 3), frame.copy(receiver = 3),
                frame.copy(attr = 0xc0), frame.copy(set = 0x3f), frame.copy(id = 3),
                frame.copy(payload = frame.payload.copyOf().apply { this[0] = 1 }),
                frame.copy(payload = frame.payload.copyOf().apply { this[1] = 1 }),
                frame.copy(payload = frame.payload.dropLast(1).toByteArray()), frame.copy(payload = frame.payload + 0)))
                assertNull(kind.readings(bad))
        }
    }
    @Test fun familyIndicesAndIdentificationCoverOnlyProtocolEstablishedModuleIds() {
        for (kind in ExtensionKind.entries) for (index in 1..kind.count) assertEquals(index, kind.index(kind.component(index)))
        assertNull(ExtensionKind.SERVO.index(RobotComponent.CLAW))
        assertFailsWith<IllegalArgumentException> { ExtensionKind.SERVO.component(5) }
        val cmd = moduleIdentificationCommand(RobotComponent.SENSOR_ADAPTER_6, true)
        assertEquals(0xd6, cmd.node); assertEquals(0, cmd.set); assertEquals(0x60, cmd.id)
        assertEquals("ff0200ff00", cmd.payload.hex())
        assertEquals("ff0100ff00", moduleIdentificationCommand(RobotComponent.SENSOR_ADAPTER_6, false).payload.hex())
    }
}

internal fun extensionFrame(kind: ExtensionKind, data: ByteArray): DussFrame = Protocol.frames(Protocol.duss(9, 2, 0, 0x48, 8,
    byteArrayOf(0, kind.topic().messageId.toByte()) + data, 1)).single()
