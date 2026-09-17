package cn.elonzh.hanppie.ui.scripts

/** The Lab Python statement the editor writes for one custom audio resource. */
internal fun scriptAudioStatement(constant: String): String = "media_ctrl.play_sound($constant)"

/**
 * Keeps an inserted statement on its own line, so the generated Python stays valid wherever the cursor
 * is. At the start of a line the existing indentation is reused as-is; after code the statement moves to
 * a new line carrying that line's indentation.
 */
internal fun scriptAudioInsertion(prefix: String, suffix: String, statement: String): String {
    if (prefix.isEmpty()) return statement + "\n"
    val currentLine = prefix.substringAfterLast('\n')
    val lineEnded = prefix.endsWith("\n")
    val closing = if (suffix.isEmpty() || !suffix.startsWith("\n")) "\n" else ""
    if (lineEnded || currentLine.isBlank()) return statement + closing
    val indent = currentLine.takeWhile { it == ' ' || it == '\t' }
    return "\n" + indent + statement + closing
}
