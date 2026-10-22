package cn.elonzh.hanppie.robot.media

import cn.elonzh.hanppie.robot.protocol.put16
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/** The camera uses local calendar fields, without an offset or timezone identifier. */
data class CameraDate(val year: Int, val month: Int, val day: Int, val hour: Int, val minute: Int, val second: Int) {
    private val calendar = LocalDateTime(year, month, day, hour, minute, second)
    init { require(year in 1..9999) }

    /** UTC is only a common scale for comparing two wall clocks, not the camera's timezone. */
    internal val wallSeconds: Long get() = calendar.toInstant(TimeZone.UTC).epochSeconds

    internal fun payload(): ByteArray = byteArrayOf(0, 0, month.toByte(), day.toByte(), hour.toByte(), minute.toByte(), second.toByte(), 0, 0)
        .apply { put16(0, year) }

    companion object {
        internal fun localNow(): CameraDate = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).let {
            CameraDate(it.year, it.month.number, it.day, it.hour, it.minute, it.second)
        }
    }
}

enum class AntiFlicker(val wireValue: Int) {
    AUTO(0), HZ_60(1), HZ_50(2);
    companion object { fun fromWire(value: Int) = entries.firstOrNull { it.wireValue == value } }
}
