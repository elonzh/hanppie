package cn.elonzh.hanppie.ui.app

import cn.elonzh.hanppie.agent.provider.ModelCatalogState
import cn.elonzh.hanppie.agent.provider.ModelTestState
import cn.elonzh.hanppie.robot.lab.LabAudioClip
import cn.elonzh.hanppie.ui.chat.ChatAgent
import cn.elonzh.hanppie.ui.i18n.UiText
import cn.elonzh.hanppie.ui.robot.files.RobotFilesController
import cn.elonzh.hanppie.ui.scripts.ScriptAudioLibrary
import cn.elonzh.hanppie.ui.scripts.ScriptLibrary
import cn.elonzh.hanppie.ui.settings.ConnectionPreferences
import cn.elonzh.hanppie.ui.settings.ControlSettings
import cn.elonzh.hanppie.ui.settings.MediaSettings
import cn.elonzh.hanppie.ui.settings.ModelSettings
import cn.elonzh.hanppie.ui.settings.RobotLedColor
import cn.elonzh.hanppie.ui.speech.SpeechInput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

internal enum class AmmoType {
    INFRARED,
    GEL,
}

/** Common UI boundary; platform composition roots provide the concrete robot transport. */
internal interface ConsoleController : AutoCloseable {
    val voiceInput: SpeechInput
    var voicePageActive: Boolean
    val isForeground: Boolean
    val state: MutableStateFlow<ConsoleState>
    val modelSettings: MutableStateFlow<ModelSettings>
    val modelCatalogState: MutableStateFlow<ModelCatalogState>
    val modelTestState: MutableStateFlow<ModelTestState>
    val mediaSettings: MutableStateFlow<MediaSettings>
    val controlSettings: MutableStateFlow<ControlSettings>
    val connectionPreferences: MutableStateFlow<ConnectionPreferences>
    val settingsBusy: MutableStateFlow<Boolean>
    val settingsMessage: MutableStateFlow<UiText?>
    val scriptLibrary: ScriptLibrary
    val scriptAudio: ScriptAudioLibrary
    val robotFiles: RobotFilesController
    val chat: ChatAgent
    val foregroundState: MutableStateFlow<Boolean>
    val remoteEnabled: MutableStateFlow<Boolean>
    val remoteInput: MutableStateFlow<List<Double>>
    val cameraYaw: MutableStateFlow<Double?>
    val gelSelected: MutableStateFlow<Boolean>
    val firing: StateFlow<Boolean>
    val fireEvents: SharedFlow<AmmoType>
    val driveGear: MutableStateFlow<Int>
    val talking: MutableStateFlow<Boolean>
    val microphoneReady: MutableStateFlow<Boolean>
    val talkBusy: MutableStateFlow<Boolean>
    val videoFrames: cn.elonzh.hanppie.ui.robot.remote.VideoFrameCache
    var videoSink: ((ByteArray) -> Unit)?
    var audioSink: ((ByteArray) -> Unit)?

    fun saveSettings()
    fun loadModelCatalog()
    fun testModelSettings()
    fun restoreDefaultSettings()
    fun shiftGear(delta: Int)
    fun selectGear(gear: Int)
    fun switchAmmo()
    fun fireSelected()
    fun startFiring()
    fun stopFiring()
    fun enableRemote()
    fun recenterGimbal()
    fun haltRemote()
    fun leaveRemote()
    fun drive(x: Double, y: Double, z: Double, pitch: Double, yaw: Double)
    fun fire()
    fun fireGel()
    fun beginPushToTalk()
    fun endPushToTalk()
    fun startMedia(audio: Boolean)
    fun stopMedia()
    fun setRemoteLed(color: RobotLedColor?)
    fun setForeground(foreground: Boolean)
    fun log(message: String)
    fun discover()
    fun pairRouter(ssid: String, password: String)
    fun connect(ip: String, appId: String)
    fun disconnect()
    fun runScript(source: String, title: String, audio: List<LabAudioClip> = emptyList())
    fun stop()
    fun clearLogs()
    suspend fun pauseConnection()
}
