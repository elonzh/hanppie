package cn.elonzh.hanppie.robot.lab.mobile

import cn.elonzh.hanppie.robot.protocol.*
import kotlin.test.*

class MobileInfoRelayTest {
    private val owner = "0123456789abcdef0123456789abcdef"
    private class Source(override val availableMask: Int = 7) : MobileSensorSource {
        var active = 0; var failStart = false; var failRead = false
        var data = emptyList<MobileSensorReading>()
        override fun start(mask: Int) { if (failStart) error("denied"); active = mask }
        override fun read(): List<MobileSensorReading> { if (failRead) error("sensor failed"); return data }
        override fun stop() { active = 0; data = emptyList() }
    }
    private inner class Rig(val source: Source = Source()) {
        var time = 1000L
        val acks = mutableListOf<Pair<DussFrame, Int>>()
        val sent = mutableListOf<ByteArray>()
        val logs = mutableListOf<String>()
        val relay = MobileInfoRelay(source, { time }, { f, r -> acks += f to r }, { sent += it }, { logs += it })
        fun frame(mask: Int = 15) = DussFrame(0, 0xc9, 2, 731, 0x40, 0x3f, 0xab,
            byteArrayOf(mask.toByte(), (mask ushr 8).toByte()), true)
        fun ready(mask: Int = 15) { relay.reset(owner); relay.receive(frame(mask), true) }
        fun sample(kind: MobileSensor = MobileSensor.ACCELERATION, stamp: Long = 1, age: Long = 0) =
            MobileSensorReading(kind, 1.25f, -2f, 3f, stamp, age)
    }

    @Test fun nativeSubscriptionProducesCorrelatedAckAndScriptTlvFloats() {
        val r = Rig(); r.ready()
        assertEquals(7, r.source.active)
        assertEquals(731, r.acks.single().first.sequence); assertEquals(0, r.acks.single().second)
        r.source.data = MobileSensor.entries.map { r.sample(it) }; r.relay.tick(true)
        assertEquals("010c0000a03f000000c000004040020c0000a03f000000c000004040030c0000a03f000000c000004040", r.sent.single().hex())
    }

    @Test fun idleBeforeRunningPreservesSubscriptionButNativeTerminalSourcesRetireIt() {
        for (sender in listOf(9, 0xa9)) {
            val r = Rig(); r.ready(1)
            fun status(value: Int) = DussFrame(46, sender, 2, 10, 0, 0x3f, 0xa5,
                byteArrayOf(value.toByte()) + owner.encodeToByteArray(), true)
            r.relay.receive(status(0), true)
            assertEquals(1, r.source.active, "Pending owner survives the native pre-start idle")
            r.relay.receive(status(2), true)
            r.relay.receive(status(0), true)
            assertEquals(0, r.source.active)
            r.relay.receive(r.frame(1), true)
            assertEquals(1, r.acks.last().second, "An ended owner cannot subscribe again")
            r.ready(1); r.relay.receive(status(0), true)
            assertEquals(1, r.source.active, "New owner does not inherit the old started state")
            r.relay.receive(status(5), true)
            assertEquals(0, r.source.active, "Failure before RUNNING still retires the owner")
        }
    }

    @Test fun onlyExactScriptRequestsCanChangeTheSubscription() {
        val r = Rig(); r.ready(1)
        val bad = listOf(r.frame().copy(sender=9), r.frame().copy(receiver=0x42), r.frame().copy(attr=0x80),
            r.frame().copy(set=0x0a), r.frame().copy(id=0xac), r.frame().copy(valid=false))
        bad.forEach { r.relay.receive(it, true) }
        assertEquals(1, r.acks.size); assertEquals(1, r.source.active)
        listOf(byteArrayOf(), byteArrayOf(7), byteArrayOf(7,0,0), byteArrayOf(0x10,0)).forEach {
            r.relay.receive(r.frame().copy(payload=it), true); assertEquals(1, r.acks.last().second)
        }
        assertEquals(1, r.source.active)
    }

    @Test fun noOwnerAndDesktopNeverStartSamplingOrInventZeroReadings() {
        val r = Rig(Source(0))
        r.relay.receive(r.frame(), true); assertEquals(1, r.acks.last().second)
        r.ready(); assertEquals(1, r.acks.last().second); assertEquals(0, r.source.active)
        r.relay.tick(true); assertTrue(r.sent.isEmpty()); assertEquals(1, r.logs.size)
        r.relay.receive(r.frame(0), true); assertEquals(0, r.acks.last().second)
    }

