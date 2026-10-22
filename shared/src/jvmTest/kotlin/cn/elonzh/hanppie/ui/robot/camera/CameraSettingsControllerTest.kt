package cn.elonzh.hanppie.ui.robot.camera

import cn.elonzh.hanppie.robot.media.*
import cn.elonzh.hanppie.robot.session.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

class CameraSettingsControllerTest {
    private class Camera : RobotCameraService {
        override val state = MutableStateFlow(CameraState(100))
        var date = CameraDate(2020, 1, 1, 0, 0, 0)
        var dateError = false
        var syncError = false
        var readGate: CompletableDeferred<Unit>? = null
        var syncGate: CompletableDeferred<Unit>? = null
        var syncs = 0
        var formats = 0
        var formatError: Exception? = null
        var formatGate: CompletableDeferred<Unit>? = null
        var captures = 0
        var captureGate: CompletableDeferred<Unit>? = null
        var captureError: Exception? = null
        override suspend fun setPhotoSize(size: cn.elonzh.hanppie.robot.media.CameraPhotoSize) = error("unused")
        override suspend fun setTransmissionRate(rate: cn.elonzh.hanppie.robot.media.VideoTransmissionRate) = error("unused")
        override suspend fun switchCamera(index: Int) = error("unused")
        override suspend fun requestKeyFrame() = error("unused")
        override suspend fun setZoom(hundredths: Int) = error("unused")
        override suspend fun takePhoto() {
            captures++
            withContext(NonCancellable) { captureGate?.await() }
            captureError?.let { throw it }
        }
        override suspend fun setRecording(active: Boolean) { takePhoto() }
        override suspend fun formatStorage() {
            formats++
            withContext(NonCancellable) { formatGate?.await() }
            formatError?.let { throw it }
        }
        override suspend fun getAntiFlicker(): Int = 2
        override suspend fun setAntiFlicker(mode: AntiFlicker): Int = mode.wireValue
        override suspend fun getDate(): CameraDate {
            val captured = date
            withContext(NonCancellable) { readGate?.await() }
            if (dateError) throw RobotRequestException(RequestProblem.INVALID_RESPONSE)
            return captured
        }
        override suspend fun syncClock(): CameraDate {
            syncs++
            withContext(NonCancellable) { syncGate?.await() }
            if (syncError) throw RobotRequestException(RequestProblem.TIMED_OUT)
            dateError = false
            date = CameraDate(2026, 10, 23, 0, 0, 0)
            return date
        }
    }

