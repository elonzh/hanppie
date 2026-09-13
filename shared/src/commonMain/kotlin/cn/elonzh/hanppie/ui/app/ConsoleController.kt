package cn.elonzh.hanppie.ui.app

import cn.elonzh.hanppie.ui.chat.ChatAgent
import cn.elonzh.hanppie.ui.i18n.UiText
import cn.elonzh.hanppie.ui.robot.files.RobotFilesController
import cn.elonzh.hanppie.ui.scripts.ScriptLibrary
import cn.elonzh.hanppie.ui.settings.ControlSettings
import cn.elonzh.hanppie.ui.settings.ModelSettings
import cn.elonzh.hanppie.ui.settings.RobotLedColor
import cn.elonzh.hanppie.ui.speech.ReplySpeaker
import cn.elonzh.hanppie.ui.speech.SpeechEngine
import cn.elonzh.hanppie.ui.speech.SpeechInput
import kotlinx.coroutines.flow.MutableStateFlow

/** Common UI boundary; platform composition roots provide the concrete robot transport. */
internal interface ConsoleController : AutoCloseable {
    val speech: SpeechEngine
    val voiceInput: SpeechInput
    val replySpeaker: ReplySpeaker
    var voicePageActive: Boolean
    val isForeground: Boolean
    val state: MutableStateFlow<ConsoleState>
    val modelSettings: MutableStateFlow<ModelSettings>
    val autoReadReplies: MutableStateFlow<Boolean>
    val controlSettings: MutableStateFlow<ControlSettings>
    val settingsBusy: MutableStateFlow<Boolean>
    val settingsMessage: MutableStateFlow<UiText?>
    val scriptLibrary: ScriptLibrary
    val robotFiles: RobotFilesController
    val chat: ChatAgent
    val foregroundState: MutableStateFlow<Boolean>
    val remoteEnabled: MutableStateFlow<Boolean>
    val remoteInput: MutableStateFlow<List<Double>>
    val cameraYaw: MutableStateFlow<Double?>
    val gelSelected: MutableStateFlow<Boolean>
    val driveGear: MutableStateFlow<Int>
    val talking: MutableStateFlow<Boolean>
    val microphoneReady: MutableStateFlow<Boolean>
    val talkBusy: MutableStateFlow<Boolean>
    var videoSink: ((ByteArray) -> Unit)?
    var audioSink: ((ByteArray) -> Unit)?

    fun saveSettings()
    fun restoreDefaultSettings()
    fun shiftGear(delta: Int)
    fun selectGear(gear: Int)
    fun switchAmmo()
    fun fireSelected()
    fun enableRemote()
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
    fun connect(ip: String, appId: String)
    fun disconnect()
    fun runScript(source: String, title: String)
    fun stop()
    fun clearLogs()
    suspend fun pauseConnection()
}
