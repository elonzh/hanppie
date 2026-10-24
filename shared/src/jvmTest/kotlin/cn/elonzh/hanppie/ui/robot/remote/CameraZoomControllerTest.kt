package cn.elonzh.hanppie.ui.robot.remote

import cn.elonzh.hanppie.robot.media.*
import cn.elonzh.hanppie.robot.session.*
import cn.elonzh.hanppie.ui.app.RobotSessionFixture
import cn.elonzh.hanppie.ui.app.testConsoleModel
import kotlinx.coroutines.*
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class CameraZoomControllerTest {
    @Test fun concurrentCallersAcquireOnlyOneCameraOperation(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val pool = Executors.newFixedThreadPool(4); val start = CyclicBarrier(4)
        val peer = RobotSessionFixture(); val calls = AtomicInteger(0)
        val camera = object : RobotCameraService by peer.camera {
            override suspend fun setZoom(hundredths: Int, smooth: Boolean) { calls.incrementAndGet(); awaitCancellation() }
        }
        val controller = CameraZoomController(scope)
        try {
            controller.attach(camera)
            val requests = (1..4).map { pool.submit { start.await(); controller.setZoom(400) } }
            requests.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, calls.get()); assertTrue(controller.state.value.busy)
            controller.cancelTransition()
            withTimeout(5_000) { while (controller.state.value.busy) yield() }
            assertNull(controller.state.value.error)
        } finally { controller.clear(); scope.cancel(); pool.shutdownNow() }
    }

    @Test fun smoothingKeepsReportedValuesSeparateFromTheTargetAndRejectsOverlappingRequests(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val peer = RobotSessionFixture(); val finish = CompletableDeferred<Unit>(); val requests = mutableListOf<Int>()
        val camera = object : RobotCameraService by peer.camera {
            override suspend fun setZoom(hundredths: Int, smooth: Boolean) {
                assertTrue(smooth); requests += hundredths
                peer.cameraState.value = CameraState(200)
                finish.await(); peer.cameraState.value = CameraState(hundredths)
            }
        }
        val controller = CameraZoomController(scope)
        try {
            controller.attach(camera); controller.setZoom(400)
            assertEquals(200, controller.state.value.hundredths); assertTrue(controller.state.value.busy)
            controller.setZoom(100); assertEquals(listOf(400), requests)
            finish.complete(Unit)
            assertEquals(CameraZoomState(400), controller.state.value.copy(connection = 0))
        } finally { controller.clear(); scope.cancel() }
    }

    @Test fun cancellationHoldsOwnershipUntilRequestCleanupFinishes(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val peer = RobotSessionFixture(); val cleanup = CompletableDeferred<Unit>(); var requests = 0
        val camera = object : RobotCameraService by peer.camera {
            override suspend fun setZoom(hundredths: Int, smooth: Boolean) {
                requests++
                if (requests == 1) try { awaitCancellation() }
                finally { withContext(NonCancellable) { cleanup.await() } }
                else peer.cameraState.value = CameraState(hundredths)
            }
        }
        val controller = CameraZoomController(scope)
        try {
            controller.attach(camera); controller.setZoom(400); controller.cancelTransition()
            assertTrue(controller.state.value.busy)
            controller.setZoom(100); assertEquals(1, requests)
            cleanup.complete(Unit); assertFalse(controller.state.value.busy); assertNull(controller.state.value.error)
            controller.setZoom(100); assertEquals(2, requests)
        } finally { cleanup.complete(Unit); controller.clear(); scope.cancel() }
    }

    @Test fun reattachingTheSameServiceRejectsOldResultsAndErrors(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val peer = RobotSessionFixture(); val late = CompletableDeferred<Unit>(); var requests = 0
        val camera = object : RobotCameraService by peer.camera {
            override suspend fun setZoom(hundredths: Int, smooth: Boolean) {
                requests++
                if (requests == 1) withContext(NonCancellable) {
                    late.await(); throw RobotRequestException(RequestProblem.REJECTED)
                } else peer.cameraState.value = CameraState(hundredths)
            }
        }
        val controller = CameraZoomController(scope)
        try {
            controller.attach(camera); controller.setZoom(400)
            controller.attach(camera); controller.setZoom(100)
            assertEquals(CameraZoomState(100), controller.state.value.copy(connection = 0))
            late.complete(Unit); yield()
            assertEquals(CameraZoomState(100), controller.state.value.copy(connection = 0))
        } finally { late.complete(Unit); controller.clear(); scope.cancel() }
    }

    @Test fun failureKeepsThePartialDeviceReportAndAllowsExplicitRecovery(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val peer = RobotSessionFixture(); var fail = true
        val camera = object : RobotCameraService by peer.camera {
            override suspend fun setZoom(hundredths: Int, smooth: Boolean) {
                if (fail) {
                    peer.cameraState.value = CameraState(200)
                    throw RobotRequestException(RequestProblem.REJECTED)
                }
                peer.cameraState.value = CameraState(hundredths)
            }
        }
        val controller = CameraZoomController(scope)
        try {
            controller.attach(camera); controller.setZoom(400)
            assertFalse(controller.state.value.busy); assertNotNull(controller.state.value.error)
            assertEquals(200, controller.state.value.hundredths)
            fail = false; controller.setZoom(100)
            assertEquals(CameraZoomState(100), controller.state.value.copy(connection = 0))
        } finally { controller.clear(); scope.cancel() }
    }

    @Test fun haltingRemoteCancelsItsOutstandingZoomOperation(): Unit = runBlocking {
        val model = testConsoleModel(); val peer = RobotSessionFixture()
        val entered = CompletableDeferred<Unit>(); val stopped = CompletableDeferred<Unit>()
        val camera = object : RobotCameraService by peer.camera {
            override suspend fun setZoom(hundredths: Int, smooth: Boolean) {
                try { entered.complete(Unit); awaitCancellation() } finally { stopped.complete(Unit) }
            }
        }
        try {
            model.remoteEnabled.value = true; model.cameraZoom.attach(camera); model.cameraZoom.setZoom(400)
            withTimeout(5_000) { entered.await() }
            model.haltRemote(); withTimeout(5_000) { stopped.await(); while (model.cameraZoom.state.value.busy) yield() }
            assertFalse(model.remoteEnabled.value); assertNull(model.cameraZoom.state.value.error)
        } finally { model.close() }
    }
}