    @Test fun independentFieldsRemainUsableAndExplicitSyncCanRecoverADateFailure(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val camera = Camera().apply { dateError = true }
            val controller = RobotCameraController(scope)
            controller.attach(camera); controller.refresh()
            assertEquals(CameraRequest.Value(2), controller.state.value.antiFlicker)
            assertIs<CameraRequest.Failed>(controller.state.value.date)
            controller.setAntiFlicker(AntiFlicker.HZ_60)
            assertEquals(CameraRequest.Value(1), controller.state.value.antiFlicker)
            controller.syncClock()
            assertEquals(CameraRequest.Value(camera.date), controller.state.value.date)
        } finally { scope.cancel() }
    }

    @Test fun autoSyncRequiresAnExplicitUnsyncedStatusAndAttemptsOnlyOncePerConnection(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val camera = Camera().apply { syncError = true }
            val controller = RobotCameraController(scope)
            controller.attach(camera)
            camera.state.value = CameraState(100, 1, true)
            assertEquals(0, camera.syncs)
            camera.state.value = CameraState(100, 2, false)
            assertEquals(1, camera.syncs)
            assertIs<CameraRequest.Failed>(controller.state.value.date)
            repeat(5) { camera.state.value = CameraState(100, it + 3L, false) }
            assertEquals(1, camera.syncs)
            camera.syncError = false
            controller.syncClock(); assertEquals(2, camera.syncs)
            controller.clear(); controller.attach(camera)
            assertEquals(3, camera.syncs)
            assertEquals(CameraRequest.Value(camera.date), controller.state.value.date)
        } finally { scope.cancel() }
    }

    @Test fun serializesClockChangesWhileOtherSettingsCanLoad(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gate = CompletableDeferred<Unit>()
        try {
            val camera = Camera().apply { syncGate = gate }
            val controller = RobotCameraController(scope)
            controller.attach(camera)
            camera.state.value = CameraState(100, 1, false)
            controller.syncClock(); controller.refresh()
            repeat(3) { camera.state.value = CameraState(100, it + 2L, false) }
            assertEquals(1, camera.syncs)
            assertEquals(CameraRequest.Loading, controller.state.value.date)
            assertEquals(CameraRequest.Value(2), controller.state.value.antiFlicker)
            gate.complete(Unit); yield()
            assertEquals(CameraRequest.Value(camera.date), controller.state.value.date)
        } finally { gate.complete(Unit); scope.cancel() }
    }

    @Test fun lateRepliesCannotRestoreDataAfterTheSameServiceIsAttachedAgain(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gate = CompletableDeferred<Unit>()
        try {
            val camera = Camera().apply { readGate = gate }
            val controller = RobotCameraController(scope)
            controller.attach(camera); controller.refresh()
            val oldConnection = controller.state.value.connection
            controller.clear()
            camera.date = CameraDate(2027, 1, 1, 0, 0, 0); camera.readGate = null
            controller.attach(camera); controller.refresh()
            gate.complete(Unit); yield()
            assertTrue(controller.state.value.connection > oldConnection)
            assertEquals(CameraRequest.Value(camera.date), controller.state.value.date)
            controller.clear()
            assertEquals(CameraRequest.Idle, controller.state.value.date)
            assertNull(controller.state.value.timeSynced)
        } finally { gate.complete(Unit); scope.cancel() }
    }

    @Test fun formatReplyIsSeparateFromLiveStateAndCannotQueueDuplicateRequests(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gate = CompletableDeferred<Unit>()
        try {
            val camera = Camera().apply { formatGate = gate }
            val storage = CameraStorageStatus(true, 0, 1024, 512, 123, 456, false, false, 0)
            camera.state.value = CameraState(100, timeSynced = true, storage = storage)
            val controller = RobotCameraController(scope)
            controller.attach(camera); controller.formatStorage(); controller.formatStorage()
            assertEquals(1, camera.formats)
            assertEquals(CameraRequest.Loading, controller.state.value.formatRequest)
            controller.refresh(); assertIs<CameraRequest.Value<*>>(controller.state.value.date)
            gate.complete(Unit); yield()
            assertEquals(CameraRequest.Value(Unit), controller.state.value.formatRequest)
            assertEquals(storage, controller.state.value.storage)
            camera.state.value = camera.state.value.copy(storage = storage.copy(stateCode = 5))
            assertTrue(assertNotNull(controller.state.value.storage).formatting)
            camera.state.value = CameraState()
            assertNull(controller.state.value.storage)
        } finally { gate.complete(Unit); scope.cancel() }
    }

    @Test fun formatFailuresDoNotRetryAndLateAckCannotRestoreAnOldConnection(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gate = CompletableDeferred<Unit>()
        try {
            val camera = Camera().apply { formatError = RobotRequestException(RequestProblem.TIMED_OUT) }
            val controller = RobotCameraController(scope)
            controller.attach(camera); controller.formatStorage()
            assertIs<CameraRequest.Failed>(controller.state.value.formatRequest)
            repeat(3) { camera.state.value = CameraState(100, it + 1L, true) }
            assertEquals(1, camera.formats)
            camera.formatError = null; camera.formatGate = gate
            controller.formatStorage(); controller.clear(); controller.attach(camera)
            gate.complete(Unit); yield()
            assertEquals(CameraRequest.Idle, controller.state.value.formatRequest)
            assertNull(controller.state.value.storage)
            assertEquals(2, camera.formats)
        } finally { gate.complete(Unit); scope.cancel() }
    }

    @Test fun captureAndFormatShareOneOperationWithoutMutatingDeviceState(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gate = CompletableDeferred<Unit>()
        try {
            val camera = Camera().apply { captureGate = gate }
            val controller = RobotCameraController(scope)
            controller.attach(camera); controller.capture(CaptureAction.PHOTO)
            controller.capture(CaptureAction.START_RECORDING); controller.formatStorage()
            assertTrue(controller.state.value.storageBusy)
            assertEquals(1, camera.captures); assertEquals(0, camera.formats)
            gate.complete(Unit); yield()
            assertEquals(CameraRequest.Value(CaptureAction.PHOTO), controller.state.value.capture)
            assertNull(controller.state.value.storage)
            controller.formatStorage(); assertEquals(1, camera.formats)
            assertFalse(controller.state.value.storageBusy)
        } finally { gate.complete(Unit); scope.cancel() }
    }

    @Test fun failedCaptureDoesNotRetryAndRebindingDropsItsLateResult(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gate = CompletableDeferred<Unit>()
        try {
            val camera = Camera().apply { captureError = RobotRequestException(RequestProblem.TIMED_OUT) }
            val controller = RobotCameraController(scope)
            controller.attach(camera); controller.capture(CaptureAction.START_RECORDING)
            assertIs<CameraRequest.Failed>(controller.state.value.capture)
            repeat(3) { camera.state.value = CameraState(100, it + 1L, true) }
            assertEquals(1, camera.captures)
            camera.captureError = null; camera.captureGate = gate
            controller.capture(CaptureAction.STOP_RECORDING)
            controller.clear(); controller.attach(camera)
            gate.complete(Unit); yield()
            assertEquals(CameraRequest.Idle, controller.state.value.capture)
            assertEquals(2, camera.captures)
        } finally { gate.complete(Unit); scope.cancel() }
    }
}
