@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package cn.elonzh.hanppie.ui.platform

import cn.elonzh.hanppie.robot.lab.mobile.*
import kotlinx.cinterop.useContents
import platform.CoreMotion.CMMotionManager
import platform.CoreMotion.CMAttitudeReferenceFrameXArbitraryZVertical
import platform.Foundation.NSProcessInfo

/** Polling avoids queueing sensor callbacks behind a stopped connection. No location/north request. */
internal class IosMobileSensors : MobileSensorSource {
    private val motion = CMMotionManager()
    override val availableMask get() =
        (if (motion.accelerometerAvailable) 1 else 0) or
        (if (motion.deviceMotionAvailable) 2 else 0) or
        (if (motion.gyroAvailable) 4 else 0)
    private var mask = 0
    private var startedAt = Double.POSITIVE_INFINITY

    override fun start(mask: Int) {
        stop()
        startedAt = NSProcessInfo.processInfo.systemUptime
        this.mask = mask
        if (mask and 1 != 0) { motion.accelerometerUpdateInterval = 0.1; motion.startAccelerometerUpdates() }
        if (mask and 2 != 0) { motion.deviceMotionUpdateInterval = 0.1; motion.startDeviceMotionUpdatesUsingReferenceFrame(CMAttitudeReferenceFrameXArbitraryZVertical) }
        if (mask and 4 != 0) { motion.gyroUpdateInterval = 0.1; motion.startGyroUpdates() }
    }

    override fun read(): List<MobileSensorReading> = buildList {
        val now = NSProcessInfo.processInfo.systemUptime
        fun append(kind: MobileSensor, stamp: Double, x: Double, y: Double, z: Double) {
            if (stamp >= startedAt) add(MobileSensorReading(kind, x.toFloat(), y.toFloat(), z.toFloat(),
                (stamp * 1_000_000_000).toLong(), ((now-stamp)*1000).toLong()))
        }
        if (mask and 1 != 0) motion.accelerometerData?.let { sample -> sample.acceleration.useContents {
            append(MobileSensor.ACCELERATION, sample.timestamp, x, y, z)
        } }
        if (mask and 2 != 0) motion.deviceMotion?.let { sample -> sample.attitude.quaternion.useContents {
            mobileAttitude(x, y, z, w)?.let { point ->
                append(MobileSensor.ATTITUDE, sample.timestamp, point.first.toDouble(), point.second.toDouble(), point.third.toDouble())
            }
        } }
        if (mask and 4 != 0) motion.gyroData?.let { sample -> sample.rotationRate.useContents {
            append(MobileSensor.ROTATION_RATE, sample.timestamp, x, y, z)
        } }
    }

    override fun stop() {
        mask = 0; startedAt = Double.POSITIVE_INFINITY
        motion.stopAccelerometerUpdates(); motion.stopDeviceMotionUpdates(); motion.stopGyroUpdates()
    }
}
