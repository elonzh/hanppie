package cn.elonzh.hanppie.robot.gamepad

import cn.elonzh.hanppie.robot.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlin.test.*

class GamepadSessionTest {
    private class Wire : GamepadTransport {
        val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        val outgoing = Channel<ByteArray>(Channel.UNLIMITED)
        var closes = 0
        override suspend fun read() = incoming.receiveCatching().getOrNull()
        override suspend fun write(bytes: ByteArray) { outgoing.send(bytes) }
        override fun close() { if (closes == 0) { closes++; incoming.close() } }
        suspend fun request() = Protocol.frames(withTimeout(2_000) { outgoing.receive() }).single()
        suspend fun reply(request: DussFrame, payload: ByteArray, sender: Int = 6, receiver: Int = 2,
            attr: Int = 0x80, sequence: Int = request.sequence) {
            incoming.send(Protocol.duss(sender, receiver, attr, request.set, request.id, payload, sequence))
        }
    }
    private class Connector : GamepadConnector {
        override var available = true
        var devices = listOf(GamepadAccessory("one", "Fixture Gamepad"))
        val opened = Channel<Wire>(Channel.UNLIMITED)
        var open: suspend () -> Wire = { Wire() }
        var opens = 0
        override fun accessories() = devices
        override suspend fun open(id: String): GamepadTransport { opens++; return open().also { opened.send(it) } }
    }
    private suspend fun await(test: () -> Boolean) { withTimeout(2_000) { while (!test()) delay(1) } }
    private fun report(flags: Int = 0x18, battery: Int = 88) = Protocol.duss(6, 2, 0, 0x3f, 0x90,
        ByteArray(19).apply { this[1] = flags.toByte(); this[18] = battery.toByte() }, 7)
    private fun version() = ByteArray(26).apply { this[22] = 4; this[23] = 3; this[24] = 2; this[25] = 1 }
    private fun activation(value: Int) = ByteArray(10).apply { this[1] = value.toByte() }

    @Test fun reportAdmissionIsExactAndUnknownBatteryRemainsUnknown() {
        val frame = Protocol.frames(report()).single()
        assertEquals(GamepadReport(true, true, 88, 0x18, 0, 0, 0, 0, 0), GamepadProtocol.report(frame))
        for (bad in listOf(frame.copy(valid = false), frame.copy(sender = 9), frame.copy(receiver = 6),
            frame.copy(attr = 0x40), frame.copy(set = 0), frame.copy(id = 0x91), frame.copy(payload = ByteArray(18)), frame.copy(payload = ByteArray(20))))
            assertNull(GamepadProtocol.report(bad))
        assertNull(GamepadProtocol.report(Protocol.frames(report(battery = 255)).single())!!.batteryPercent)
    }

    @Test fun driveBodyDecodesSignedGimbalUnsignedChassisAndEveryKnownButton() {
        val bytes = byteArrayOf(0, 0xd8.toByte(), 0x94.toByte(), 6, 0x6c, 1, 0x85.toByte(), 0xff.toByte(), 0x4a, 5,
            0, 0, 0xc0.toByte(), 0xf0.toByte(), 0, 0, 0, 0, 59)
        val report = checkNotNull(GamepadProtocol.report(Protocol.frames(Protocol.duss(6, 2, 0, 0x3f, 0x90, bytes, 1)).single()))
        assertEquals(listOf(1684, 364, -123, 1354), listOf(report.lateral, report.forward, report.yaw, report.pitch))
        assertTrue(report.chassisActive && report.gimbalActive && report.mouseConnected && report.keyboardConnected)
        assertTrue(report.fire && report.mouseLeft && report.mouseRight && report.c1 && report.c2 && report.fn)
        assertFalse(report.copy(rawFlags = 0, buttons = 0).chassisActive)
        assertFalse(report.copy(rawFlags = 0, buttons = 0).gimbalActive)
        assertFalse(report.copy(buttons = 0).fire)
    }

