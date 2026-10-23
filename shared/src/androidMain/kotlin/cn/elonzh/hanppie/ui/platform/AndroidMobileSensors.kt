package cn.elonzh.hanppie.ui.platform

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import cn.elonzh.hanppie.robot.lab.mobile.*

/** Only native sampling; the connection owns subscriptions, rate limits and wire encoding. */
internal class AndroidMobileSensors(context: Context) : MobileSensorSource {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val sensors = mapOf(
        MobileSensor.ACCELERATION to manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
        MobileSensor.ATTITUDE to manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR),
        MobileSensor.ROTATION_RATE to manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE),
    )
    override val availableMask get() = sensors.entries.filter { it.value != null }.fold(0) { mask, item -> mask or item.key.bit }
    private val lock = Any()
    private var listener: SensorEventListener? = null
    private val latest = mutableMapOf<MobileSensor, MobileSensorReading>()

    override fun start(mask: Int) {
        stop()
        val current = object : SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            override fun onSensorChanged(event: SensorEvent) {
                val kind = sensors.entries.firstOrNull { it.value?.type == event.sensor.type }?.key ?: return
                val values = event.values
                if (values.size < 3) return
                val point = when (kind) {
                    // Android reports support force in m/s²; CoreMotion reports gravity/motion in g.
                    MobileSensor.ACCELERATION -> Triple(-values[0]/SensorManager.GRAVITY_EARTH,
                        -values[1]/SensorManager.GRAVITY_EARTH, -values[2]/SensorManager.GRAVITY_EARTH)
                    MobileSensor.ROTATION_RATE -> Triple(values[0], values[1], values[2])
                    MobileSensor.ATTITUDE -> {
                        val q = FloatArray(4)
                        SensorManager.getQuaternionFromVector(q, values)
                        mobileAttitude(q[1].toDouble(), q[2].toDouble(), q[3].toDouble(), q[0].toDouble()) ?: return
                    }
                }
                synchronized(lock) {
                    if (listener === this) latest[kind] = MobileSensorReading(kind, point.first, point.second, point.third, event.timestamp, 0)
                }
            }
        }
        synchronized(lock) { listener = current }
        try {
            sensors.forEach { (kind, sensor) ->
                if (mask and kind.bit != 0) check(sensor != null && manager.registerListener(current, sensor, 100_000)) {
                    "Motion sensor registration failed: $kind"
                }
            }
        } catch (error: Exception) { stop(); throw error }
    }

    override fun read(): List<MobileSensorReading> = synchronized(lock) {
        val now = SystemClock.elapsedRealtimeNanos()
        latest.values.map { it.copy(ageMillis = (now - it.stamp) / 1_000_000) }
    }

    override fun stop() {
        val previous = synchronized(lock) { val old = listener; listener = null; latest.clear(); old }
        if (previous != null) manager.unregisterListener(previous)
    }
}
