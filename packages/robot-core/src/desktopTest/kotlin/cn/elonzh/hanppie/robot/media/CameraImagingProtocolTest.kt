package cn.elonzh.hanppie.robot.media

import cn.elonzh.hanppie.robot.protocol.*
import cn.elonzh.hanppie.robot.session.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlin.test.*

class CameraImagingProtocolTest {
    @Test fun decodesNativeFormatsPresenceAndIndexWithoutInventingUnknownValues(): Unit = runBlocking {
        var clock = 1L
        val camera = CameraCommands({ _, _, _, _ -> error("No queries for pushed fields") }) { clock }
        camera.receive(status(video = 1, photo = 2, sources = 1))
        assertEquals(VideoResolution.R1080P, camera.state.value.imaging?.resolution)
        assertEquals(CameraPhotoSize.R1440P, camera.state.value.imaging?.photo)
        assertEquals(listOf(0), camera.state.value.imaging?.availableCameras)
        camera.receive(status(sources = 2, index = 1))
        assertEquals(listOf(1), camera.state.value.imaging?.availableCameras)
        assertEquals(1, camera.state.value.imaging?.cameraIndex)
        for (source in listOf(0, 3)) {
            camera.receive(status(sources = source, video = 15, photo = 15, index = 3))
            assertEquals(listOf(0, 1), camera.state.value.imaging?.availableCameras)
            assertNull(camera.state.value.imaging?.resolution); assertNull(camera.state.value.imaging?.photo)
            assertEquals(3, camera.state.value.imaging?.cameraIndex)
        }
        clock += 1500; camera.expire(); assertNull(camera.state.value.imaging)
        camera.close(); camera.receive(status()); assertNull(camera.state.value.imaging)
    }

    @Test fun photoDimensionsUseNativeCodesAndRequireFreshPostRequestConfirmation(): Unit = runBlocking {
        lateinit var camera: CameraCommands
        val sent = mutableListOf<String>()
        var confirm = true
        camera = CameraCommands({ receiver, set, id, payload ->
            assertEquals(1, receiver); assertEquals(2, set); assertEquals(0x12, id)
            sent += payload.hex()
            if (confirm) camera.receive(status(photo = payload.u8(0)))
            byteArrayOf(0)
        }) { 1 }
        camera.receive(status())
        for (size in CameraPhotoSize.entries) camera.setPhotoSize(size)
        assertEquals(listOf("0001", "0101", "0201"), sent)
        confirm = false
        // Even an already matching cached size is not confirmation of the new command.
        assertEquals(RequestProblem.NOT_APPLIED,
            assertFailsWith<RobotRequestException> { camera.setPhotoSize(CameraPhotoSize.R1440P) }.problem)
        confirm = true; camera.setPhotoSize(CameraPhotoSize.R720P)
    }

    @Test fun sourceSwitchRequiresReportedHardwareAndReleasesStorageAccessAfterFailure(): Unit = runBlocking {
        val access = Mutex()
        var clock = 1L
        var rejected = false
        val sent = mutableListOf<String>()
        lateinit var camera: CameraCommands
        camera = CameraCommands({ receiver, set, id, payload ->
            assertEquals(1, receiver); assertEquals(2, set); assertEquals(0xe3, id)
            sent += payload.hex()
            if (rejected) throw RobotRequestException(RequestProblem.REJECTED)
            camera.receive(status(index = payload.u8(0))); byteArrayOf(0)
        }, storageAccess = access) { clock }
        assertFailsWith<IllegalStateException> { camera.switchCamera(0) }
        camera.receive(status(sources = 1))
        assertFailsWith<IllegalStateException> { camera.switchCamera(1) }
        camera.receive(status()); access.lock()
        assertFailsWith<IllegalStateException> { camera.switchCamera(1) }
        access.unlock()
        for (frame in listOf(status(extraFlags = 0x1000), status(shooting = true), status(cardCode = 5))) {
            camera.receive(frame)
            assertFailsWith<IllegalStateException> { camera.switchCamera(1) }
            assertFailsWith<IllegalStateException> { camera.setPhotoSize(CameraPhotoSize.R720P) }
        }
        camera.receive(status()); clock += 1500
        assertFailsWith<IllegalStateException> { camera.switchCamera(0) }
        camera.receive(status()); rejected = true
        assertEquals(RequestProblem.REJECTED, assertFailsWith<RobotRequestException> { camera.switchCamera(1) }.problem)
        assertFalse(access.isLocked)
        rejected = false; camera.switchCamera(1); camera.switchCamera(0)
        assertEquals(listOf("01", "01", "00"), sent)
        assertFailsWith<IllegalArgumentException> { camera.switchCamera(2) }
    }

    @Test fun rateAndKeyFrameTargetDifferentNativeNodesAndDoNotQueryAnUnprovenGetter(): Unit = runBlocking {
        val sent = mutableListOf<String>()
        var problem: RequestProblem? = null
        val camera = CameraCommands({ receiver, set, id, payload ->
            sent += "$receiver/$set/$id/${payload.hex()}"
            problem?.let { throw RobotRequestException(it) }
            byteArrayOf(0)
        }) { 1 }
        for (rate in VideoTransmissionRate.entries) camera.setTransmissionRate(rate)
        camera.requestKeyFrame()
        assertEquals(listOf("1/8/32/6009", "1/8/32/480d", "1/8/32/7017", "14/1/1/00000000002000000020"), sent)
        for (failure in listOf(RequestProblem.REJECTED, RequestProblem.TIMED_OUT)) {
            problem = failure
            assertEquals(failure, assertFailsWith<RobotRequestException> { camera.requestKeyFrame() }.problem)
        }
        camera.close()
        assertEquals(RequestProblem.DISCONNECTED,
            assertFailsWith<RobotRequestException> { camera.setTransmissionRate(VideoTransmissionRate.BEST) }.problem)
        assertEquals(RequestProblem.DISCONNECTED, assertFailsWith<RobotRequestException> { camera.requestKeyFrame() }.problem)
        assertEquals(6, sent.size)
    }

    @Test fun pendingCameraConfigurationBlocksCaptureAndCancelOrCloseReleasesIt(): Unit = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val camera = CameraCommands({ _, _, _, _ -> gate.await(); byteArrayOf(0) }) { 1 }
        camera.receive(status())
        val pending = async { camera.setPhotoSize(CameraPhotoSize.R1440P) }; yield()
        assertFailsWith<IllegalStateException> { camera.takePhoto() }
        pending.cancelAndJoin()
        val switch = async { runCatching { camera.switchCamera(1) } }; yield()
        gate.complete(Unit); yield()
        camera.close()
        assertEquals(RequestProblem.DISCONNECTED, (switch.await().exceptionOrNull() as RobotRequestException).problem)
    }
}

private fun status(video: Int = 0, photo: Int = 0, sources: Int = 0, index: Int = 0, extraFlags: Int = 0,
    shooting: Boolean = false, cardCode: Int = 0) =
    DussFrame(0, 1, 2, 1, 0, 2, 0xe4, ByteArray(26).apply {
        put16(5, (video shl 8) or (sources shl 4) or (index shl 2) or 0x40 or extraFlags)
        this[7] = (photo or if (shooting) 0x10 else 0).toByte(); this[2] = (0x10 or cardCode).toByte(); put16(8, 100)
    }, true)
