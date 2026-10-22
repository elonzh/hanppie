package cn.elonzh.hanppie.robot.telemetry

import cn.elonzh.hanppie.robot.protocol.*
import kotlin.test.*

class RobotHealthTest {
    @Test fun combinedBatteryRetainsSignedRawFieldsAndRejectsUnrelatedFrames() {
        val store = RobotHealthStore { 0 }
        val payload = ByteArray(62).apply {
            this[1] = 1; put16(2, 12345); put16(4, -123); put32(6, -1200); this[10] = 87
        }
        val frame = DussFrame(0, 9, 2, 1, 0, 0x48, 8, payload, true)
        for (invalid in listOf(frame.copy(valid = false), frame.copy(sender = 3), frame.copy(receiver = 9),
            frame.copy(attr = 0xc0), frame.copy(set = 0x3f), frame.copy(id = 7), frame.copy(payload = payload.copyOf(61)),
            frame.copy(payload = payload.copyOf().apply { this[0] = 1 }), frame.copy(payload = payload.copyOf().apply { this[1] = 2 }))) {
            store.receive(invalid); assertNull(store.state.value.battery)
        }
        store.receive(frame)
        assertEquals(BatteryTelemetry(12345, -123, -1200, 87), store.state.value.battery)
        assertEquals(87, Telemetry.motion(frame)?.batteryPercent)
        store.receive(frame.copy(payload = payload.copyOf().apply { put16(4, -32768); put32(6, Int.MIN_VALUE); this[10] = -1 }))
        assertEquals(BatteryTelemetry(12345, -32768, Int.MIN_VALUE, null), store.state.value.battery)
    }

    @Test fun moduleExceptionWordsAreAtomicAndFreshnessIsIndependent() {
        var clock = 0L
        val store = RobotHealthStore { clock }
        val frame = DussFrame(0, 0x28, 2, 1, 0, 0x3f, 0x12,
            byteArrayOf(2, 0, 3, 2, 4, 0x82.toByte(), 4, 0x82.toByte(), 0x34, 0x12, 1, 5, 0xc0.toByte()), true)
        for (bad in listOf(frame.copy(sender = 9), frame.copy(receiver = 9), frame.copy(attr = 0xc0),
            frame.copy(payload = frame.payload.copyOf(frame.payload.size - 1)))) {
            store.receive(bad); assertNull(store.state.value.modules)
        }
        store.receive(frame)
        assertEquals(listOf(0x300, 0x1234), store.state.value.modules?.map { it.deviceId })
        assertEquals(2, store.state.value.issues.size)
        assertEquals("0x1234C005", store.state.value.issues.first().hexCode)
        val heat = store.state.value.issues.last()
        assertEquals(2, heat.severity); assertEquals(2, heat.subDevice); assertEquals(4, heat.message)
        assertEquals(0x30a, heat.affectedDeviceId)
        clock = 1000
        store.receive(DussFrame(0, 9, 2, 2, 0, 0x48, 8, ByteArray(62).apply { this[1] = 1 }, true))
        clock = 3000; store.expire()
        assertNotNull(store.state.value.modules); assertNotNull(store.state.value.battery)
        assertEquals(3, store.state.value.moduleReportAgeSeconds)
        assertEquals(2, store.state.value.issues.size); assertTrue(store.state.value.unreportedDeviceIds.isEmpty())
        store.receive(frame.copy(payload = byteArrayOf(0)))
        assertEquals(emptyList(), store.state.value.modules)
        assertEquals(setOf(0x300, 0x1234), store.state.value.unreportedDeviceIds)
        clock = 4000; store.expire(); assertNull(store.state.value.battery)
        store.close(); clock = 5000; store.expire(); store.receive(frame); assertEquals(RobotHealth(), store.state.value)
    }

    @Test fun issueIdentityRetainsFullModuleAndNativeSeverity() {
        val issue = RobotIssue(0xabcd, 0xffff)
        assertEquals("0xABCDFFFF", issue.hexCode); assertEquals(3, issue.severity)
        assertEquals(63, issue.subDevice); assertEquals(255, issue.message)
        assertEquals(0x317, RobotIssue(0x300, 0x8e0e).affectedDeviceId)
        assertEquals(0x30a, RobotIssue(0x100, 0x14).affectedDeviceId)
        assertEquals(0x1806, RobotIssue(0x100, 0x1f).affectedDeviceId)
    }
}

private fun ByteArray.put32(offset: Int, value: Int) { repeat(4) { this[offset + it] = (value ushr (it * 8)).toByte() } }
