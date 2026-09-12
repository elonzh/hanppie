package cn.elonzh.hanppie.ui.app

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.elonzh.hanppie.resources.Res
import cn.elonzh.hanppie.resources.no_application_can_open_this_file
import cn.elonzh.hanppie.robot.files.RobotFileEntry
import cn.elonzh.hanppie.ui.i18n.Localization
import cn.elonzh.hanppie.ui.i18n.tr
import cn.elonzh.hanppie.ui.scripts.EditorDocument
import cn.elonzh.hanppie.ui.scripts.suggestedScriptFileName
import cn.elonzh.hanppie.ui.settings.AppearanceController
import cn.elonzh.hanppie.ui.settings.AppearanceSettings
import cn.elonzh.hanppie.ui.settings.UiPreferences
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.cacheDir
import io.github.vinceglb.filekit.createDirectories
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openFilePicker
import io.github.vinceglb.filekit.dialogs.openFileSaver
import io.github.vinceglb.filekit.dialogs.openFileWithDefaultApplication
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readString
import io.github.vinceglb.filekit.sink
import io.github.vinceglb.filekit.source
import io.github.vinceglb.filekit.writeString
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.asInputStream
import kotlinx.io.asOutputStream
import kotlinx.io.buffered

private val workbenchLogger = KotlinLogging.logger {}

internal class WorkbenchViewModel(
    val model: ConsoleModel,
    private val storage: WorkbenchStorage,
    systemLanguage: String,
    private val applyPlatformPreferences: (UiPreferences) -> Unit = {},
    private val pausePlatformResources: () -> Unit = {},
    private val releasePlatformResources: () -> Unit = {},
) : ViewModel() {
    val document: MutableState<EditorDocument> = mutableStateOf(EditorDocument())
    var fileError by mutableStateOf<String?>(null)
        private set
    val appearance = AppearanceController { encoded ->
        viewModelScope.launch {
            persistUi("appearance") { storage.settings.saveAppearance(AppearanceSettings.decode(encoded)) }
        }
    }

    private val closed = AtomicBoolean(false)
    private var transition: Job? = null
    private var fileAction: Job? = null
    private var foreground = true
    private var systemLanguage = systemLanguage
    private val persistLanguage: (String) -> Unit = { language ->
        viewModelScope.launch { persistUi("language") { storage.settings.saveLanguage(language) } }
    }

    init {
        Localization.initialize(systemLanguage, null, persistLanguage)
        viewModelScope.launch {
            try {
                val preferences = storage.settings.loadUi()
                Localization.initialize(this@WorkbenchViewModel.systemLanguage, preferences.language, persistLanguage)
                appearance.load(preferences.appearance)
                applyPlatformPreferences(preferences)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                workbenchLogger.error(error) { "Could not load UI preferences" }
                fileError = error.message ?: error.javaClass.simpleName
            }
        }
    }

    fun updateSystemLanguage(language: String) {
        if (language == systemLanguage) return
        systemLanguage = language
        Localization.initialize(language, Localization.choice, persistLanguage)
    }

    fun updateFileError(message: String?) {
        fileError = message
    }

    fun saveSpeechService(service: String) {
        viewModelScope.launch { persistUi("speech service") { storage.settings.saveSpeechService(service) } }
    }

    fun importScript() = documentOperation("import script") {
        val file = FileKit.openFilePicker(FileKitType.File("py")) ?: return@documentOperation
        document.value = EditorDocument(source = file.readString(), path = file.name)
    }

    fun exportScript() = documentOperation("export script") {
        val file = FileKit.openFileSaver(
            suggestedName = suggestedScriptFileName(document.value.displayName),
            defaultExtension = "py",
            allowedExtensions = setOf("py"),
        ) ?: return@documentOperation
        file.writeString(document.value.source)
    }

    fun uploadRobotFile(directory: String) = launchFileAction("select robot upload") {
        val file = FileKit.openFilePicker(FileKitType.File()) ?: return@launchFileAction
        model.robotFiles.upload(directory, file.name) {
            file.source().buffered().asInputStream()
        }
    }

    fun downloadRobotFile(entry: RobotFileEntry) = launchFileAction("select robot download") {
        val file = FileKit.openFileSaver(
            suggestedName = entry.name,
            defaultExtension = entry.name.substringAfterLast('.', "").ifBlank { null },
        ) ?: return@launchFileAction
        model.robotFiles.download(entry) {
            file.sink().buffered().asOutputStream()
        }
    }

    fun openRobotFile(entry: RobotFileEntry) = launchFileAction("open robot file") {
        val directory = PlatformFile(FileKit.cacheDir, "robot-files").apply { createDirectories() }
        val extension = entry.name.substringAfterLast('.', "").takeIf {
            it.isNotBlank() && it.length <= 12 && it.all(Char::isLetterOrDigit)
        }
        val temporary = PlatformFile(directory, "${UUID.randomUUID()}${extension?.let { ".$it" }.orEmpty()}")
        model.robotFiles.open(entry, { temporary.sink().buffered().asOutputStream() }) {
            viewModelScope.launch {
                try {
                    FileKit.openFileWithDefaultApplication(temporary)
                    fileError = null
                } catch (error: Exception) {
                    workbenchLogger.warn(error) { "No application could open ${entry.name}" }
                    fileError = tr(Res.string.no_application_can_open_this_file)
                }
            }
        }
    }

    fun pauseConnection() {
        foreground = false
        model.setForeground(false)
        model.speech.stop()
        val previous = transition
        transition = viewModelScope.launch {
            previous?.join()
            try {
                withContext(Dispatchers.IO) { model.pauseConnection() }
            } finally {
                pausePlatformResources()
            }
        }
    }

    fun resumeConnection() {
        foreground = true
        val pending = transition
        viewModelScope.launch {
            pending?.join()
            if (foreground) model.setForeground(true)
        }
    }

    fun shutdown() {
        if (!closed.compareAndSet(false, true)) return
        viewModelScope.cancel()
        transition?.cancel()
        model.close()
        releasePlatformResources()
        storage.close()
    }

    override fun onCleared() {
        shutdown()
    }

    private fun documentOperation(name: String, action: suspend () -> Unit) {
        if (document.value.busy || fileAction?.isActive == true) return
        document.value = document.value.copy(busy = true)
        fileAction = viewModelScope.launch {
            try {
                action()
                fileError = null
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                workbenchLogger.error(error) { "Could not $name" }
                fileError = error.message ?: error.javaClass.simpleName
            } finally {
                document.value = document.value.copy(busy = false)
            }
        }
    }

    private fun launchFileAction(name: String, action: suspend () -> Unit) {
        if (fileAction?.isActive == true) return
        fileAction = viewModelScope.launch {
            try {
                action()
                fileError = null
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                workbenchLogger.error(error) { "Could not $name" }
                fileError = error.message ?: error.javaClass.simpleName
            }
        }
    }

    private suspend fun persistUi(name: String, action: suspend () -> Unit) {
        try {
            action()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            workbenchLogger.error(error) { "Could not persist $name preference" }
            fileError = error.message ?: error.javaClass.simpleName
        }
    }
}
