package cn.elonzh.hanppie.agent.skills

import cn.elonzh.hanppie.agent.tools.ReadSkillTool
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class SkillLibraryTest {
    private suspend fun load(root: Path) = SkillLibrary.load(SkillFileSystem, root) { it.toRealPath() }

    @Test fun packagedSkillsAreExtractedRefreshedAndLoadedFromDisk(): Unit = runBlocking {
        val data = createTempDirectory("hanppie-skills-")
        try {
            val root = data.resolve("skills/builtin")
            val unrelated = data.resolve("user.md").apply { writeText("keep") }
            BuiltinSkills.install { path, bytes ->
                val target = root.resolve(path)
                target.parent.createDirectories()
                Files.write(target, bytes)
            }
            val entry = root.resolve("lab-python/SKILL.md")
            val packaged = entry.readText()
            assertContains(packaged, "name: lab-python")
            entry.writeText("outdated")
            BuiltinSkills.install { path, bytes ->
                val target = root.resolve(path)
                target.parent.createDirectories()
                Files.write(target, bytes)
            }
            assertEquals(packaged, entry.readText())
            assertEquals("keep", unrelated.readText())
            val library = load(root)
            assertEquals(listOf("lab-python"), library.list().map { it.name })
            assertContains(library.prompt(), "<name>lab-python</name>")
            assertFalse(library.prompt().contains(root.toString()))
            assertFalse(library.prompt().contains("chassis_ctrl.move_with_time"))
            assertEquals(packaged, ReadSkillTool(library::read).execute(ReadSkillTool.Args("lab-python")).content)
            val sensorsDoc = ReadSkillTool(library::read).execute(
                ReadSkillTool.Args(
                    "lab-python",
                    "references/sensors-and-vision.md"
                )
            ).content
            assertContains(sensorsDoc, "armor_ctrl")
            val epDoc = ReadSkillTool(library::read).execute(
                ReadSkillTool.Args(
                    "lab-python",
                    "references/ep-extensions.md"
                )
            ).content
            assertContains(epDoc, "robotic_arm_ctrl")
        } finally {
            data.toFile().deleteRecursively()
        }
    }

    @Test fun koogDiscoversMultipleSkillsAndReadsSupportingDocumentsDirectly(): Unit = runBlocking {
        val root = createTempDirectory("hanppie-skills-")
        try {
            for ((name, description) in listOf("report" to "Write reports", "review" to "Review designs")) {
                val directory = root.resolve(name).createDirectories()
                directory.resolve("SKILL.md").writeText("---\nname: $name\ndescription: $description\n---\nBODY_$name")
            }
            val detail = root.resolve("report/references/details.md")
            detail.parent.createDirectories()
            detail.writeText("Details")
            root.resolve("invalid").createDirectories().resolve("SKILL.md").writeText("Missing metadata")
            val library = load(root)
            assertEquals(setOf("report", "review"), library.list().map { it.name }.toSet())
            assertContains(library.prompt(), "Write reports")
            assertFalse(library.prompt().contains("BODY_report"))
            assertEquals("Details", library.read("report", "references/details.md").content)
            detail.writeText("Updated details")
            assertEquals("Updated details", library.read("report", "references/details.md").content)
            assertFailsWith<IllegalArgumentException> { library.read("invalid") }
            assertFailsWith<java.nio.file.NoSuchFileException> { library.read("report", "missing.md") }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun unsafePathsAndSymlinksCannotEscapeTheSelectedSkill(): Unit = runBlocking {
        val root = createTempDirectory("hanppie-skills-")
        try {
            val directory = root.resolve("test").createDirectories()
            directory.resolve("SKILL.md").writeText("---\nname: test\ndescription: Test\n---\nInstructions")
            val outside = root.resolve("outside.md").apply { writeText("not a skill document") }
            Files.createSymbolicLink(directory.resolve("escape.md"), outside)
            val library = load(root)
            for (path in listOf("../outside.md", "/etc/passwd", "", "nested//SKILL.md", "C:\\secret.md", "script.py", "escape.md")) {
                assertFailsWith<IllegalArgumentException> { library.read("test", path) }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
