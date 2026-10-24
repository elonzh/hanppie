package cn.elonzh.hanppie.ui.speech

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.i18n.uiText
import kotlinx.coroutines.flow.MutableStateFlow

internal class AndroidSpeechInput(context: Context) : SpeechInput {
    private val capture = AndroidSpeechCapture(context)
    private val input = SpeechRecognitionInput(capture)
    override val state = input.state
    val selectedService get() = capture.selectedService
    fun services() = capture.services()
    fun selectService(id: String) {
        require(services().any { it.first == id })
        input.cancel(); capture.selectService(id); input.dismissError()
    }
    fun loadService(id: String?) = capture.loadService(id)
    fun permissionDenied() = input.reject(uiText(Res.string.microphone_permission_denied_you_can_still_type))
    override fun start() = input.start()
    override fun finish() = input.finish()
    override fun cancel() = input.cancel()
    override fun consumeResult() = input.consumeResult()
    override fun dismissError() = input.dismissError()
    override fun close() = input.close()
}

/** Android owns service selection, permission checks, the recognizer and its native timeout. */
private class AndroidSpeechCapture(context: Context) : SpeechCapture {
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var listener: SpeechCaptureListener? = null
    private val timeout = Runnable { listener?.failed(uiText(Res.string.speech_recognition_timed_out_try_again)) }
    private var preferredService: String? = null
    val selectedService = MutableStateFlow(resolveService(null))
    fun services(): List<Pair<String, String>> = app.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
        .filter { it.serviceInfo.exported && it.serviceInfo.enabled }
        .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name).flattenToString() to it.loadLabel(app.packageManager).toString() }
    fun selectService(id: String) { preferredService = id; selectedService.value = id }

    fun loadService(id: String?) {
        require(id == null || services().any { it.first == id }) { "Configured speech service is unavailable: $id" }
        preferredService = id
        selectedService.value = resolveService(id)
    }

    private fun resolveService(preferred: String?): String? = recognitionServiceSelection(preferred,
        Settings.Secure.getString(app.contentResolver, "voice_recognition_service")
            ?.let(ComponentName::unflattenFromString)?.flattenToString(), services().map { it.first })

    override fun start(languageTag: String, listener: SpeechCaptureListener) {
        check(Looper.myLooper() == Looper.getMainLooper())
        this.listener = listener
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            listener.failed(uiText(Res.string.microphone_permission_denied_you_can_still_type)); return
        }
        val preferred = preferredService
        val local = preferred == null && Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(app)
        if (!local && !SpeechRecognizer.isRecognitionAvailable(app)) {
            listener.failed(uiText(Res.string.no_speech_recognizer_installed_use_keyboard_dictation_or_install)); return
        }
        val systemDefault = Settings.Secure.getString(app.contentResolver, "voice_recognition_service")
            ?.let(ComponentName::unflattenFromString)?.flattenToString()
        val component = recognitionServiceSelection(preferred, systemDefault, services().map { it.first })
        if (!local && component == null) { listener.failed(uiText(Res.string.select_an_available_recognition_service_in_settings)); return }
        val service = if (local && Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(app)
            else SpeechRecognizer.createSpeechRecognizer(app, requireNotNull(ComponentName.unflattenFromString(requireNotNull(component))))
        recognizer = service
        service.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() = listener.processing()
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(results: Bundle?) = listener.partial(recognized(results))
            override fun onResults(results: Bundle?) = listener.complete(recognized(results))
            override fun onError(error: Int) = listener.failed(when (error) {
                SpeechRecognizer.ERROR_CLIENT -> uiText(Res.string.speech_service_connection_failed_5_check_or_change_the)
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                    uiText(Res.string.hanppie_has_no_microphone_permission_allow_recording_in_app)
                else if (systemDefault == null) uiText(Res.string.recording_denied_9_no_default_recognizer_configure_it_in)
                else uiText(Res.string.recording_denied_9_although_hanppie_has_microphone_permission_check)
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> uiText(Res.string.could_not_understand_try_again)
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> uiText(Res.string.speech_recognition_network_unavailable)
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> uiText(Res.string.speech_service_busy_try_again_later)
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> uiText(Res.string.language_unsupported_or_speech_language_pack_missing)
                else -> uiText(Res.string.speech_recognition_failed_value, error)
            })
        })
        handler.postDelayed(timeout, 30_000)
        service.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        })
    }

    private fun recognized(results: Bundle?) = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
    override fun finish() { recognizer?.stopListening() }
    override fun cancel() {
        listener = null
        handler.removeCallbacks(timeout)
        recognizer?.cancel(); recognizer?.destroy(); recognizer = null
    }
}
