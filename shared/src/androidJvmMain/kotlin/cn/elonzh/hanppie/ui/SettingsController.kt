package cn.elonzh.hanppie.ui

import cn.elonzh.hanppie.resources.Res
import cn.elonzh.hanppie.resources.default_settings_restored
import cn.elonzh.hanppie.resources.settings_load_failed
import cn.elonzh.hanppie.resources.settings_save_failed
import cn.elonzh.hanppie.resources.settings_saved
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource

/** Serializes blocking settings I/O so user actions are never dropped while a previous operation is running. */
internal class SettingsController(
    private val store: SettingsStore?,
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
    private val queueLock = Any()
    private val closed = AtomicBoolean(false)
    private var pendingCommands = 0

    init {
        scope.launch {
            for (command in commands) {
                try {
                    execute(command)
                } finally {
                    synchronized(queueLock) {
                        pendingCommands--
                        busy.value = pendingCommands > 0
                    }
                }
            }
        }
        if (store != null) enqueue(Command.Load)
    }

    fun save() {
        val snapshot = SavedSettings(model.value, autoRead.value, control.value)
        if (store == null) {
            message.value = uiText(Res.string.settings_saved)
        } else {
            enqueue(Command.Save(snapshot, Res.string.settings_saved))
        }
    }

    fun restoreDefaults() {
        val persisted = SavedSettings()
        val runtime = persisted.copy(model = runtimeDefaults())
        if (store == null) {
            apply(runtime)
            message.value = uiText(Res.string.default_settings_restored)
        } else {
            enqueue(Command.Restore(runtime, persisted))
        }
    }

    private fun enqueue(command: Command) {
        synchronized(queueLock) {
            if (closed.get()) return
            pendingCommands++
            busy.value = true
            message.value = null
            if (commands.trySend(command).isFailure) {
                pendingCommands--
                busy.value = pendingCommands > 0
            }
        }
    }

    private fun execute(command: Command) {
        when (command) {
            Command.Load -> try {
                store?.load()?.let { saved ->
                    apply(saved.copy(model = applyEnvironmentOverrides(saved.model)))
                }
            } catch (_: Exception) {
                message.value = uiText(Res.string.settings_load_failed)
            }
            is Command.Save -> persist(command.settings, command.success)
            is Command.Restore -> {
                apply(command.runtime)
                persist(command.persisted, Res.string.default_settings_restored)
            }
        }
    }

    private fun persist(settings: SavedSettings, success: StringResource) {
        try {
            // Saving a blank key intentionally clears the previously stored credential.
            if (settings.model.apiKey.isNotBlank()) settings.model.validate()
            checkNotNull(store).save(settings)
            message.value = uiText(success)
        } catch (_: Exception) {
            message.value = uiText(Res.string.settings_save_failed)
        }
    }

    private fun apply(settings: SavedSettings) {
        model.value = settings.model
        autoRead.value = settings.autoRead
        control.value = settings.control
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        commands.close()
        scope.cancel()
    }

    private sealed interface Command {
        data object Load : Command
        data class Save(val settings: SavedSettings, val success: StringResource) : Command
        data class Restore(val runtime: SavedSettings, val persisted: SavedSettings) : Command
    }
}
