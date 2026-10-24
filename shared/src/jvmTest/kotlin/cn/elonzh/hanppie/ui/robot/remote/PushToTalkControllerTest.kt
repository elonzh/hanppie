package cn.elonzh.hanppie.ui.robot.remote

import cn.elonzh.hanppie.resources.*
import kotlinx.coroutines.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class PushToTalkControllerTest {
    private class Input : SpeakerInput {
        val ready = mutableListOf<() -> Unit>()
        val failures = mutableListOf<(Throwable) -> Unit>()
        var startFailure: Exception? = null
        var finishFailure: Exception? = null
        var cancellations = 0
        var finishes = 0
        override fun start(onReady: () -> Unit, onError: (Throwable) -> Unit) {
            startFailure?.let { throw it }; ready += onReady; failures += onError
        }
        override suspend fun finish(): ByteArray { finishes++; finishFailure?.let { throw it }; return byteArrayOf(1, 2, 3) }
        override fun cancel() { cancellations++ }
    }

    @Test fun releasesBeforeFirstSamplesDiscardTheClipAndRetireBothCallbacks() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val input = Input()
        val controller = PushToTalkController(scope, input)
        try {
            controller.start(); assertEquals(TalkPhase.PREPARING, controller.state.value.phase)
            controller.finish { error("Unready capture must not upload") }
            assertEquals(0, input.finishes); assertFalse(controller.state.value.active)
            controller.start(); input.ready[0](); input.failures[0](IllegalStateException("old"))
            assertEquals(TalkPhase.PREPARING, controller.state.value.phase); assertNull(controller.state.value.error)
            input.ready[1](); assertEquals(TalkPhase.RECORDING, controller.state.value.phase)
        } finally { controller.close(); scope.cancel() }
    }

    @Test fun synchronousAndAsynchronousCaptureFailuresRemainVisibleAndAllowANewAttempt() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val input = Input()
        val controller = PushToTalkController(scope, input)
        try {
            input.startFailure = IllegalStateException("EACCES")
            controller.start(); assertEquals(Res.string.talk_capture_failed_value, controller.state.value.error?.resource)
            assertEquals(listOf("EACCES"), controller.state.value.error?.arguments)
            input.startFailure = null; controller.start(); assertNull(controller.state.value.error)
            input.failures.last()(IllegalStateException("READ -6"))
            assertFalse(controller.state.value.active)
            assertEquals(listOf("READ -6"), controller.state.value.error?.arguments)
            controller.start(); input.ready.last()(); assertEquals(TalkPhase.RECORDING, controller.state.value.phase)
        } finally { controller.close(); scope.cancel() }
    }

    @Test fun encodingAndTransportFailuresHaveDifferentContextsAndOnlySuccessReportsPackets() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val input = Input(); val sent = mutableListOf<Int>()
        val controller = PushToTalkController(scope, input, sent::add)
        try {
            controller.start(); input.ready.last()(); input.finishFailure = IllegalStateException("codec failure")
            controller.finish { error("Encoding failure must not upload") }
            assertEquals(Res.string.talk_capture_failed_value, controller.state.value.error?.resource)
            input.finishFailure = null; controller.start(); input.ready.last()()
            controller.finish { assertContentEquals(byteArrayOf(1, 2, 3), it); error("network failure") }
            assertEquals(Res.string.talk_send_failed_value, controller.state.value.error?.resource); assertTrue(sent.isEmpty())
            controller.start(); input.ready.last()(); controller.finish { 3 }
            assertEquals(listOf(3), sent); assertEquals(PushToTalkState(), controller.state.value)
        } finally { controller.close(); scope.cancel() }
    }

    @Test fun anAlreadyReleasedUploadKeepsOwnershipWithoutAcceptingAnotherCapture() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val input = Input(); val release = CompletableDeferred<Unit>()
        val controller = PushToTalkController(scope, input)
        try {
            controller.start(); input.ready.last()(); controller.finish { release.await(); 1 }
            controller.cancel(); controller.start()
            assertEquals(TalkPhase.SENDING, controller.state.value.phase); assertEquals(1, input.ready.size)
            input.failures.last()(IllegalStateException("late microphone error")); assertNull(controller.state.value.error)
            release.complete(Unit); assertEquals(PushToTalkState(), controller.state.value)
        } finally { release.complete(Unit); controller.close(); scope.cancel() }
    }

    @Test fun closingOrCancellingTheParentRetiresSendingAndLateCallbacks() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val input = Input()
        val controller = PushToTalkController(scope, input)
        controller.start(); input.ready.last()(); scope.cancel()
        controller.finish { error("Cancelled scope must not upload") }
        assertFalse(controller.state.value.busy)
        controller.close(); input.ready.last()(); input.failures.last()(IllegalStateException("late")); controller.start()
        assertEquals(PushToTalkState(), controller.state.value)
    }

    @Test fun concurrentPressesAcquireOnlyOneCapture() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val input = Input()
        val controller = PushToTalkController(scope, input); val pool = Executors.newFixedThreadPool(4); val start = CyclicBarrier(4)
        try {
            val jobs = (1..4).map { pool.submit { start.await(); controller.start() } }
            jobs.forEach { it.get(5, TimeUnit.SECONDS) }; assertEquals(1, input.ready.size)
        } finally { controller.close(); scope.cancel(); pool.shutdownNow() }
    }

    @Test fun anOldBlockedReadCannotOverwriteOrStopTheNextCapture() = runBlocking {
        val entered = CountDownLatch(1); val unblock = CountDownLatch(1); val ready = CountDownLatch(1)
        val creates = AtomicInteger(); val oldReady = AtomicInteger(); val prefix = ByteArray(480) { 42 }
        val capture = BoundedPcmCapture({
            val old = creates.incrementAndGet() == 1
            object : PcmCaptureDevice {
                var first = true
                override fun start() = Unit
                override fun read(buffer: ByteArray, length: Int): Int {
                    if (first) {
                        first = false
                        if (old) { entered.countDown(); check(unblock.await(5, TimeUnit.SECONDS)) }
                        prefix.copyInto(buffer); return prefix.size
                    }
                    Thread.sleep(2); return 0
                }
                override fun stop() = Unit
                override fun close() = Unit
            }
        }, { it })
        try {
            capture.start({ oldReady.incrementAndGet() }, { throw it }); assertTrue(entered.await(5, TimeUnit.SECONDS))
            capture.cancel(); capture.start({ ready.countDown() }, { throw it })
            assertTrue(ready.await(5, TimeUnit.SECONDS)); unblock.countDown()
            assertContentEquals(prefix, capture.finish()); assertEquals(0, oldReady.get())
        } finally { unblock.countDown(); capture.close() }
    }

    @Test fun failedReadsReportWithoutWaitingForAReleaseAndUnavailableInputsKeepTypedFeedback() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val failed = CountDownLatch(1)
        val input = BoundedPcmCapture({ object : PcmCaptureDevice {
            override fun start() = Unit
            override fun read(buffer: ByteArray, length: Int): Int = -6
            override fun stop() = Unit
            override fun close() = Unit
        } }, { it })
        val controller = PushToTalkController(scope, input, onError = { failed.countDown() })
        try {
            controller.start(); assertTrue(failed.await(5, TimeUnit.SECONDS))
            withTimeout(5_000) { while (controller.state.value.active) yield() }
            assertEquals(Res.string.talk_capture_failed_value, controller.state.value.error?.resource)
            val unavailable = PushToTalkController(scope, NoSpeakerInput())
            assertFalse(unavailable.available); unavailable.start()
            assertEquals(Res.string.talk_input_unavailable, unavailable.state.value.error?.resource); unavailable.close()
        } finally { controller.close(); scope.cancel() }
    }
}