    @Test fun reportNoticesRemainIndependentOfInputGroupsBatteryPercentAndEachOther() {
        for (bits in 0..7) {
            val decoded = checkNotNull(GamepadProtocol.report(Protocol.frames(report(0xd8 or bits, 88)).single()))
            assertEquals(bits and 4 != 0, GamepadWarning.NOT_CALIBRATED in decoded.warnings)
            assertEquals(bits and 2 != 0, GamepadWarning.NOT_CENTERED in decoded.warnings)
            assertEquals(bits and 1 != 0, GamepadWarning.LOW_BATTERY in decoded.warnings)
            assertTrue(decoded.chassisActive && decoded.gimbalActive && decoded.keyboardConnected && decoded.mouseConnected)
            assertEquals(88, decoded.batteryPercent)
        }
        assertTrue(checkNotNull(GamepadProtocol.report(Protocol.frames(report(0x38, 0)).single())).warnings.isEmpty(),
            "Neither a reserved bit nor a battery percentage invents a reported notice")
    }

    @Test fun exactSnapshotAndButtonEdgesPreserveBurstButRetireOldConnections(): Unit = runBlocking {
        var now = 0L; val connector = Connector(); val session = GamepadSession(connector, this, { now })
        val events = Channel<GamepadButtonEvent>(Channel.UNLIMITED)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { session.buttonEvents.collect { events.send(it) } }
        try {
            session.connect("one"); val wire = connector.opened.receive(); await { session.state.value.phase == GamepadPhase.CONNECTED }
            wire.incoming.send(report()); val initial = withTimeout(2000) { events.receive() }
            val down = report().let { original -> Protocol.frames(original).single().payload.copyOf().apply { this[13] = 0x80.toByte() } }
            wire.incoming.send(Protocol.duss(6, 2, 0, 0x3f, 0x90, down, 8) + report())
            val pressed = withTimeout(2000) { events.receive() }; val released = withTimeout(2000) { events.receive() }
            assertTrue(pressed.report.fire); assertFalse(released.report.fire)
            assertTrue(initial.number < pressed.number && pressed.number < released.number)
            assertEquals(initial.connection, released.connection); assertTrue(session.accepts(pressed))
            now = 251
            assertFalse(session.snapshot().reportFresh); assertFalse(session.accepts(pressed))
            session.disconnect(); now = 0
            assertFalse(session.accepts(released))
            session.connect("one"); connector.opened.receive(); await { session.state.value.phase == GamepadPhase.CONNECTED }
            assertNotEquals(initial.connection, session.snapshot().connection)
            assertFalse(session.accepts(initial)); assertNull(session.snapshot().report)
        } finally { collector.cancel(); session.close() }
    }

    @Test fun activationUsesTwoBitsAndValidatesLengthWithoutExportingIdentity() {
        assertEquals("01.02.03.04", GamepadProtocol.version(version()))
        assertEquals(false, GamepadProtocol.activated(activation(0)))
        assertEquals(true, GamepadProtocol.activated(activation(1)))
        assertNull(GamepadProtocol.activated(activation(2)))
        assertNull(GamepadProtocol.activated(activation(3)))
        assertFailsWith<IllegalArgumentException> { GamepadProtocol.version(ByteArray(25)) }
        assertFailsWith<IllegalArgumentException> { GamepadProtocol.activated(ByteArray(9)) }
        assertFailsWith<IllegalArgumentException> { GamepadProtocol.activated(activation(1).apply { this[9] = 1 }) }
        assertEquals(true, GamepadProtocol.activated(activation(1) + byteArrayOf(0)))
    }

    @Test fun streamHandlesSplitsCoalescingNoiseAndBadCrcBeforeGoodFrames() {
        val good = report(); val broken = good.copyOf().apply { this[14] = 5 }
        val stream = GamepadFrameStream()
        assertTrue(stream.add(byteArrayOf(1, 2, 0x55, 0, 0, 0) + good.take(3).toByteArray()).isEmpty())
        assertEquals(1, stream.add(good.drop(3).toByteArray()).size)
        assertEquals(2, stream.add(broken + ByteArray(5_000) { 0x55 } + good + good).size)
        val split = GamepadFrameStream()
        assertEquals(1, good.flatMap { split.add(byteArrayOf(it)) }.size)
    }

