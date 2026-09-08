package cn.elonzh.hanppie.desktop

import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

/** Native TTS only. Text is passed on stdin, never interpolated into shell commands. */
internal class SystemSpeech : SpeechEngine {
    private val command = speechCommand(System.getProperty("os.name")) { File(it).canExecute() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var process: Process? = null
    private var generation = 0L
    override val state = MutableStateFlow(SpeechState(
        when { command == null -> tr("未检测到系统语音引擎")
            command.first().endsWith("spd-say") -> "Speech Dispatcher"
            command.first().endsWith("say") -> tr("macOS 系统语音")
            else -> tr("Windows 系统语音") }, command != null))

    override fun speak(text: String) {
        require(text.length <= 4000) { tr("单次播报最多 4000 个字符") }
        if (text.isBlank()) return
        val token = synchronized(lock) {
            generation++
            process?.destroy(); process = null
            state.value = state.value.copy(speaking = command != null, error = if (command == null) tr("系统语音引擎不可用") else null)
            generation
        }
        val args = command ?: return
        scope.launch {
            var child: Process? = null
            try {
                synchronized(lock) {
                    if (token != generation) return@launch
                    child = ProcessBuilder(args).redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
                    process = child
                }
                child!!.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
                val code = child.waitFor()
                synchronized(lock) {
                    if (token == generation) state.value = state.value.copy(speaking = false,
                        error = if (code == 0) null else tr("系统语音进程退出：{0}",code))
                }
            } catch (error: Exception) {
                synchronized(lock) {
                    if (token == generation) state.value = state.value.copy(speaking = false, error = error.message)
                }
            } finally {
                child?.destroy()
                synchronized(lock) { if (process === child) process = null }
            }
        }
    }

    override fun stop() = synchronized(lock) {
        generation++
        process?.destroy(); process = null
        state.value = state.value.copy(speaking = false)
    }

    override fun close() { stop(); scope.cancel() }
}

internal fun speechCommand(os: String, executable: (String) -> Boolean): List<String>? = when {
    os.startsWith("Mac", true) -> listOf("/usr/bin/say").takeIf { executable(it.first()) }
    os.startsWith("Windows", true) -> listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
        "[Console]::InputEncoding = [Text.Encoding]::UTF8; Add-Type -AssemblyName System.Speech; " +
            "\$voice = New-Object System.Speech.Synthesis.SpeechSynthesizer; " +
            "try { \$voice.Speak([Console]::In.ReadToEnd()) } finally { \$voice.Dispose() }")
    os.startsWith("Linux", true) -> listOf("/usr/bin/spd-say", "--wait", "--pipe-mode").takeIf { executable(it.first()) }
    else -> null
}
