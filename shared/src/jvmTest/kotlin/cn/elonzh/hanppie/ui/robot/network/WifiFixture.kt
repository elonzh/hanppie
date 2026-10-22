package cn.elonzh.hanppie.ui.robot.network

import cn.elonzh.hanppie.robot.network.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal class WifiFixture : RobotWifiService {
    override val signals = kotlinx.coroutines.flow.MutableStateFlow(WifiSignals())
    var radio = WifiRadioSettings(0, 0, 1, listOf(1, 6, 11, 36, 149))
    val reportWrites = mutableListOf<Boolean>()
    val channelWrites = mutableListOf<Int>()
    val modeWrites = mutableListOf<WifiChannelMode>()
    var radioError: Exception? = null
    var radioWriteError: Exception? = null
    var radioBeforeWriteError: Exception? = null
    var applyRadioWrites = true
    var reportGate: CompletableDeferred<Unit>? = null
    var reportError: Exception? = null
    override suspend fun readRadio(): WifiRadioSettings { radioError?.let { throw it }; return radio }
    override suspend fun setChannelMode(mode: WifiChannelMode, onDispatch: () -> Unit) {
        radioBeforeWriteError?.let { throw it }; onDispatch(); modeWrites += mode
        radioWriteError?.let { throw it }; if (applyRadioWrites) radio = radio.copy(selectionCode = mode.code)
    }
    override suspend fun setChannel(channel: Int, onDispatch: () -> Unit) {
        radioBeforeWriteError?.let { throw it }; onDispatch(); channelWrites += channel
        radioWriteError?.let { throw it }; if (applyRadioWrites) radio = radio.copy(currentChannel = channel)
    }
    override suspend fun setSignalReports(enabled: Boolean) {
        reportWrites += enabled
        if (enabled) withContext(NonCancellable) { reportGate?.await() }
        reportError?.let { throw it }
    }
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
