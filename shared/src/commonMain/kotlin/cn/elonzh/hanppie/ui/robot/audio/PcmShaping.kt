package cn.elonzh.hanppie.ui.robot.audio

/**
 * Plain PCM shaping shared by every platform importer.
 *
 * The DSP container needs 48 kHz mono signed 16-bit PCM split into 20 ms frames, while host files may
 * be stereo and sampled at any rate. Resampling is linear interpolation: the target is a 12 kbps Opus
 * stream for a small speaker, so the aliasing it adds stays below the codec's own loss, and a
 * windowed-sinc resampler would be infrastructure without a measured need here.
 */
internal fun downmixToMono(pcm: ByteArray, channels: Int): ByteArray {
    require(channels >= 1) { "声道数必须为正数" }
    require(pcm.size % (channels * 2) == 0) { "PCM 长度与声道数不匹配" }
    if (channels == 1) return pcm
    val frames = pcm.size / (channels * 2)
    val mono = ByteArray(frames * 2)
    for (frame in 0 until frames) {
        var sum = 0
        for (channel in 0 until channels) sum += sample16(pcm, (frame * channels + channel) * 2)
        writeSample16(mono, frame * 2, sum / channels)
    }
    return mono
}

internal fun resampleMono16(pcm: ByteArray, sourceRate: Int, targetRate: Int): ByteArray {
    require(sourceRate > 0 && targetRate > 0) { "采样率必须为正数" }
    require(pcm.size % 2 == 0) { "PCM 必须由完整的 16 位样本组成" }
    val samples = pcm.size / 2
    if (samples == 0 || sourceRate == targetRate) return pcm
    val target = ((samples.toLong() * targetRate) / sourceRate).toInt().coerceAtLeast(1)
    val result = ByteArray(target * 2)
    val step = sourceRate.toDouble() / targetRate
    for (index in 0 until target) {
        val position = index * step
        val left = position.toInt().coerceAtMost(samples - 1)
        val right = (left + 1).coerceAtMost(samples - 1)
        val fraction = ((position - left) * 1024).toInt().coerceIn(0, 1024)
        val value = sample16(pcm, left * 2) * (1024 - fraction) / 1024 + sample16(pcm, right * 2) * fraction / 1024
        writeSample16(result, index * 2, value)
    }
    return result
}

/** Pads mono 16-bit PCM with silence so the last frame is complete. */
internal fun framePadded(pcm: ByteArray, frameBytes: Int): ByteArray {
    require(frameBytes > 0 && frameBytes % 2 == 0) { "帧长度必须为偶数字节" }
    require(pcm.isNotEmpty()) { "PCM 为空" }
    val remainder = pcm.size % frameBytes
    return if (remainder == 0) pcm else pcm + ByteArray(frameBytes - remainder)
}

internal fun sample16(pcm: ByteArray, index: Int): Int {
    val low = pcm[index].toInt() and 255
    val high = pcm[index + 1].toInt()
    return (high shl 8) or low
}

internal fun writeSample16(pcm: ByteArray, index: Int, value: Int) {
    val bounded = value.coerceIn(-32_768, 32_767)
    pcm[index] = (bounded and 255).toByte()
    pcm[index + 1] = ((bounded shr 8) and 255).toByte()
}
