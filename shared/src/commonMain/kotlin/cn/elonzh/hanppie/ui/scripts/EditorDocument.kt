package cn.elonzh.hanppie.ui.scripts

import cn.elonzh.hanppie.robot.lab.LabAudioClip

internal data class EditorDocument(
    val source: String = DEFAULT_SCRIPT_SOURCE,
    val savedSource: String = source,
    val path: String? = null,
    val busy: Boolean = false,
    val scriptId: String? = null,
    val title: String? = null,
    val initialAudio: List<LabAudioClip> = emptyList(),
) {
    val dirty: Boolean get() = source != savedSource
    val displayName: String? get() = title ?: path?.substringAfterLast('/')?.substringAfterLast('\\')

    fun stored(script: StoredScript): EditorDocument = copy(
        source = script.source,
        savedSource = script.source,
        path = null,
        busy = false,
        scriptId = script.id,
        title = script.name,
        initialAudio = emptyList(),
    )

    companion object {
        fun from(script: StoredScript): EditorDocument = EditorDocument(
            source = script.source,
            scriptId = script.id,
            title = script.name,
        )
    }
}

private const val DEFAULT_SCRIPT_SOURCE = "def start():\n    log_ctrl.print_msg('Hello from Hanppie')\n"
