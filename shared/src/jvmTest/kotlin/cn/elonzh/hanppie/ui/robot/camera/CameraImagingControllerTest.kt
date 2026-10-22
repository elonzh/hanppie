package cn.elonzh.hanppie.ui.robot.camera

import cn.elonzh.hanppie.robot.media.*
import cn.elonzh.hanppie.robot.session.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

internal class ImagingCameraFixture : RobotCameraService {
    override val state = MutableStateFlow(CameraState(100, timeSynced = true,
        storage = CameraStorageStatus(true, 0, 1000, 500, 200, 300, false, false, 0), workMode = 0,
        imaging = CameraImagingStatus(1, 0, 0, 0)))
    val requests = CopyOnWriteArrayList<String>()
    var photoGate: CompletableDeferred<Unit>? = null
    var rateGate: CompletableDeferred<Unit>? = null
    var failure: RequestProblem? = null
    override suspend fun setPhotoSize(size: CameraPhotoSize) {
        requests += "photo:${size.code}"
        withContext(NonCancellable) { photoGate?.await() }
        failure?.let { throw RobotRequestException(it) }
        state.value = state.value.copy(imaging = state.value.imaging?.copy(photoSize = size.code))
    }
    override suspend fun switchCamera(index: Int) {
        requests += "camera:$index"
        failure?.let { throw RobotRequestException(it) }
        state.value = state.value.copy(imaging = state.value.imaging?.copy(cameraIndex = index))
    }
    override suspend fun setTransmissionRate(rate: VideoTransmissionRate) {
        requests += "rate:${rate.kbps}"
        withContext(NonCancellable) { rateGate?.await() }
        failure?.let { throw RobotRequestException(it) }
    }
    override suspend fun requestKeyFrame() {
        requests += "keyframe"
        failure?.let { throw RobotRequestException(it) }
    }
    override suspend fun formatStorage() { requests += "format" }
    override suspend fun takePhoto() { requests += "capture" }
    override suspend fun setRecording(active: Boolean) { requests += "record:$active" }
    override suspend fun getAntiFlicker() = 2
    override suspend fun setAntiFlicker(mode: AntiFlicker) = mode.wireValue
    override suspend fun getDate() = CameraDate(2026, 10, 23, 1, 2, 3)
    override suspend fun syncClock() = getDate()
    override suspend fun setZoom(hundredths: Int) = error("unused")
}

class CameraImagingControllerTest {
    @Test fun pushedImagingStateRemainsAuthoritativeWhileRequestResultsAreIndependent(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val camera = ImagingCameraFixture()
            val controller = RobotCameraController(scope)
            controller.attach(camera)
            controller.setPhotoSize(CameraPhotoSize.R1440P)
            assertEquals(CameraPhotoSize.R1440P, controller.state.value.imaging?.photo)
            controller.switchCamera(1)
            assertEquals(1, controller.state.value.imaging?.cameraIndex)
            val before = controller.state.value.imaging
            controller.setTransmissionRate(VideoTransmissionRate.BEST)
            assertEquals(CameraRequest.Value(VideoTransmissionRate.BEST), controller.state.value.transmissionRate)
            controller.requestKeyFrame()
            assertEquals(CameraRequest.Value(Unit), controller.state.value.keyFrame)
            assertEquals(before, controller.state.value.imaging)
            camera.state.value = CameraState()
            assertNull(controller.state.value.imaging)
            controller.clear()
            assertEquals(CameraRequest.Idle, controller.state.value.transmissionRate)
        } finally { scope.cancel() }
    }

    @Test fun cameraStorageTasksDoNotQueueAndOldCompletionsCannotMutateANewConnection(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gate = CompletableDeferred<Unit>()
        try {
            val old = ImagingCameraFixture().apply { photoGate = gate }
            val controller = RobotCameraController(scope)
            controller.attach(old); controller.setPhotoSize(CameraPhotoSize.R1440P)
            assertTrue(controller.state.value.storageBusy)
            controller.formatStorage(); controller.capture(CaptureAction.PHOTO); controller.switchCamera(1)
            assertEquals(listOf("photo:2"), old.requests.toList())
            val jobs = scope.coroutineContext[Job]!!.children.toList()
            val fresh = ImagingCameraFixture()
            controller.attach(fresh)
            gate.complete(Unit); jobs.forEach { it.join() }
            assertEquals(CameraRequest.Idle, controller.state.value.photoSize)
            assertEquals(CameraPhotoSize.R1080P, controller.state.value.imaging?.photo)
            assertFalse(controller.state.value.storageBusy)
            assertTrue(fresh.requests.isEmpty())
        } finally { gate.complete(Unit); scope.cancel() }
    }

    @Test fun videoRequestsSerializeWithoutReplayAndFailureAllowsExplicitRetry(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gate = CompletableDeferred<Unit>()
        try {
            val camera = ImagingCameraFixture().apply { rateGate = gate }
            val controller = RobotCameraController(scope)
            controller.attach(camera); controller.setTransmissionRate(VideoTransmissionRate.GOOD); controller.requestKeyFrame()
            assertEquals(listOf("rate:3400"), camera.requests.toList())
            gate.complete(Unit)
            assertEquals(CameraRequest.Value(VideoTransmissionRate.GOOD), controller.state.value.transmissionRate)
            camera.failure = RequestProblem.TIMED_OUT
            controller.requestKeyFrame(); assertIs<CameraRequest.Failed>(controller.state.value.keyFrame)
            camera.failure = null
            controller.requestKeyFrame(); assertEquals(CameraRequest.Value(Unit), controller.state.value.keyFrame)
            controller.attach(camera)
            assertEquals(CameraRequest.Idle, controller.state.value.keyFrame)
            assertEquals(CameraRequest.Idle, controller.state.value.transmissionRate)
            assertEquals(listOf("rate:3400", "keyframe", "keyframe"), camera.requests.toList())
        } finally { gate.complete(Unit); scope.cancel() }
    }
}
