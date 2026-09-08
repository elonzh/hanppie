package cn.elonzh.hanppie.desktop

import android.Manifest
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.RecognitionService
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

/** All platform calls and listeners stay on the main thread; each recording gets a fresh generation. */
internal class AndroidSpeechInput(context: Context) : SpeechInput {
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var generation = 0L
    private var closed = false
    private val timeout = Runnable { fail(tr("语音识别超时，请重试")) }
    override val state = MutableStateFlow(SpeechInputState())
    private val preferences = app.getSharedPreferences("speech-input", Context.MODE_PRIVATE)
    fun services(): List<Pair<String, String>> = app.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
        .filter { it.serviceInfo.exported && it.serviceInfo.enabled }
        .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name).flattenToString() to it.loadLabel(app.packageManager).toString() }
    fun selectService(id: String) {
        require(services().any { it.first == id })
        cancel(); preferences.edit().putString("component", id).apply()
        state.update { it.copy(error = null) }
    }

    fun selectedService(): String? = recognitionServiceSelection(preferences.getString("component", null),
        Settings.Secure.getString(app.contentResolver, "voice_recognition_service")
            ?.let(ComponentName::unflattenFromString)?.flattenToString(), services().map { it.first })

    override fun start() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed || state.value.active) return
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionDenied(); return
        }
        val preferred = preferences.getString("component", null)
        val local = preferred == null && Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(app)
        if (!local && !SpeechRecognizer.isRecognitionAvailable(app)) {
            fail(tr("系统未提供语音识别服务；可使用输入法语音输入或安装兼容的系统识别服务")); return
        }
        val systemDefault = Settings.Secure.getString(app.contentResolver, "voice_recognition_service")
            ?.let(ComponentName::unflattenFromString)?.flattenToString()
        val component = recognitionServiceSelection(preferred, systemDefault, services().map { it.first })
        if (!local && component == null) { fail(tr("未配置可用识别服务，请在对话设置中选择语音识别服务")); return }
        cancel()
        val token = generation
        state.update { it.copy(active = true, processing = false, partial = "", result = "", error = null) }
        try {
            val service = if (local && Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(app)
                else SpeechRecognizer.createSpeechRecognizer(app, requireNotNull(ComponentName.unflattenFromString(requireNotNull(component))))
            recognizer = service
            service.setRecognitionListener(object : RecognitionListener {
                private fun current() = !closed && generation == token && state.value.active
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { if (current()) state.update { it.copy(processing = true) } }
                override fun onEvent(eventType: Int, params: Bundle?) {}
                override fun onPartialResults(results: Bundle?) {
                    if (current()) state.update { it.copy(partial = recognized(results).take(12000)) }
                }
                override fun onResults(results: Bundle?) {
                    if (!current()) return
                    val text = recognized(results).trim().take(12000)
                    cancel()
                    state.update { it.copy(result = text, resultId = it.resultId + 1,
                        error = if (text.isEmpty()) tr("没有识别到文字，请重试") else null) }
                }
                override fun onError(error: Int) {
                    if (!current()) return
                    fail(when (error) {
                        SpeechRecognizer.ERROR_CLIENT -> tr("识别服务连接失败（5），请在对话设置中检查或更换语音识别服务")
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                            tr("憨皮的麦克风权限未授予，请在应用权限设置中允许录音")
                        else if (systemDefault == null) tr("识别服务拒绝录音（9）：系统默认识别服务未配置。请打开设置 → 语音服务 → 系统默认语音输入进行配置")
                        else tr("识别服务拒绝录音（9），憨皮已获麦克风权限。请在设置 → 语音服务中检查服务权限及系统默认语音输入")
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> tr("没有听清，请重试")
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> tr("系统语音识别网络不可用")
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> tr("系统语音服务正忙，请稍后重试")
                        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> tr("系统语音服务不支持当前语言或缺少语音包")
                        else -> tr("系统语音识别失败（{0}）",error)
                    })
                }
            })
            handler.postDelayed(timeout, 30_000)
            service.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Localization.languageTag)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            })
        } catch (_: Exception) { fail(tr("无法启动系统语音识别，请检查麦克风权限和语音服务")) }
    }

    private fun recognized(results: Bundle?) = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
    fun permissionDenied() = fail(tr("麦克风权限未授予；仍可直接输入文字"))
    private fun fail(message: String) { cancel(); state.update { it.copy(error = message) } }
    override fun finish() {
        if (!state.value.active || state.value.processing) return
        state.update { it.copy(processing = true) }
        recognizer?.stopListening()
    }
    override fun cancel() {
        generation++
        handler.removeCallbacks(timeout)
        recognizer?.cancel(); recognizer?.destroy(); recognizer = null
        state.update { it.copy(active = false, processing = false, partial = "") }
    }
    override fun consumeResult() { state.update { it.copy(result = "") } }
    override fun close() { closed = true; cancel() }
}