    @Test fun queriesUseIndependentNodeAndRealSequenceWithFragmentedReplies(): Unit = runBlocking {
        val connector = Connector(); val session = GamepadSession(connector, this)
        try {
            session.connect("one"); val wire = connector.opened.receive(); await { session.state.value.phase == GamepadPhase.CONNECTED }
            session.readFirmware(); val request = wire.request()
            assertEquals(listOf(2, 6, 0x40, 0, 1, 1), listOf(request.sender, request.receiver, request.attr, request.set, request.id, request.sequence))
            assertTrue(request.payload.isEmpty())
            val answer = Protocol.duss(6, 2, 0x80, 0, 1, version(), request.sequence)
            wire.incoming.send(answer.take(2).toByteArray()); wire.incoming.send(answer.drop(2).toByteArray())
            await { !session.state.value.querying }; assertEquals("01.02.03.04", session.state.value.firmware)
            session.readActivation(); val second = wire.request()
            assertEquals(2, second.sequence); assertEquals(0x32, second.id); assertContentEquals(byteArrayOf(0x11), second.payload)
            wire.reply(second, activation(1)); await { !session.state.value.querying }
            assertEquals(true, session.state.value.activated)
            assertTrue(session.state.value.activationRead)
        } finally { session.close() }
    }

    @Test fun wrongRouteAttrSequenceAndCrcDoNotCompleteQuery(): Unit = runBlocking {
        val connector = Connector(); val session = GamepadSession(connector, this)
        try {
            session.connect("one"); val wire = connector.opened.receive(); await { session.state.value.phase == GamepadPhase.CONNECTED }
            session.readFirmware(); val request = wire.request()
            wire.reply(request, version(), sender = 9); wire.reply(request, version(), receiver = 9)
            wire.reply(request, version(), attr = 0); wire.reply(request, version(), sequence = request.sequence + 1)
            wire.incoming.send(Protocol.duss(6, 2, 0x80, 0, 1, version(), request.sequence).apply { this[15] = 9 })
            yield(); assertNull(session.state.value.firmware); assertTrue(session.state.value.querying)
            session.readActivation(); assertTrue(wire.outgoing.tryReceive().isFailure, "Concurrent queries must not queue")
            wire.reply(request, version()); await { !session.state.value.querying }; assertNotNull(session.state.value.firmware)
        } finally { session.close() }
    }

    @Test fun rejectedMalformedAndTimeoutRemainRetryableWithoutAutomaticResend(): Unit = runBlocking {
        val connector = Connector(); val session = GamepadSession(connector, this, requestTimeoutMillis = 40)
        try {
            session.connect("one"); val wire = connector.opened.receive(); await { session.state.value.phase == GamepadPhase.CONNECTED }
            session.readFirmware(); wire.reply(wire.request(), byteArrayOf(2)); await { !session.state.value.querying }
            assertEquals(GamepadProblem.REJECTED, session.state.value.problem)
            session.readFirmware(); wire.reply(wire.request(), byteArrayOf(0)); await { !session.state.value.querying }
            assertEquals(GamepadProblem.INVALID_RESPONSE, session.state.value.problem)
            session.readFirmware(); val old = wire.request(); await { !session.state.value.querying }
            assertEquals(GamepadProblem.TIMEOUT, session.state.value.problem); assertTrue(wire.outgoing.tryReceive().isFailure)
            session.readFirmware(); val current = wire.request(); wire.reply(old, version()); yield(); assertNull(session.state.value.firmware)
            wire.reply(current, version()); await { !session.state.value.querying }; assertNotNull(session.state.value.firmware)
        } finally { session.close() }
    }

