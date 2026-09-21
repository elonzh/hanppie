package cn.elonzh.hanppie.agent.skills

import cn.elonzh.hanppie.resources.Res

internal object BuiltinSkills {
    private val resources = listOf(
        "lab-python/SKILL.md",
        "lab-python/references/sensors-and-vision.md",
        "lab-python/references/ep-extensions.md",
    )

    /** Refresh packaged documents before discovery; user files outside this directory are untouched. */
    suspend fun install(writeDocument: suspend (String, ByteArray) -> Unit) {
        for (path in resources) {
            writeDocument(path, Res.readBytes("files/skills/$path"))
        }
    }
}
