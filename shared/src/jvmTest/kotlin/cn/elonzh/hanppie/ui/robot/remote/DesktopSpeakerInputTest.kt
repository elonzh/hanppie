package cn.elonzh.hanppie.ui.robot.remote

import cn.elonzh.hanppie.ui.app.testConsoleModel
import cn.elonzh.hanppie.ui.speech.SystemSpeech
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopSpeakerInputTest {
    @Test fun readinessFollowsFirstSamplesAndRetainsTheirPrefix() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val allowRead = java.util.concurrent.CountDownLatch(1)
        val ready = java.util.concurrent.CountDownLatch(1)
        val prefix = ByteArray(480) { (it % 127).toByte() }
        var first = true
        val line = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader,
            arrayOf(javax.sound.sampled.TargetDataLine::class.java)) { _, method, args ->
            when (method.name) {
                "getBufferSize" -> 1920
                "read" -> if (first) {
                    first = false; entered.countDown()
                    check(allowRead.await(2, java.util.concurrent.TimeUnit.SECONDS))
                    prefix.copyInto(args!![0] as ByteArray, args[1] as Int)
                    prefix.size
                } else { Thread.sleep(2); 0 }
                else -> null
            }
        } as javax.sound.sampled.TargetDataLine
        val capture = DesktopSpeakerInput(lineFactory = { line }, encoder = { it })
        try {
            capture.start { ready.countDown() }
            check(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            kotlin.test.assertEquals(1L, ready.count, "Starting the device must not announce readiness")
            allowRead.countDown()
            check(ready.await(2, java.util.concurrent.TimeUnit.SECONDS))
            assertContentEquals(prefix, capture.finish())
        } finally { allowRead.countDown(); capture.cancel() }
    }

    @Test fun lateReadinessCannotReactivateCancelledRecording() {
        val callbacks = mutableListOf<() -> Unit>()
        val input = object : SpeakerInput {
            override fun start(onReady: () -> Unit) { callbacks += onReady }
            override fun finish(): ByteArray = error("Unready recording must not be encoded")
            override fun cancel() = Unit
        }
        val model = testConsoleModel(SystemSpeech(), speakerInput = input)
        try {
            model.remoteEnabled.value = true
            model.beginPushToTalk()
            kotlin.test.assertFalse(model.microphoneReady.value)
            model.endPushToTalk()
            kotlin.test.assertFalse(model.talkBusy.value)
            model.beginPushToTalk()
            callbacks[0]()
            kotlin.test.assertFalse(model.microphoneReady.value)
            callbacks[1]()
            kotlin.test.assertTrue(model.microphoneReady.value)
        } finally { model.close() }
    }

    @Test fun extractsPacketsAcrossOggLacingSegments() {
        val head = "OpusHead".toByteArray()
        val tags = "OpusTags".toByteArray()
        val audio = ByteArray(300) { (it * 3).toByte() }
        val ogg = oggPage(listOf(head, tags, audio))
        val expected = ByteArrayOutputStream().apply {
            write(300 and 0xff); write(300 ushr 8); write(audio)
        }.toByteArray()
        assertContentEquals(expected, opusPacketsFromOgg(ogg))
    }

    @Test fun failedDeviceCreationDoesNotLeaveCaptureLocked() {
        var attempts = 0
        val capture = BoundedPcmCapture(
            deviceFactory = {
                attempts++
                if (attempts == 1) error("device unavailable")
                object : PcmCaptureDevice {
                    override fun start() = Unit
                    override fun read(buffer: ByteArray, length: Int): Int = 0
                    override fun stop() = Unit
                    override fun close() = Unit
                }
            },
            encoder = { it },
        )

        assertFailsWith<IllegalStateException> { capture.start {} }
        capture.start {}
        capture.cancel()
    }

    @Test fun timedOutEncoderDestroysItsProcess() {
        val process = HangingProcess()

        val error = assertFailsWith<IllegalStateException> {
            encodeDesktopSpeakerPcm(ByteArray(480), timeoutMillis = 1, processFactory = { process })
        }

        assertTrue(error.message.orEmpty().contains("超时"))
        assertTrue(process.destroyed)
        assertFalse(process.isAlive)
    }

    @Test fun cancellationDestroysAnActiveEncoderProcess() {
        var first = true
        val line = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader,
            arrayOf(javax.sound.sampled.TargetDataLine::class.java)) { _, method, args ->
            when (method.name) {
                "getBufferSize" -> 1920
                "read" -> if (first) {
                    first = false
                    ByteArray(480) { 1 }.copyInto(args!![0] as ByteArray, args[1] as Int)
                    480
                } else { Thread.sleep(2); 0 }
                else -> null
            }
        } as javax.sound.sampled.TargetDataLine
        val process = HangingProcess()
        val ready = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val input = DesktopSpeakerInput(
            lineFactory = { line },
            encoderTimeoutMillis = 5_000,
            processFactory = { process },
        )

        input.start { ready.countDown() }
        assertTrue(ready.await(2, TimeUnit.SECONDS))
        val finisher = thread { runCatching { input.finish() }.onFailure(failure::set) }
        assertTrue(process.waitEntered.await(2, TimeUnit.SECONDS))
        input.cancel()
        finisher.join(2_000)

        assertFalse(finisher.isAlive)
        assertTrue(process.destroyed)
        assertTrue(failure.get() is IllegalStateException)
    }

    private fun oggPage(packets: List<ByteArray>): ByteArray {
        val laces = packets.flatMap { packet ->
            buildList {
                var left = packet.size
                while (left >= 255) { add(255); left -= 255 }
                add(left)
            }
        }
        return ByteArrayOutputStream().apply {
            write("OggS".toByteArray()); write(ByteArray(22)); write(laces.size)
            laces.forEach(::write); packets.forEach(::write)
        }.toByteArray()
    }

    private class HangingProcess : Process() {
        var destroyed = false
        private var alive = true
        private val completed = CountDownLatch(1)
        val waitEntered = CountDownLatch(1)
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun waitFor(): Int { alive = false; return 143 }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            waitEntered.countDown()
            return completed.await(timeout, unit)
        }
        override fun exitValue(): Int = if (alive) throw IllegalThreadStateException() else 143
        override fun destroy() { destroyed = true; alive = false; completed.countDown() }
        override fun destroyForcibly(): Process { destroy(); return this }
        override fun isAlive(): Boolean = alive
    }
}
