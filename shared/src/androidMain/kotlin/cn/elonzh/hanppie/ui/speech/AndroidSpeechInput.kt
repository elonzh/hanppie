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
import cn.elonzh.hanppie.ui.i18n.Localization
import cn.elonzh.hanppie.ui.i18n.tr
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** All platform calls and listeners stay on the main thread; each recording gets a fresh generation. */
internal class AndroidSpeechInput(context: Context) : SpeechInput {
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var generation = 0L
    private var closed = false
    private val timeout = Runnable { fail(tr(Res.string.speech_recognition_timed_out_try_again)) }
    override val state = MutableStateFlow(SpeechInputState())
    private var preferredService: String? = null
    val selectedService = MutableStateFlow(resolveService(null))
    fun services(): List<Pair<String, String>> = app.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
        .filter { it.serviceInfo.exported && it.serviceInfo.enabled }
        .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name).flattenToString() to it.loadLabel(app.packageManager).toString() }
    fun selectService(id: String) {
        require(services().any { it.first == id })
        cancel()
        preferredService = id
        selectedService.value = id
        state.update { it.copy(error = null) }
    }

    fun loadService(id: String?) {
        require(id == null || services().any { it.first == id }) { "Configured speech service is unavailable: $id" }
        preferredService = id
        selectedService.value = resolveService(id)
    }

    private fun resolveService(preferred: String?): String? = recognitionServiceSelection(preferred,
        Settings.Secure.getString(app.contentResolver, "voice_recognition_service")
            ?.let(ComponentName::unflattenFromString)?.flattenToString(), services().map { it.first })

    override fun start() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed || state.value.active) return
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionDenied(); return
        }
        val preferred = preferredService
        val local = preferred == null && Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(app)
        if (!local && !SpeechRecognizer.isRecognitionAvailable(app)) {
            fail(tr(Res.string.no_speech_recognizer_installed_use_keyboard_dictation_or_install)); return
        }
        val systemDefault = Settings.Secure.getString(app.contentResolver, "voice_recognition_service")
            ?.let(ComponentName::unflattenFromString)?.flattenToString()
        val component = recognitionServiceSelection(preferred, systemDefault, services().map { it.first })
        if (!local && component == null) { fail(tr(Res.string.select_an_available_recognition_service_in_settings)); return }
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
                        error = if (text.isEmpty()) tr(Res.string.no_text_recognized_try_again) else null) }
                }
                override fun onError(error: Int) {
                    if (!current()) return
                    fail(when (error) {
                        SpeechRecognizer.ERROR_CLIENT -> tr(Res.string.speech_service_connection_failed_5_check_or_change_the)
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                            tr(Res.string.hanppie_has_no_microphone_permission_allow_recording_in_app)
                        else if (systemDefault == null) tr(Res.string.recording_denied_9_no_default_recognizer_configure_it_in)
                        else tr(Res.string.recording_denied_9_although_hanppie_has_microphone_permission_check)
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> tr(Res.string.could_not_understand_try_again)
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> tr(Res.string.speech_recognition_network_unavailable)
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> tr(Res.string.speech_service_busy_try_again_later)
                        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> tr(Res.string.language_unsupported_or_speech_language_pack_missing)
                        else -> tr(Res.string.speech_recognition_failed_value,error)
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
        } catch (_: Exception) { fail(tr(Res.string.could_not_start_speech_recognition_check_microphone_permissions_and)) }
    }

    private fun recognized(results: Bundle?) = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
    fun permissionDenied() = fail(tr(Res.string.microphone_permission_denied_you_can_still_type))
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
