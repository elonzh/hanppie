package cn.elonzh.hanppie.ui

import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals

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
        val model = ConsoleModel(SystemSpeech(), speakerInput = input)
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
}