    @Test fun unsubscribeAndReplacementRetireOldReadings() {
        val r = Rig(); r.ready(1); r.source.data = listOf(r.sample())
        r.relay.receive(r.frame(4), true)
        r.relay.tick(true); assertTrue(r.sent.isEmpty()); assertEquals(4, r.source.active)
        r.source.data = listOf(r.sample(MobileSensor.ROTATION_RATE))
        r.relay.tick(true); r.time += 100; r.relay.tick(true); assertEquals(1, r.sent.size)
        r.relay.receive(r.frame(0), true); assertEquals(0, r.source.active)
        r.time += 100; r.relay.tick(true); assertEquals(1, r.sent.size)
    }

    @Test fun backgroundOrExpiredLinkRequiresANewSubscription() {
        val r = Rig(); r.ready(); r.source.data = listOf(r.sample()); r.relay.tick(true)
        r.relay.tick(false); assertEquals(0, r.source.active)
        r.source.data = listOf(r.sample(stamp=2)); r.time += 100; r.relay.tick(true)
        assertEquals(1, r.sent.size)
        r.relay.receive(r.frame(), false); assertEquals(1, r.acks.last().second)
        r.relay.receive(r.frame(), true); assertEquals(7, r.source.active)
        r.relay.reset("f".repeat(32)); assertEquals(0, r.source.active)
        r.relay.tick(true); assertEquals(1, r.sent.size)
    }

    @Test fun rateLimitAndFreshnessRejectReplayInvalidAndOldSamples() {
        val r = Rig(); r.ready()
        r.source.data = listOf(r.sample()); r.relay.tick(true)
        r.source.data = listOf(r.sample(stamp=2)); r.time += 99; r.relay.tick(true); assertEquals(1, r.sent.size)
        r.time++; r.relay.tick(true); assertEquals(2, r.sent.size)
        r.time += 100; r.relay.tick(true); assertEquals(2, r.sent.size)
        r.source.data = listOf(r.sample(stamp=1), r.sample(stamp=3,age=251), r.sample(stamp=4,age=-1),
            r.sample(stamp=5).copy(x=Float.NaN), r.sample(stamp=6).copy(z=Float.POSITIVE_INFINITY))
        r.time += 100; r.relay.tick(true); assertEquals(2, r.sent.size)
        r.time += 3000; r.relay.tick(true); assertEquals(0, r.source.active); assertEquals(1, r.logs.size)
    }

    @Test fun partialAvailabilityAndNativeFailuresRemainVisibleAndStop() {
        val r = Rig(Source(1)); r.ready()
        assertEquals(1, r.source.active); assertEquals(1, r.acks.last().second); assertEquals(1,r.logs.size)
        r.source.failRead = true; r.relay.tick(true); assertEquals(0,r.source.active); assertEquals(2,r.logs.size)
        r.source.failRead = false; r.source.failStart = true
        r.relay.receive(r.frame(1),true); assertEquals(1,r.acks.last().second); assertEquals(0,r.source.active)
    }

    @Test fun AWorkingAccelerometerCannotHideAStalledGyroscope() {
        val r = Rig(); r.ready(5)
        repeat(31) { index ->
            r.source.data = listOf(r.sample(stamp=index.toLong()))
            r.relay.tick(true); r.time += 100
        }
        assertEquals(0,r.source.active)
        assertTrue(r.logs.single().contains("ROTATION_RATE"))
    }

    @Test fun matchingTerminalStatusAndCloseClearTheOwner() {
        val r = Rig(); r.ready()
        val status = DussFrame(0,9,2,1,0,0x3f,0xa5,byteArrayOf(5)+owner.encodeToByteArray(),true)
        r.relay.receive(status.copy(sender=2),true); assertEquals(7,r.source.active)
        r.relay.receive(status.copy(payload=byteArrayOf(5)+"f".repeat(32).encodeToByteArray()),true)
        assertEquals(7,r.source.active)
        r.relay.receive(status,true); assertEquals(0,r.source.active)
        r.relay.receive(r.frame(),true); assertEquals(1,r.acks.last().second)
        r.ready(); r.relay.close(); r.relay.receive(r.frame(),true)
        assertEquals(0,r.source.active); assertEquals(3,r.acks.size)
    }
}
