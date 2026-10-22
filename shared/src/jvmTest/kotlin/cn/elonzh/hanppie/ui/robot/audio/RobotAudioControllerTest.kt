package cn.elonzh.hanppie.ui.robot.audio

import cn.elonzh.hanppie.robot.media.*
import cn.elonzh.hanppie.robot.session.RequestProblem
import cn.elonzh.hanppie.robot.session.RobotRequestException
import kotlinx.coroutines.*
import kotlin.test.*

class RobotAudioControllerTest {
    private class Audio : RobotAudioService {
        override val playback = kotlinx.coroutines.flow.MutableStateFlow<cn.elonzh.hanppie.robot.media.RobotSpeakerPlayback?>(null)
        var sound = false
        var soundReads = 0
        val feedbackWrites = mutableListOf<Boolean>()
        var soundFailure: Exception? = null
        var feedbackFailure: Exception? = null
        var soundGate: CompletableDeferred<Unit>? = null
        var feedbackGate: CompletableDeferred<Unit>? = null
        override suspend fun readSoundEnabled(): Boolean {
            soundReads++
            val before = sound
            withContext(NonCancellable) { soundGate?.await() }
            soundFailure?.let { throw it }
            return before
        }
        override suspend fun setAntiFeedback(enabled: Boolean) {
            feedbackWrites += enabled
            withContext(NonCancellable) { feedbackGate?.await() }
            feedbackFailure?.let { throw it }
        }
        var settings = RobotAudioSettings(38, 0)
        var reads = 0
        var failure: Exception? = null
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun read(): RobotAudioSettings {
            reads++
            withContext(NonCancellable) { gate?.await() }
            failure?.let { throw it }
            return settings
        }
        override suspend fun applyVolume(volume: Int): RobotAudioSettings {
            settings = settings.copy(volume = volume); return read()
        }
        override suspend fun setLanguage(language: SpeakerLanguage): RobotAudioSettings {
            settings = settings.copy(languageCode = language.wireValue); return read()
        }
    }

    @Test fun updatesOnlyConfirmedValuesAndDiscardsStaleDataAfterAnError(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val audio = Audio()
            val controller = RobotAudioController(scope)
            controller.attach(audio); controller.refresh()
            assertEquals(38, controller.state.value.settings?.volume)
            controller.applyVolume(65)
            assertEquals(65, controller.state.value.settings?.volume)
            controller.setLanguage(SpeakerLanguage.CHINESE)
            assertEquals(SpeakerLanguage.CHINESE, controller.state.value.settings?.language)
            audio.failure = RobotRequestException(RequestProblem.TIMED_OUT)
            controller.refresh()
            assertNotNull(controller.state.value.error)
            assertNull(controller.state.value.settings)
            assertFalse(controller.state.value.busy)
            audio.failure = null
            controller.refresh()
            assertNotNull(controller.state.value.settings)
        } finally { scope.cancel() }
    }

    @Test fun ignoresLateRepliesAfterInvalidationOrAttachmentAndDeduplicatesBusyActions(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val old = Audio().apply { gate = CompletableDeferred() }
            val controller = RobotAudioController(scope)
            controller.attach(old); controller.refresh(); controller.refresh()
            assertTrue(controller.state.value.busy)
            assertEquals(1, old.reads)
            controller.invalidate()
            assertNull(controller.state.value.settings)
            old.gate!!.complete(Unit)
            yield()
            assertNull(controller.state.value.settings)
            val new = Audio().apply { settings = RobotAudioSettings(76, 2) }
            controller.attach(new); controller.refresh()
            assertEquals(76, controller.state.value.settings?.volume)
            controller.clear()
            assertEquals(RobotAudioState(), controller.state.value)
        } finally { scope.cancel() }
    }

    @Test fun nativeQueryFailureAndFeedbackResultNeverReplaceVolumeOrPlayback(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val audio = Audio().apply { soundFailure = RobotRequestException(RequestProblem.INVALID_RESPONSE) }
            val controller = RobotAudioController(scope)
            controller.attach(audio); controller.refresh()
            assertEquals(38, controller.state.value.settings?.volume)
            assertIs<AudioSetting.Failed>(controller.state.value.soundEnabled)
            controller.setAntiFeedback(true)
            assertEquals(AudioSetting.Value(true), controller.state.value.antiFeedback)
            val playback = RobotSpeakerPlayback(11, 65536, 35, 0)
            audio.playback.value = playback
            controller.applyVolume(65)
            assertEquals(playback, controller.state.value.playback)
            assertEquals(65, controller.state.value.settings?.volume)
            audio.feedbackFailure = RobotRequestException(RequestProblem.TIMED_OUT)
            controller.setAntiFeedback(false)
            assertIs<AudioSetting.Failed>(controller.state.value.antiFeedback)
            assertEquals(playback, controller.state.value.playback)
            audio.soundFailure = null; audio.sound = true
            controller.refreshSound()
            assertEquals(AudioSetting.Value(true), controller.state.value.soundEnabled)
            audio.feedbackFailure = null; controller.setAntiFeedback(false)
            assertEquals(AudioSetting.Value(false), controller.state.value.antiFeedback)
            audio.playback.value = null; assertNull(controller.state.value.playback)
        } finally { scope.cancel() }
    }

    @Test fun nativeRequestsDeduplicateAndIgnoreLateRepliesEvenWhenTheSameServiceIsRebound(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gate = CompletableDeferred<Unit>()
        try {
            val audio = Audio().apply { soundGate = gate; feedbackGate = gate }
            val controller = RobotAudioController(scope)
            controller.attach(audio); controller.refreshSound(); controller.refreshSound()
            controller.setAntiFeedback(true); controller.setAntiFeedback(false)
            assertEquals(1, audio.soundReads); assertEquals(listOf(true), audio.feedbackWrites)
            assertEquals(AudioSetting.Loading, controller.state.value.soundEnabled)
            val jobs = scope.coroutineContext[Job]!!.children.toList()
            controller.attach(audio); audio.sound = true
            gate.complete(Unit); jobs.forEach { it.join() }
            assertEquals(RobotAudioState(), controller.state.value)
            controller.refreshSound()
            assertEquals(AudioSetting.Value(true), controller.state.value.soundEnabled)
            assertEquals(AudioSetting.Unread, controller.state.value.antiFeedback)
            assertEquals(listOf(true), audio.feedbackWrites)
            controller.clear(); assertEquals(RobotAudioState(), controller.state.value)
            controller.attach(audio)
            val beforeReads = audio.soundReads
            scope.cancel()
            controller.refreshSound(); controller.setAntiFeedback(false); controller.applyVolume(1)
            assertEquals(beforeReads, audio.soundReads)
            assertEquals(listOf(true), audio.feedbackWrites)
            assertEquals(RobotAudioState(), controller.state.value)
        } finally { gate.complete(Unit); scope.cancel() }
    }
}
