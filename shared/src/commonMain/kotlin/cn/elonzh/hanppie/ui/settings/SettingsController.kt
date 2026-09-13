package cn.elonzh.hanppie.ui.settings

import cn.elonzh.hanppie.resources.Res
import cn.elonzh.hanppie.resources.default_settings_restored
import cn.elonzh.hanppie.resources.settings_load_failed
import cn.elonzh.hanppie.resources.settings_save_failed
import cn.elonzh.hanppie.resources.settings_saved
import cn.elonzh.hanppie.ui.i18n.UiText
import cn.elonzh.hanppie.ui.i18n.uiText
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource

private val settingsLogger = KotlinLogging.logger {}

/** Serializes blocking settings I/O so user actions are never dropped while a previous operation is running. */
@OptIn(ExperimentalAtomicApi::class)
internal class SettingsController(
    private val store: SettingsStore,
    private val runtimeDefaults: () -> ModelSettings,
    private val applyEnvironmentOverrides: (ModelSettings) -> ModelSettings,
) : AutoCloseable {
    val model = MutableStateFlow(runtimeDefaults())
    val autoRead = MutableStateFlow(false)
    val control = MutableStateFlow(ControlSettings())
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<UiText?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val closed = AtomicBoolean(false)
    private val pendingCommands = AtomicInt(0)

    init {
        scope.launch {
            for (command in commands) {
                try {
                    execute(command)
                } finally {
                    busy.value = pendingCommands.addAndFetch(-1) > 0
                }
            }
        }
        enqueue(Command.Load)
    }

    fun save() {
        val snapshot = SavedSettings(model.value, autoRead.value, control.value)
        enqueue(Command.Save(snapshot, Res.string.settings_saved))
    }

    fun restoreDefaults() {
        val persisted = SavedSettings()
        val runtime = persisted.copy(model = runtimeDefaults())
        enqueue(Command.Restore(runtime, persisted))
    }

    private fun enqueue(command: Command) {
        if (closed.load()) return
        pendingCommands.addAndFetch(1)
        busy.value = true
        message.value = null
        if (commands.trySend(command).isFailure) {
            busy.value = pendingCommands.addAndFetch(-1) > 0
        }
    }

    private suspend fun execute(command: Command) {
        when (command) {
            Command.Load -> try {
                val saved = store.load()
                apply(saved.copy(model = applyEnvironmentOverrides(saved.model)))
            } catch (error: Exception) {
                settingsLogger.error(error) { "Could not load settings" }
                message.value = uiText(Res.string.settings_load_failed)
            }
            is Command.Save -> persist(command.settings, command.success)
            is Command.Restore -> {
                apply(command.runtime)
                persist(command.persisted, Res.string.default_settings_restored)
            }
        }
    }

    private suspend fun persist(settings: SavedSettings, success: StringResource) {
        try {
            // Saving a blank key intentionally clears the previously stored credential.
            if (settings.model.apiKey.isNotBlank()) settings.model.validate()
            store.save(settings)
            message.value = uiText(success)
        } catch (error: Exception) {
            settingsLogger.error(error) { "Could not save settings" }
            message.value = uiText(Res.string.settings_save_failed)
        }
    }

    private fun apply(settings: SavedSettings) {
        model.value = settings.model
        autoRead.value = settings.autoRead
        control.value = settings.control
    }

    override fun close() {
        if (!closed.compareAndSet(expectedValue = false, newValue = true)) return
        commands.close()
        scope.cancel()
    }

    private sealed interface Command {
        data object Load : Command
        data class Save(val settings: SavedSettings, val success: StringResource) : Command
        data class Restore(val runtime: SavedSettings, val persisted: SavedSettings) : Command
    }
}
