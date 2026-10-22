package cn.elonzh.hanppie.robot.network

import cn.elonzh.hanppie.robot.protocol.*
import cn.elonzh.hanppie.robot.session.*
import kotlinx.coroutines.*
import kotlin.test.*

class SdrProtocolTest {
    @Test fun queryRequiresPositiveLinkModeAndDecodesNativeConfigurationOffsets(): Unit = runBlocking {
        var mode = 0
        var response = sdrResponse(SdrSettings(0, 1, 2544, 2, true)) + byteArrayOf(99)
        val ids = mutableListOf<String>()
        val service = SdrCommands { node, set, id, p ->
            assertTrue(p.isEmpty()); ids += "$node/$set/$id"
            if (node == 7) byteArrayOf(0, mode.toByte()) else response
        }
        for (other in listOf(0, 2, 4, 255)) { mode = other; assertNull(service.read()) }
        assertEquals(List(4) { "7/7/57" }, ids)
        mode = 1
        assertEquals(SdrSettings(0, 1, 2544, 2, true), service.read())
        assertEquals(listOf("7/7/57", "14/9/33"), ids.takeLast(2))
        response = byteArrayOf(0, -1, -1, -1, -1, -1, 2)
        assertEquals(SdrSettings(255, 255, 65535, 255, true), service.read())
        response = ByteArray(6)
        assertEquals(RequestProblem.INVALID_RESPONSE, assertFailsWith<RobotRequestException> { service.read() }.problem)
    }

    @Test fun settersUseNativeThreeByteSelectorsAndFreshConfigurationGuards(): Unit = runBlocking {
        var mode = 1; var settings = SdrSettings(0, 0, 1011, 1, true)
        val writes = mutableListOf<String>(); var dispatched = 0
        val service = SdrCommands { node, set, id, p ->
            when {
                node == 7 -> byteArrayOf(0, mode.toByte())
                id == 0x21 -> sdrResponse(settings)
                else -> { assertEquals(14, node); assertEquals(9, set); assertEquals(0x0d, id); writes += p.hex(); byteArrayOf(0) }
            }
        }
        for (target in listOf(SdrChange.Point(1012), SdrChange.Band(SdrBand.GHZ_5_8),
            SdrChange.Selection(SdrSelection.AUTO), SdrChange.Selection(SdrSelection.MANUAL), SdrChange.Bandwidth(SdrBandwidth.MHZ_10))) {
            service.set(target) { dispatched++ }
        }
        assertEquals(listOf("01f403", "020200", "030100", "030000", "040100"), writes)
        assertEquals(5, dispatched)
        settings = settings.copy(dualBandSupported = false)
        assertFailsWith<IllegalStateException> { service.set(SdrChange.Band(SdrBand.GHZ_5_8)) { dispatched++ } }
        assertFailsWith<IllegalStateException> { service.set(SdrChange.Band(SdrBand.DUAL)) { dispatched++ } }
        assertFailsWith<IllegalStateException> { service.set(SdrChange.Point(1074)) { dispatched++ } }
        settings = settings.copy(selectionCode = 1)
        assertFailsWith<IllegalStateException> { service.set(SdrChange.Bandwidth(SdrBandwidth.MHZ_20)) { dispatched++ } }
        assertFailsWith<IllegalStateException> { service.set(SdrChange.Point(1012)) { dispatched++ } }
        mode = 0
        assertFailsWith<IllegalStateException> { service.set(SdrChange.Selection(SdrSelection.MANUAL)) { dispatched++ } }
        assertEquals(5, dispatched); assertEquals(5, writes.size)
    }

    @Test fun requestCancellationDoesNotQueueOrContinueToAWrite(): Unit = runBlocking {
        val gate = CompletableDeferred<Unit>(); var writes = 0
        val service = SdrCommands { node, _, _, _ ->
            if (node == 7) { gate.await(); byteArrayOf(0, 1) } else { writes++; byteArrayOf(0) }
        }
        val first = launch(start = CoroutineStart.UNDISPATCHED) { service.read() }
        assertFailsWith<IllegalStateException> { service.set(SdrChange.Selection(SdrSelection.AUTO)) {} }
        first.cancelAndJoin(); assertEquals(0, writes)
        service.close()
        assertEquals(RequestProblem.DISCONNECTED, assertFailsWith<RobotRequestException> { service.read() }.problem)
    }

    @Test fun frequencyConversionAndManualBoundsRetainUnknownsAndFollowFixedClientRanges() {
        assertEquals("2400.5", sdrFrequencyMHz(1001)); assertEquals("5150.5", sdrFrequencyMHz(2084))
        assertEquals("5470.5", sdrFrequencyMHz(2284)); assertEquals("5839.5", sdrFrequencyMHz(2653))
        assertNull(sdrFrequencyMHz(1201)); assertNull(sdrFrequencyMHz(65535))
        assertEquals(1011..1073, SdrSettings(0, 0, 1011, 1, false).manualPoints)
        assertEquals(1006..1078, SdrSettings(0, 1, 1011, 1, false).manualPoints)
        assertEquals(2549..2653, SdrSettings(0, 0, 2549, 2, true).manualPoints)
        assertEquals(2544..2658, SdrSettings(0, 1, 2549, 2, true).manualPoints)
        assertNull(SdrSettings(0, 0, 2549, 2, false).manualPoints)
        assertNull(SdrSettings(255, 0, 2549, 2, true).manualPoints)
        assertNull(SdrSettings(0, 255, 2549, 2, true).manualPoints)
    }
}

internal fun sdrResponse(settings: SdrSettings): ByteArray = ByteArray(7).apply {
    this[1] = settings.selectionCode.toByte(); this[2] = settings.bandwidthCode.toByte(); put16(3, settings.point)
    this[5] = settings.bandCode.toByte(); this[6] = if (settings.dualBandSupported) 1 else 0
}
