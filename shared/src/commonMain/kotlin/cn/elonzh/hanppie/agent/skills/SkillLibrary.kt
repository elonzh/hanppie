package cn.elonzh.hanppie.agent.skills

import ai.koog.rag.base.files.FileSystemProvider
import ai.koog.skills.discovery.discoverSkills
import ai.koog.skills.model.Skill
import ai.koog.skills.prompt.SkillsPromptFormat
import ai.koog.skills.prompt.generateSkillsPrompt
import cn.elonzh.hanppie.agent.tools.ReadSkillTool

/** Metadata is discovered once after startup extraction; documents are read on demand. */
internal class SkillLibrary(
    private val skills: List<Skill>,
    private val readDocument: suspend (Skill, String) -> String,
) {
    fun list(): List<Skill> = skills

    fun prompt(): String = generateSkillsPrompt(skills, SkillsPromptFormat.XML, includeLocation = false)

    suspend fun read(name: String, path: String = "SKILL.md"): ReadSkillTool.Result {
        val skill = requireNotNull(skills.find { it.name == name }) {
            "Unknown skill name. Use a name from available_skills."
        }
        require(path.isNotEmpty() && path.split('/').all { it.isNotEmpty() && it != "." && it != ".." } &&
            path.none { it == '\\' || it == ':' || it.isISOControl() }) { "Expected a relative skill document path" }
        require(path.endsWith(".md")) { "Expected a Markdown skill document" }
        return ReadSkillTool.Result(readDocument(skill, path))
    }

    companion object {
        suspend fun <Path> load(
            files: FileSystemProvider.ReadOnly<Path>,
            root: Path,
            realPath: (Path) -> Path,
        ): SkillLibrary {
            val rootPath = realPath(root)
            val skills = discoverSkills(files, listOf(files.toAbsolutePathString(rootPath)))
            return SkillLibrary(skills) { skill, path ->
                val directory = realPath(requireNotNull(files.parent(files.fromAbsolutePathString(skill.location))))
                val target = realPath(files.joinPath(directory, path))
                require(files.relativize(rootPath, directory)?.let { it != ".." && !it.startsWith("../") } == true &&
                    files.relativize(directory, target)?.let { it != ".." && !it.startsWith("../") } == true) {
                    "Skill document is outside its directory"
                }
                files.readBytes(target).decodeToString()
            }
        }
    }
}