    @Test fun reportAgeAndInvalidPacketsNeverFabricateFreshData(): Unit = runBlocking {
        var now = 0L; val connector = Connector(); val session = GamepadSession(connector, this, { now })
        try {
            session.connect("one"); val wire = connector.opened.receive(); await { session.state.value.phase == GamepadPhase.CONNECTED }
            wire.incoming.send(report()); await { session.state.value.report != null }; assertTrue(session.state.value.reportFresh)
            now = 251; session.updateAge(); assertFalse(session.state.value.reportFresh)
            wire.incoming.send(report().apply { this[11] = 3 }); yield(); assertFalse(session.state.value.reportFresh)
            wire.incoming.send(report()); await { session.state.value.reportFresh }
        } finally { session.close() }
    }

    @Test fun disconnectClearsStateAndClosesPendingRequests(): Unit = runBlocking {
        val connector = Connector(); val session = GamepadSession(connector, this)
        try {
            session.connect("one"); val wire = connector.opened.receive(); await { session.state.value.phase == GamepadPhase.CONNECTED }
            wire.incoming.send(report()); await { session.state.value.report != null }
            session.readFirmware(); wire.request(); session.disconnect()
            assertEquals(1, wire.closes); assertNull(session.state.value.report); assertFalse(session.state.value.querying)
            session.connect("one"); val second = connector.opened.receive(); await { session.state.value.phase == GamepadPhase.CONNECTED }
            assertNull(session.state.value.firmware); assertNull(session.state.value.report)
            session.readActivation(); val request = second.request(); second.reply(request, activation(2))
            await { !session.state.value.querying }; assertTrue(session.state.value.activationRead); assertNull(session.state.value.activated)
        } finally { session.close() }
    }

    @Test fun permissionFailureMissingAndUnsupportedDoNotOpenFakeLinks(): Unit = runBlocking {
        val connector = Connector(); val session = GamepadSession(connector, this)
        try {
            session.connect("missing"); assertEquals(GamepadProblem.NOT_FOUND, session.state.value.problem); assertEquals(0, connector.opens)
            connector.open = { throw GamepadPermissionDenied() }; session.connect("one")
            await { session.state.value.problem != null }; assertEquals(GamepadProblem.PERMISSION_DENIED, session.state.value.problem)
            connector.available = false; session.connect("one"); assertEquals(GamepadPhase.DISCONNECTED, session.state.value.phase)
            assertEquals(1, connector.opens)
        } finally { session.close() }
    }

    @Test fun lateOpenIsClosedAndCannotResurrectRetiredOrFinalSession(): Unit = runBlocking {
        val connector = Connector(); val gate = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>(); val late = Wire()
        connector.open = { withContext(NonCancellable) { entered.complete(Unit); gate.await() }; late }
        val session = GamepadSession(connector, this)
        session.connect("one"); entered.await(); session.disconnect(); gate.complete(Unit)
        await { late.closes >= 1 }; assertEquals(GamepadPhase.DISCONNECTED, session.state.value.phase)
        session.close(); session.connect("one"); session.readFirmware(); assertEquals(1, connector.opens)
    }

    @Test fun backgroundClosesImmediatelyAndReturningDoesNotReconnect(): Unit = runBlocking {
        val connector = Connector(); val session = GamepadSession(connector, this)
        try {
            session.connect("one"); val wire = connector.opened.receive(); await { session.state.value.phase == GamepadPhase.CONNECTED }
            session.setForeground(false); assertEquals(1, wire.closes); assertEquals(GamepadPhase.DISCONNECTED, session.state.value.phase)
            session.connect("one"); assertEquals(1, connector.opens)
            session.setForeground(true); yield(); assertEquals(1, connector.opens)
        } finally { session.close() }
    }

    @Test fun eofClearsReadValuesAndReportsInterruptedConnection(): Unit = runBlocking {
        val connector = Connector(); val session = GamepadSession(connector, this)
        try {
            session.connect("one"); val wire = connector.opened.receive(); await { session.state.value.phase == GamepadPhase.CONNECTED }
            wire.incoming.send(report()); await { session.state.value.report != null }; wire.incoming.close()
            await { session.state.value.phase == GamepadPhase.DISCONNECTED }
            assertEquals(GamepadProblem.LINK_LOST, session.state.value.problem); assertNull(session.state.value.report)
        } finally { session.close() }
    }
}
