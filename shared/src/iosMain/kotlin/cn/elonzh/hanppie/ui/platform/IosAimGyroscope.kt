@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package cn.elonzh.hanppie.ui.platform

import cn.elonzh.hanppie.ui.robot.remote.input.AimGyroscope
import cn.elonzh.hanppie.ui.robot.remote.input.GyroscopeReading
import kotlinx.cinterop.useContents
import platform.CoreMotion.CMMotionManager
import platform.CoreMotion.CMAttitudeReferenceFrameXArbitraryZVertical
import platform.Foundation.NSProcessInfo
import platform.UIKit.*

/** DeviceMotion provides bias-corrected rotationRate; raw gyroData is reserved for Lab. */
internal class IosAimGyroscope(private val orientation: () -> UIInterfaceOrientation) : AimGyroscope {
    private val motion = CMMotionManager()
    private var startedAt = Double.POSITIVE_INFINITY
    override val available get() = motion.deviceMotionAvailable
    override fun start() {
        stop(); startedAt = NSProcessInfo.processInfo.systemUptime
        motion.deviceMotionUpdateInterval = 0.02
        motion.startDeviceMotionUpdatesUsingReferenceFrame(CMAttitudeReferenceFrameXArbitraryZVertical)
    }
    override fun read(): GyroscopeReading? {
        val value = motion.deviceMotion ?: return null
        if (value.timestamp < startedAt) return null
        val rotation = when (orientation()) {
            UIInterfaceOrientationLandscapeLeft -> 1
            UIInterfaceOrientationLandscapeRight -> 3
            UIInterfaceOrientationPortrait -> 0
            UIInterfaceOrientationPortraitUpsideDown -> 2
            else -> return null
        }
        return value.rotationRate.useContents {
            GyroscopeReading(x, y, z, (value.timestamp * 1_000_000_000).toLong(),
                ((NSProcessInfo.processInfo.systemUptime - value.timestamp) * 1000).toLong(), rotation)
        }
    }
    override fun stop() { startedAt = Double.POSITIVE_INFINITY; motion.stopDeviceMotionUpdates() }
}
