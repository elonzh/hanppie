package cn.elonzh.hanppie.ui.platform

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.view.WindowManager
import cn.elonzh.hanppie.ui.robot.remote.input.AimGyroscope
import cn.elonzh.hanppie.ui.robot.remote.input.GyroscopeReading

/** TYPE_GYROSCOPE is bias-compensated. Screen rotation is read with each native sample. */
internal class AndroidAimGyroscope(context: Context) : AimGyroscope {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val window = context.getSystemService(WindowManager::class.java)
    private val sensor = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    override val available get() = sensor != null
    private val lock = Any()
    private var listener: SensorEventListener? = null
    private var latest: GyroscopeReading? = null

    override fun start() {
        stop()
        val startedAt = SystemClock.elapsedRealtimeNanos()
        val current = object : SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            override fun onSensorChanged(event: SensorEvent) {
                if (event.values.size < 3 || event.timestamp < startedAt) return
                @Suppress("DEPRECATION") // Associated display, including API levels before Context.display.
                val rotation = window.defaultDisplay.rotation
                val value = GyroscopeReading(event.values[0].toDouble(), event.values[1].toDouble(),
                    event.values[2].toDouble(), event.timestamp, 0, rotation)
                synchronized(lock) { if (listener === this) latest = value }
            }
        }
        synchronized(lock) { listener = current }
        try { check(sensor != null && sensors.registerListener(current, sensor, 20_000)) }
        catch (error: Exception) { stop(); throw error }
    }

    override fun read(): GyroscopeReading? = synchronized(lock) {
        latest?.let { it.copy(ageMillis = (SystemClock.elapsedRealtimeNanos() - it.stamp) / 1_000_000) }
    }

    override fun stop() {
        val previous = synchronized(lock) { val old = listener; listener = null; latest = null; old }
        if (previous != null) sensors.unregisterListener(previous)
    }
}
