package cn.elonzh.hanppie.ui.robot.audio

import cn.elonzh.hanppie.robot.media.*
import cn.elonzh.hanppie.robot.session.RequestProblem
import cn.elonzh.hanppie.robot.session.RobotRequestException
import kotlinx.coroutines.*
import kotlin.test.*

class RobotAudioControllerTest {
    private class Audio : RobotAudioService {
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
}
