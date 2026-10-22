package cn.elonzh.hanppie.ui.robot.network

import cn.elonzh.hanppie.robot.network.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal class WifiFixture : RobotWifiService {
    var profile = RobotWifiProfile(0, "RMS1_Old", "old_pass")
    var reads = 0
    val writes = mutableListOf<RobotApCredentials>()
    var reboots = 0
    var readGate: CompletableDeferred<Unit>? = null
    var readError: Exception? = null
    var saveError: Exception? = null
    var rebootError: Exception? = null
    override suspend fun read(): RobotWifiProfile {
        reads++
        val captured = profile
        withContext(NonCancellable) { readGate?.await() }
        readError?.let { throw it }
        return captured
    }
    override suspend fun configure(credentials: RobotApCredentials, onConfirmed: (WifiConfirmedField) -> Unit) {
        writes += credentials
        onConfirmed(WifiConfirmedField.SSID)
        saveError?.let { throw it }
        profile = RobotWifiProfile(0, credentials.ssid, credentials.password)
        onConfirmed(WifiConfirmedField.PASSWORD)
    }
    override suspend fun reboot() { reboots++; rebootError?.let { throw it } }
}
