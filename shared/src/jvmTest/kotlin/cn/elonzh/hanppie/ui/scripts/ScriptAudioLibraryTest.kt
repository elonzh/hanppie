package cn.elonzh.hanppie.ui.scripts

import cn.elonzh.hanppie.robot.lab.LabAudioClip
import cn.elonzh.hanppie.robot.lab.LabProgram
import cn.elonzh.hanppie.ui.app.MemoryScriptRepository
import cn.elonzh.hanppie.ui.robot.audio.DecodedLabAudio
import cn.elonzh.hanppie.ui.robot.audio.LabAudioImporter
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class ScriptAudioLibraryTest {
    private class StubImporter(
        private val bytesPerClip: Int = 600,
        private val durationMillis: Long = 1_000,
        private val failure: String? = null,
    ) : LabAudioImporter {
        var imports = 0
        override suspend fun decode(name: String, bytes: ByteArray): DecodedLabAudio {
            imports++
            failure?.let { error(it) }
            return DecodedLabAudio(durationMillis, ByteArray(bytesPerClip) { it.toByte() })
        }
    }

    private suspend fun repository(): Pair<MemoryScriptRepository, StoredScript> {
        val repository = MemoryScriptRepository()
        val library = ScriptLibrary(repository)
        library.load()
        return repository to library.create("巡检", "def start():\n    pass\n")
    }

    @Test fun importAssignsFreeIdsAndSurvivesReload() = runBlocking {
        val (repository, script) = repository()
        val importer = StubImporter()
        val library = ScriptAudioLibrary(repository, importer)
        library.open(script.id)
        val first = library.import("voice.mp3", byteArrayOf(1))
        val second = library.import("beep.wav", byteArrayOf(2))
        assertEquals(0, first.id)
        assertEquals("voice", first.name)
        assertEquals(1, second.id)
        assertEquals("beep", second.name)
        assertEquals("rm_define.media_custom_audio_0", first.soundConstant)
        assertEquals(1_000, first.durationMillis)

        val reloaded = ScriptAudioLibrary(repository, importer)
        reloaded.open(script.id)
        assertEquals(listOf(0, 1), reloaded.state.value.clips.map { it.id })
        assertEquals(2, repository.audio(script.id).size)
    }

    @Test fun draftWithoutASavedScriptCannotCarryAudio() = runBlocking {
        val (repository, _) = repository()
        val library = ScriptAudioLibrary(repository, StubImporter())
        library.open(null)
        val failure = assertFailsWith<IllegalStateException> { library.import("voice.mp3", byteArrayOf(1)) }
        assertContains(failure.message.orEmpty(), "请先保存脚本")
        assertTrue(library.state.value.clips.isEmpty())
    }

    @Test fun theMachineOnlyExposesTenSlots() = runBlocking {
        val (repository, script) = repository()
        val library = ScriptAudioLibrary(repository, StubImporter())
        library.open(script.id)
        repeat(LabAudioClip.MAX_CLIPS) { index -> library.import("clip$index.mp3", byteArrayOf(1)) }
        assertEquals((0 until LabAudioClip.MAX_CLIPS).toList(), library.state.value.clips.map { it.id })
        val failure = assertFailsWith<IllegalStateException> { library.import("extra.mp3", byteArrayOf(1)) }
        assertContains(failure.message.orEmpty(), "最多 ${LabAudioClip.MAX_CLIPS} 个")
    }

    @Test fun audioThatCannotFitTheDspBudgetIsRejected() = runBlocking {
        val (repository, script) = repository()
        val oversized = StubImporter(bytesPerClip = LabProgram.MAX_DSP_BYTES)
        val library = ScriptAudioLibrary(repository, oversized)
        library.open(script.id)
        val failure = assertFailsWith<IllegalArgumentException> { library.import("long.mp3", byteArrayOf(1)) }
        assertContains(failure.message.orEmpty(), "上传预算")
        assertTrue(repository.audio(script.id).isEmpty())
        assertTrue(library.state.value.clips.isEmpty())
    }

    @Test fun renameAndDeleteKeepStateAndStorageInStep() = runBlocking {
        val (repository, script) = repository()
        val library = ScriptAudioLibrary(repository, StubImporter())
        library.open(script.id)
        library.import("voice.mp3", byteArrayOf(1))

        val renamed = library.rename(0, "Opening voice")
        assertEquals("Opening voice", renamed.name)
        assertEquals(listOf("Opening voice"), library.state.value.clips.map { it.name })
        assertEquals("Opening voice", repository.audio(script.id).single().name)

        library.delete(0)
        assertTrue(library.state.value.clips.isEmpty())
        assertTrue(repository.audio(script.id).isEmpty())
        assertFailsWith<IllegalStateException> { library.delete(0) }
        Unit
    }

    @Test fun decoderFailureIsReportedWithoutChangingTheLibrary() = runBlocking {
        val (repository, script) = repository()
        val library = ScriptAudioLibrary(repository, StubImporter(failure = "无法解码该音频文件"))
        library.open(script.id)
        assertFailsWith<IllegalStateException> { library.import("broken.bin", byteArrayOf(1)) }
        assertContains(library.state.value.error.orEmpty(), "无法解码")
        assertTrue(library.state.value.clips.isEmpty())
    }

    @Test fun namesComeFromFileNamesAndFitTheDspField() {
        assertEquals("voice", labAudioName("voice.mp3"))
        assertEquals("voice", labAudioName("  voice  "))
        assertEquals("audio", labAudioName("   "))
        assertEquals("audio", labAudioName(".mp3"))
        assertEquals(LabAudioClip.MAX_NAME_LENGTH, labAudioName("${"n".repeat(100)}.wav").length)
        assertEquals(2, freeAudioId(listOf(LabAudioClip(0, "a", 1, byteArrayOf(1)), LabAudioClip(1, "b", 1, byteArrayOf(1)))))
        assertEquals(0, freeAudioId(emptyList()))
    }

    @Test fun presetInitialAudioSeedsStateAndCanBePersisted() = runBlocking {
        val (repository, script) = repository()
        val library = ScriptAudioLibrary(repository, StubImporter())
        val presetClip = LabAudioClip(0, "preset_song", 11_500, byteArrayOf(1, 2, 3))

        // When opened with null scriptId and initial audio, clips are seeded into state
        library.open(null, listOf(presetClip))
        assertEquals(listOf(presetClip), library.state.value.clips)
        assertEquals(null, library.state.value.scriptId)

        // When saving the script, saveClipsFor persists the audio to repository and binds scriptId
        library.saveClipsFor(script.id, listOf(presetClip))
        assertEquals(script.id, library.state.value.scriptId)
        assertEquals(listOf(presetClip), library.state.value.clips)
        val stored = repository.audio(script.id)
        assertEquals(1, stored.size)
        assertEquals("preset_song", stored.single().name)
        assertEquals(0, stored.single().id)
    }

    @Test fun reorderAndMoveReassignIdsSequentiallyAndPersist() = runBlocking {
        val (repository, script) = repository()
        val library = ScriptAudioLibrary(repository, StubImporter())
        library.open(script.id)
        library.import("first.mp3", byteArrayOf(1))
        library.import("second.mp3", byteArrayOf(2))
        library.import("third.mp3", byteArrayOf(3))

        assertEquals(listOf(0, 1, 2), library.state.value.clips.map { it.id })
        assertEquals(listOf("first", "second", "third"), library.state.value.clips.map { it.name })

        // Move item 0 down to index 1 -> ["second", "first", "third"]
        library.move(0, 1)
        assertEquals(listOf("second", "first", "third"), library.state.value.clips.map { it.name })
        assertEquals(listOf(0, 1, 2), library.state.value.clips.map { it.id })
        assertEquals(listOf("second", "first", "third"), repository.audio(script.id).map { it.name })
        assertEquals(listOf(0, 1, 2), repository.audio(script.id).map { it.id })

        // Reorder via full list
        val reversed = library.state.value.clips.reversed()
        library.reorder(reversed)
        assertEquals(listOf("third", "first", "second"), library.state.value.clips.map { it.name })
        assertEquals(listOf(0, 1, 2), library.state.value.clips.map { it.id })
        assertEquals(listOf("third", "first", "second"), repository.audio(script.id).map { it.name })
        assertEquals(listOf(0, 1, 2), repository.audio(script.id).map { it.id })
    }

    private class StubPlayer : cn.elonzh.hanppie.ui.robot.audio.LabAudioPlayer {
        private val _state = kotlinx.coroutines.flow.MutableStateFlow(cn.elonzh.hanppie.ui.robot.audio.AudioPlaybackState())
        override val state: kotlinx.coroutines.flow.StateFlow<cn.elonzh.hanppie.ui.robot.audio.AudioPlaybackState> = _state
        var playCount = 0
        var pauseCount = 0
        var resumeCount = 0
        var stopCount = 0

        override fun play(clip: LabAudioClip) {
            playCount++
            _state.value = cn.elonzh.hanppie.ui.robot.audio.AudioPlaybackState(playingClipId = clip.id, isPlaying = true, durationMillis = clip.durationMillis)
        }

        override fun pause() {
            pauseCount++
            _state.value = _state.value.copy(isPlaying = false)
        }

        override fun resume() {
            resumeCount++
            _state.value = _state.value.copy(isPlaying = true)
        }

        override fun stop() {
            stopCount++
            _state.value = cn.elonzh.hanppie.ui.robot.audio.AudioPlaybackState()
        }
    }

    @Test fun playbackControlsSupportPlayPauseResumeAndAutoResume() = runBlocking {
        val (repository, script) = repository()
        val player = StubPlayer()
        val library = ScriptAudioLibrary(repository, StubImporter(), player = player)
        val clip = LabAudioClip(0, "clip", 10_000, byteArrayOf(1, 2))

        // Initial play
        library.play(clip)
        assertEquals(1, player.playCount)
        assertEquals(0, player.resumeCount)
        assertEquals(true, library.playbackState.value.isPlaying)
        assertEquals(0, library.playbackState.value.playingClipId)

        // Pause
        library.pause()
        assertEquals(1, player.pauseCount)
        assertEquals(false, library.playbackState.value.isPlaying)

        // Calling play(clip) while paused resumes instead of re-triggering play from start
        library.play(clip)
        assertEquals(1, player.playCount)
        assertEquals(1, player.resumeCount)
        assertEquals(true, library.playbackState.value.isPlaying)

        // Pause again and call resume()
        library.pause()
        assertEquals(2, player.pauseCount)
        library.resume()
        assertEquals(2, player.resumeCount)
        assertEquals(true, library.playbackState.value.isPlaying)

        // Stop
        library.stop()
        assertEquals(1, player.stopCount)
        assertEquals(false, library.playbackState.value.isPlaying)
        assertEquals(null, library.playbackState.value.playingClipId)
    }
}
