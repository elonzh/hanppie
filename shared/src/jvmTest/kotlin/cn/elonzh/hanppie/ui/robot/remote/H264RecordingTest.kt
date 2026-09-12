package cn.elonzh.hanppie.ui.robot.remote

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class H264RecordingTest {
    @Test fun codecConfigIsKeptInTrackFormatAndRemovedFromSamples() {
        val sps = byteArrayOf(0, 0, 0, 1, 0x67, 1)
        val pps = byteArrayOf(0, 0, 1, 0x68, 2)
        val idr = byteArrayOf(0, 0, 0, 1, 0x65, 3, 4)
        val sample = sps + pps + idr

        val stripped = sample.withoutNalTypes(setOf(7, 8))

        assertContentEquals(idr, stripped)
        assertTrue(stripped.hasNalType(5))
        assertFalse(stripped.hasNalType(7))
    }
}
