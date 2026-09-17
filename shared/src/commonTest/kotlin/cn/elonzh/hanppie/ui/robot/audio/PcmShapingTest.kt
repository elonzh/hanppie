package cn.elonzh.hanppie.ui.robot.audio

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PcmShapingTest {
    @Test fun monoInputPassesThroughAndStereoIsAveraged() {
        val mono = byteArrayOf(1, 0, 2, 0)
        assertContentEquals(mono, downmixToMono(mono, 1))
        val stereo = pcm16(1000, -1000, 200, 400)
        assertContentEquals(pcm16(0, 300), downmixToMono(stereo, 2))
        assertFailsWith<IllegalArgumentException> { downmixToMono(stereo, 3) }
        assertFailsWith<IllegalArgumentException> { downmixToMono(stereo, 0) }
    }

    @Test fun resamplingKeepsLengthAndEndpoints() {
        val source = pcm16(0, 1000, 2000, 3000)
        assertContentEquals(source, resampleMono16(source, 48_000, 48_000))
        val upsampled = resampleMono16(source, 24_000, 48_000)
        assertEquals(8, upsampled.size / 2)
        assertEquals(0, sample16(upsampled, 0))
        assertEquals(3000, sample16(upsampled, upsampled.size - 2))
        val downsampled = resampleMono16(source, 48_000, 16_000)
        assertEquals(1, downsampled.size / 2)
        assertEquals(0, sample16(downsampled, 0))
        assertFailsWith<IllegalArgumentException> { resampleMono16(source, 0, 48_000) }
    }

    @Test fun resamplingInterpolatesBetweenSamples() {
        // 44.1 kHz -> 48 kHz on a two-sample ramp: the second output sample lands 0.919 samples in.
        val upsampled = resampleMono16(pcm16(0, 441), 44_100, 48_000)
        assertEquals(2, upsampled.size / 2)
        assertEquals(404, sample16(upsampled, 2))
    }

    @Test fun framingPadsTheTailToAWholeFrame() {
        assertContentEquals(pcm16(7, 0), framePadded(pcm16(7), 4))
        assertContentEquals(pcm16(7, 8, 9, 0), framePadded(pcm16(7, 8, 9), 8))
        assertFailsWith<IllegalArgumentException> { framePadded(ByteArray(0), 4) }
        assertFailsWith<IllegalArgumentException> { framePadded(pcm16(1), 3) }
    }

    private fun pcm16(vararg samples: Int): ByteArray = ByteArray(samples.size * 2).also { pcm ->
        samples.forEachIndexed { index, value -> writeSample16(pcm, index * 2, value) }
    }
}
