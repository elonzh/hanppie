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
        override suspend fun setZoom(hundredths: Int) = error("unused")
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
            val controller = CameraSettingsController(scope)
            controller.attach(camera); controller.refresh()
            assertEquals(CameraSetting.Value(2), controller.state.value.antiFlicker)
            assertIs<CameraSetting.Failed>(controller.state.value.date)
            controller.setAntiFlicker(AntiFlicker.HZ_60)
            assertEquals(CameraSetting.Value(1), controller.state.value.antiFlicker)
            controller.syncClock()
            assertEquals(CameraSetting.Value(camera.date), controller.state.value.date)
        } finally { scope.cancel() }
    }

    @Test fun autoSyncRequiresAnExplicitUnsyncedStatusAndAttemptsOnlyOncePerConnection(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val camera = Camera().apply { syncError = true }
            val controller = CameraSettingsController(scope)
            controller.attach(camera)
            camera.state.value = CameraState(100, 1, true)
            assertEquals(0, camera.syncs)
            camera.state.value = CameraState(100, 2, false)
            assertEquals(1, camera.syncs)
            assertIs<CameraSetting.Failed>(controller.state.value.date)
            repeat(5) { camera.state.value = CameraState(100, it + 3L, false) }
            assertEquals(1, camera.syncs)
            camera.syncError = false
            controller.syncClock(); assertEquals(2, camera.syncs)
            controller.clear(); controller.attach(camera)
            assertEquals(3, camera.syncs)
            assertEquals(CameraSetting.Value(camera.date), controller.state.value.date)
        } finally { scope.cancel() }
    }

    @Test fun serializesClockChangesWhileOtherSettingsCanLoad(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gate = CompletableDeferred<Unit>()
        try {
            val camera = Camera().apply { syncGate = gate }
            val controller = CameraSettingsController(scope)
            controller.attach(camera)
            camera.state.value = CameraState(100, 1, false)
            controller.syncClock(); controller.refresh()
            repeat(3) { camera.state.value = CameraState(100, it + 2L, false) }
            assertEquals(1, camera.syncs)
            assertEquals(CameraSetting.Loading, controller.state.value.date)
            assertEquals(CameraSetting.Value(2), controller.state.value.antiFlicker)
            gate.complete(Unit); yield()
            assertEquals(CameraSetting.Value(camera.date), controller.state.value.date)
        } finally { gate.complete(Unit); scope.cancel() }
    }

    @Test fun lateRepliesCannotRestoreDataAfterTheSameServiceIsAttachedAgain(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gate = CompletableDeferred<Unit>()
        try {
            val camera = Camera().apply { readGate = gate }
            val controller = CameraSettingsController(scope)
            controller.attach(camera); controller.refresh()
            val oldConnection = controller.state.value.connection
            controller.clear()
            camera.date = CameraDate(2027, 1, 1, 0, 0, 0); camera.readGate = null
            controller.attach(camera); controller.refresh()
            gate.complete(Unit); yield()
            assertTrue(controller.state.value.connection > oldConnection)
            assertEquals(CameraSetting.Value(camera.date), controller.state.value.date)
            controller.clear()
            assertEquals(CameraSetting.Idle, controller.state.value.date)
            assertNull(controller.state.value.timeSynced)
        } finally { gate.complete(Unit); scope.cancel() }
    }
}
