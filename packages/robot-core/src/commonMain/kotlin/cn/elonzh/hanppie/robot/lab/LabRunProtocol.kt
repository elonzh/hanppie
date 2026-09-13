package cn.elonzh.hanppie.robot.lab

enum class LabRunEventType { STARTED, COMPLETED, FAILED }

data class LabRunEvent(val runId: String, val type: LabRunEventType, val text: String = "")

/** Private messages injected around one uploaded Lab program so the host can follow its lifecycle. */
object LabRunProtocol {
    private const val prefix = "__HANPPIE_RUN__|"
    private val runIdPattern = Regex("[a-f0-9]{16}")
    private val startPattern = Regex("(?m)^def[ \\t]+start[ \\t]*\\([ \\t]*\\)[ \\t]*:")
    private val importPattern = Regex("(?m)^[ \\t]*(?:import[ \\t]+|from[ \\t]+\\S+[ \\t]+import[ \\t]+)")

    fun instrument(source: String, runId: String): String {
        require(source.isNotBlank()) { "脚本不能为空" }
        require(runIdPattern.matches(runId)) { "运行标识必须是 16 位小写十六进制字符" }
        require(!importPattern.containsMatchIn(source)) {
            "Lab 脚本不能使用 import；time 等 SDK 对象已由机内环境提供"
        }
        val symbol = "_hanppie_$runId"
        require(startPattern.containsMatchIn(source)) { "脚本需要定义 def start():" }
        val renamedSource = startPattern.replaceFirst(source.trimEnd(), "def ${symbol}_user_start():")
        val support = """
            def ${symbol}_send(kind, text=""):
                log_ctrl.print_msg("$prefix$runId|" + kind + "|" + str(text))
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
        val start = message.indexOf(prefix)
        if (start < 0) return null
        val parts = message.substring(start).split('|', limit = 4)
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
