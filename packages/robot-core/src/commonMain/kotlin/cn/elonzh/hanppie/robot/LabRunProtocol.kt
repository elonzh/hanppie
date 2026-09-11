package cn.elonzh.hanppie.robot

enum class LabRunEventType { STARTED, COMPLETED, FAILED }

data class LabRunEvent(val runId: String, val type: LabRunEventType, val text: String = "")

/** Private messages injected around one uploaded Lab program so the host can follow its lifecycle. */
object LabRunProtocol {
    private const val prefix = "__HANPPIE_RUN__|"
    private val runIdPattern = Regex("[a-f0-9]{16}")
    private val startPattern = Regex("(?m)^def[ \\t]+start[ \\t]*\\([ \\t]*\\)[ \\t]*:")

    fun instrument(source: String, runId: String): String {
        require(source.isNotBlank()) { "脚本不能为空" }
        require(runIdPattern.matches(runId)) { "运行标识必须是 16 位小写十六进制字符" }
        val symbol = "_hanppie_$runId"
        require(startPattern.containsMatchIn(source)) { "脚本需要定义 def start():" }
        val renamedSource = startPattern.replaceFirst(source.trimEnd(), "def ${symbol}_user_start():")
        val support = """
            def ${symbol}_send(kind, text=""):
                builtins = rm_define.__dict__["__builtins__"]
                importer = builtins["__import__"] if isinstance(builtins, dict) else builtins.__import__
                module = importer("rm_module", globals(), locals(), [], 0)
                head = "$prefix$runId|" + kind + "|"
                body = str(text).encode("utf-8")[:800 - len(head)]
                wire = head + "".join(chr(value) for value in body)
                module.Mobile(chassis_ctrl.event_client).custom_msg_send(0, 0, wire)
        """.trimIndent()
        val entry = """
            def start():
                ${symbol}_send("STARTED")
                try:
                    ${symbol}_user_start()
                except Exception as error:
                    ${symbol}_send("FAILED", error.__class__.__name__ + ": " + str(error))
                    raise
                else:
                    ${symbol}_send("COMPLETED")
        """.trimIndent()
        return "$support\n\n$renamedSource\n\n$entry\n"
    }

    fun decode(message: String): LabRunEvent? {
        if (!message.startsWith(prefix)) return null
        val parts = message.split('|', limit = 4)
        if (parts.size != 4 || !runIdPattern.matches(parts[1])) return null
        val type = when (parts[2]) {
            "STARTED" -> LabRunEventType.STARTED
            "COMPLETED" -> LabRunEventType.COMPLETED
            "FAILED" -> LabRunEventType.FAILED
            else -> return null
        }
        return LabRunEvent(parts[1], type, parts[3])
    }
}
