package cn.elonzh.hanppie.ui.scripts

import cn.elonzh.hanppie.robot.lab.LabAudioClip
import io.github.vinceglb.filekit.PlatformFile
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class DirectoryScriptRepositoryTest {
    @Test
    fun scriptBundleSavesAndLoadsCompletePackage() = runBlocking {
        val tempDir = Files.createTempDirectory("script-bundle-test-")
        try {
            val root = PlatformFile(tempDir.toString())
            val scriptDir = PlatformFile(root, "my_script")
            val manifest = ScriptManifest(
                id = "my_script",
                name = "测试脚本",
                summary = "简介",
                createdAtEpochMillis = 1000L,
                updatedAtEpochMillis = 2000L,
            )
            // 2 packets of 10 bytes each -> 2 * 20ms = 40ms
            val packetHeader1 = byteArrayOf(10, 0)
            val packetBody1 = ByteArray(10) { 1 }
            val packetHeader2 = byteArrayOf(10, 0)
            val packetBody2 = ByteArray(10) { 2 }
            val packets = packetHeader1 + packetBody1 + packetHeader2 + packetBody2

            val clip = LabAudioClip(0, "intro", 40L, packets)
            ScriptBundle.save(scriptDir, manifest, "def start(): pass\n", listOf(clip))

            val loaded = ScriptBundle.load(scriptDir)
            assertNotNull(loaded)
            assertEquals("my_script", loaded.id)
            assertEquals("测试脚本", loaded.name)
            assertEquals("简介", loaded.summary)
            assertEquals("def start(): pass\n", loaded.source)
            assertEquals(1, loaded.audioClips.size)
            assertEquals(0, loaded.audioClips[0].id)
            assertEquals("intro", loaded.audioClips[0].name)
            assertEquals(40L, loaded.audioClips[0].durationMillis)

            val listed = ScriptBundle.list(root)
            assertEquals(1, listed.size)
            assertEquals("my_script", listed[0].id)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun directoryWithoutManifestGracefullyFallsBackToFolderProperties() = runBlocking {
        val tempDir = Files.createTempDirectory("script-fallback-test-")
        try {
            val rawDir = tempDir.resolve("raw_python")
            Files.createDirectories(rawDir)
            Files.writeString(rawDir.resolve("script.py"), "print('hello')\n")
            val scriptDir = PlatformFile(rawDir.toString())

            val loaded = ScriptBundle.load(scriptDir)
            assertNotNull(loaded)
            assertEquals("raw_python", loaded.id)
            assertEquals("raw_python", loaded.name)
            assertEquals("print('hello')\n", loaded.source)
            assertTrue(loaded.audioClips.isEmpty())
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun directoryScriptRepositoryManagesUserScriptsAndAudio() = runBlocking {
        val tempDir = Files.createTempDirectory("dir-repo-test-")
        try {
            val userDir = PlatformFile(tempDir.resolve("user").toString())
            val presetsDir = PlatformFile(tempDir.resolve("presets").toString())
            val repo = DirectoryScriptRepository(userDir, presetsDir, enablePresetSync = false)

            assertTrue(repo.all().isEmpty())

            val script = StoredScript("test-1", "第一个脚本", "def start(): pass\n", 100L, 100L)
            repo.insert(script)
            assertEquals(1, repo.all().size)
            assertEquals("第一个脚本", repo.all().first().name)

            val clipBytes = byteArrayOf(4, 0, 1, 2, 3, 4) // 1 packet of 4 bytes -> 20ms
            val clip = LabAudioClip(1, "sound", 20L, clipBytes)
            repo.insertAudio("test-1", clip)

            val audios = repo.audio("test-1")
            assertEquals(1, audios.size)
            assertEquals(1, audios[0].id)
            assertEquals("sound", audios[0].name)

            assertTrue(repo.deleteAudio("test-1", 1))
            assertFalse(repo.deleteAudio("test-1", 1))
            assertTrue(repo.audio("test-1").isEmpty())

            repo.delete(script)
            assertTrue(repo.all().isEmpty())
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun scriptBundleDeduplicatesAudioClipsBySlotId() = runBlocking {
        val tempDir = Files.createTempDirectory("dedup-test-")
        try {
            val scriptDir = tempDir.resolve("disco")
            val audioDir = scriptDir.resolve("audio")
            Files.createDirectories(audioDir)
            Files.writeString(scriptDir.resolve("script.py"), "def start(): pass\n")
            Files.writeString(
                scriptDir.resolve("manifest.json"),
                """{"id":"disco","name":"狂欢舞","createdAtEpochMillis":1,"updatedAtEpochMillis":1}"""
            )
            // Two files with slot 0, two files with slot 1
            val packet = byteArrayOf(4, 0, 1, 2, 3, 4)
            Files.write(audioDir.resolve("0_intro.opus"), packet)
            Files.write(audioDir.resolve("0_ready.opus"), packet)
            Files.write(audioDir.resolve("1_verse.opus"), packet)
            Files.write(audioDir.resolve("1_chorus.opus"), packet)
            Files.write(audioDir.resolve("2_bridge.opus"), packet)

            val pkg = ScriptBundle.load(PlatformFile(scriptDir.toString()))
            assertNotNull(pkg)
            val slots = pkg.audioClips.map { it.id }
            assertEquals(listOf(0, 1, 2), slots, "Slots must be deduplicated by id")
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun presetSyncCleansStaleAudioFilesFromPresetDirectory() = runBlocking {
        val tempDir = Files.createTempDirectory("preset-sync-clean-test-")
        try {
            val userDir = PlatformFile(tempDir.resolve("user").toString())
            val presetsDir = PlatformFile(tempDir.resolve("presets").toString())
            val repo = DirectoryScriptRepository(userDir, presetsDir, enablePresetSync = true)

            // Pre-seed an obsolete audio file in friday_disco audio directory
            val fridayAudioDir = tempDir.resolve("presets").resolve("friday_disco").resolve("audio")
            Files.createDirectories(fridayAudioDir)
            val staleFile = fridayAudioDir.resolve("0_friday_intro.opus")
            Files.write(staleFile, byteArrayOf(1, 2, 3, 4))
            assertTrue(Files.exists(staleFile))

            // Sync presets
            repo.syncBundledPresetsIfNeeded()

            // The stale file must be deleted because it is not in bundledPresets files
            assertFalse(Files.exists(staleFile), "Stale preset audio file must be cleaned up")

            val clips = repo.audio("friday-disco")
            val ids = clips.map { it.id }
            assertEquals(ids.distinct(), ids, "Audio slots in synced preset must have no duplicates")
            assertEquals(listOf(0, 1, 2, 3), ids)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
}
