package cn.elonzh.hanppie.ui.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import cn.elonzh.hanppie.robot.files.RobotFileEntry
import cn.elonzh.hanppie.robot.files.RobotFileKind
import cn.elonzh.hanppie.robot.files.RobotFileService
import cn.elonzh.hanppie.robot.lab.LabAudioClip
import cn.elonzh.hanppie.robot.lab.LabUpload
import cn.elonzh.hanppie.robot.lab.ScriptRunPhase
import cn.elonzh.hanppie.robot.product.RobotModel
import cn.elonzh.hanppie.robot.product.RobotProduct
import cn.elonzh.hanppie.robot.protocol.DiscoveredRobot
import cn.elonzh.hanppie.robot.protocol.DussFrame
import cn.elonzh.hanppie.robot.session.RobotLabSession
import cn.elonzh.hanppie.robot.session.RobotRuntime
import cn.elonzh.hanppie.robot.session.RobotSession
import cn.elonzh.hanppie.robot.session.RobotTarget
import cn.elonzh.hanppie.robot.session.RouterPairing
import cn.elonzh.hanppie.ui.design.WorkbenchGlyph
import cn.elonzh.hanppie.ui.design.WorkbenchTheme
import cn.elonzh.hanppie.ui.i18n.Localization
import cn.elonzh.hanppie.ui.robot.remote.RemotePage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.Sink
import kotlinx.io.Source
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteFireUiTest {
    @get:Rule val rule = createComposeRule()

    private class FakeRobotSession : RobotSession {
        val infraredCount = AtomicInteger(0)
        val gelCount = AtomicInteger(0)
        val driveCount = AtomicInteger(0)
        val labStopCount = AtomicInteger(0)

        override val connected = true
        override val product = RobotProduct(model = RobotModel.ROBOMASTER_S1)
        override val cameraYaw: Double? = null
        override var onVideo: ((ByteArray) -> Unit)? = null
        override var onAudio: ((ByteArray) -> Unit)? = null
        override val lab: RobotLabSession = object : RobotLabSession {
            override fun invalidateMode() {}
            override suspend fun upload(source: String, title: String, audio: List<LabAudioClip>): LabUpload =
                LabUpload("hash", "0123456789abcdef0123456789abcdef")
            override suspend fun start(): String = "0123456789abcdef0123456789abcdef"
            override suspend fun stop() { labStopCount.incrementAndGet() }
            override suspend fun complete(runId: String): Boolean = true
        }
        override val files: RobotFileService = object : RobotFileService {
            override suspend fun list(path: String): List<RobotFileEntry> = emptyList()
            override suspend fun upload(directory: String, preferredName: String, source: Source): RobotFileEntry =
                RobotFileEntry(path = "$directory/$preferredName", name = preferredName, kind = RobotFileKind.FILE, size = 0)
            override suspend fun download(path: String, destination: Sink) {}
            override suspend fun createDirectory(parent: String, name: String): RobotFileEntry =
                RobotFileEntry(path = "$parent/$name", name = name, kind = RobotFileKind.DIRECTORY, size = 0)
            override suspend fun rename(path: String, newName: String): String = "$path/$newName"
            override suspend fun delete(entry: RobotFileEntry) {}
            override fun close() {}
        }

        override suspend fun connect() {}
        override fun safetyStop() {}
        override suspend fun enterRemote() {}
        override suspend fun exitRemote() {}
        override fun drive(x: Double, y: Double, z: Double, pitch: Double, yaw: Double, cameraRelative: Boolean) {
            driveCount.incrementAndGet()
        }
        override fun halt() {}
        override fun fireInfrared() {
            infraredCount.incrementAndGet()
        }
        override suspend fun fireGelOnce(): Int {
            delay(50)
            return gelCount.incrementAndGet()
        }
        override suspend fun playSpeaker(encoded: ByteArray): Int = 0
        override fun setLed(red: Int, green: Int, blue: Int, enabled: Boolean) {}
        override fun setSpeakerVolume(volume: Int) {}
        override fun media(start: Boolean, audio: Boolean, resolution: cn.elonzh.hanppie.robot.media.VideoResolution) {}
        override fun close() {}
    }

    private class FakeRobotRuntime(val fakeSession: FakeRobotSession) : RobotRuntime {
        override suspend fun discover(timeoutMillis: Long): List<DiscoveredRobot> = emptyList()
        override suspend fun waitForRouterPairing(appId: String): RouterPairing = error("unused")
        override suspend fun acknowledgeRouterPairing(pairing: RouterPairing, appId: String) {}
        override fun open(
            target: RobotTarget,
            onFrame: (DussFrame) -> Unit,
            onLog: (String) -> Unit,
            onLost: (RobotSession, String) -> Unit,
        ): RobotSession = fakeSession
    }

    @Test
    fun missingOnboardStartReportBecomesUnknownInsteadOfWaitingForever() = runBlocking {
        val session = FakeRobotSession()
        val model = testConsoleModel(
            robotRuntime = FakeRobotRuntime(session),
            scriptStartConfirmationTimeoutMillis = 25,
        )
        try {
            model.connect("127.0.0.1", "12345678")
            withTimeout(5_000) {
                while (!model.state.value.connected || model.state.value.busy) delay(10)
            }

            model.runScript("def start():\n    pass\n", "No report")
            withTimeout(2_000) {
                while (model.state.value.scriptRunPhase != ScriptRunPhase.UNKNOWN) delay(10)
            }

            assertEquals(
                "未收到机内 STARTED 回报，运行状态未知；请检查或停止脚本后再重试",
                model.state.value.scriptStatus,
            )
            assertTrue(model.state.value.canStop)
            assertTrue(model.state.value.scriptMessages.any {
                it.contains("启动确认超时")
            })
        } finally {
            model.close()
        }
    }

    @Test
    fun stopCommandKeepsTheRobotRunStateUnconfirmed() = runBlocking {
        val session = FakeRobotSession()
        val model = testConsoleModel(robotRuntime = FakeRobotRuntime(session))
        try {
            model.connect("127.0.0.1", "12345678")
            withTimeout(5_000) {
                while (!model.state.value.connected || model.state.value.busy) delay(10)
            }
            model.state.value = model.state.value.copy(
                scriptRunPhase = ScriptRunPhase.RUNNING,
                scriptFinishedAtEpochMillis = 123,
            )

            model.stop()
            withTimeout(5_000) {
                while (model.state.value.busy || session.labStopCount.get() == 0) delay(10)
            }

            assertEquals(1, session.labStopCount.get())
            assertEquals(ScriptRunPhase.STOP_UNCONFIRMED, model.state.value.scriptRunPhase)
            assertEquals("停止命令已发送；未获得机内停止确认。", model.state.value.scriptStatus)
            assertNull(model.state.value.scriptFinishedAtEpochMillis)
            assertTrue(model.state.value.canRun("def start():\n    pass\n"), "A delivered stop command must not block the next run")
            assertFalse(model.state.value.canStop)
        } finally {
            model.close()
        }
    }

    @Test
    fun infraredHoldFiresRepeatedlyAndStops() = runBlocking {
        val session = FakeRobotSession()
        val runtime = FakeRobotRuntime(session)
        val model = testConsoleModel(robotRuntime = runtime)
        try {
            model.connect("127.0.0.1", "12345678")
            withTimeout(5_000) {
                while (!model.state.value.connected || model.state.value.busy) delay(10)
            }
            model.enableRemote()
            withTimeout(5_000) {
                while (!model.remoteEnabled.value || model.state.value.busy) delay(10)
            }

            model.startFiring()
            delay(450)
            model.stopFiring()
            val shots = session.infraredCount.get()
            assertTrue(shots >= 2, "Expected multiple infrared shots while held, got $shots")

            val captured = session.infraredCount.get()
            delay(300)
            assertEquals(captured, session.infraredCount.get(), "Firing must stop after stopFiring")
        } finally {
            model.close()
        }
    }

    @Test
    fun gelHoldFiresSequentiallyWithoutBlockingDrive() = runBlocking {
        val session = FakeRobotSession()
        val runtime = FakeRobotRuntime(session)
        val model = testConsoleModel(robotRuntime = runtime)
        try {
            model.connect("127.0.0.1", "12345678")
            withTimeout(5_000) {
                while (!model.state.value.connected || model.state.value.busy) delay(10)
            }
            model.enableRemote()
            withTimeout(5_000) {
                while (!model.remoteEnabled.value || model.state.value.busy) delay(10)
            }
            model.switchAmmo() // switch to gel
            assertTrue(model.gelSelected.value)

            model.startFiring()
            delay(120)
            // Drive should not be blocked while firing gel!
            model.drive(1.0, 0.0, 0.0, 0.0, 0.0)
            assertEquals(1.0, model.remoteInput.value[0])
            assertTrue(session.driveCount.get() > 0, "Drive must execute while firing gel")
            assertFalse(model.state.value.busy, "Gel firing must not set state.busy to true")

            delay(100)
            model.stopFiring()
            val beads = session.gelCount.get()
            assertTrue(beads >= 2, "Expected multiple gel beads while held, got $beads")

            delay(150)
            val finalBeads = session.gelCount.get()
            delay(200)
            assertEquals(finalBeads, session.gelCount.get(), "Gel firing must stop after stopFiring")
        } finally {
            model.close()
        }
    }

    @Test
    fun remotePageSpaceKeyHoldAndRelease() {
        Localization.initialize("zh", null)
        val session = FakeRobotSession()
        val runtime = FakeRobotRuntime(session)
        val model = testConsoleModel(robotRuntime = runtime)
        try {
            rule.setContent {
                WorkbenchTheme {
                    Box(Modifier.requiredSize(1040.dp, 760.dp)) {
                        RemotePage(model)
                    }
                }
            }
            model.connect("127.0.0.1", "12345678")
            rule.waitUntil(5_000) { model.state.value.connected && !model.state.value.busy }
            model.enableRemote()
            rule.waitUntil(5_000) { model.remoteEnabled.value }
            rule.waitForIdle()

            // Press and hold Space key
            rule.onNodeWithTag("remote-surface").performKeyInput { keyDown(Key.Spacebar) }
            rule.waitUntil(5_000) { session.infraredCount.get() >= 2 }

            // Release Space key
            rule.onNodeWithTag("remote-surface").performKeyInput { keyUp(Key.Spacebar) }
            rule.waitForIdle()
            val countAfterRelease = session.infraredCount.get()
            Thread.sleep(300)
            rule.waitForIdle()
            assertEquals(countAfterRelease, session.infraredCount.get(), "Firing must stop after Space is released")
        } finally {
            model.close()
        }
    }

    @Test
    fun remotePageFireButtonClickFiresSingleShot() {
        Localization.initialize("zh", null)
        val session = FakeRobotSession()
        val runtime = FakeRobotRuntime(session)
        val model = testConsoleModel(robotRuntime = runtime)
        try {
            rule.setContent {
                WorkbenchTheme {
                    Box(Modifier.requiredSize(1040.dp, 760.dp)) {
                        RemotePage(model)
                    }
                }
            }
            model.connect("127.0.0.1", "12345678")
            rule.waitUntil(5_000) { model.state.value.connected && !model.state.value.busy }
            model.enableRemote()
            rule.waitUntil(5_000) { model.remoteEnabled.value && !model.state.value.busy }
            rule.onNodeWithTag("remote-surface").requestFocus()
            rule.waitForIdle()

            val fireButton = rule.onNodeWithContentDescription("红外开火")
            fireButton.assertIsDisplayed()

            val crosshair = rule.onNodeWithTag("remote-crosshair")
            crosshair.assertIsDisplayed()

            assertEquals(0, session.infraredCount.get())
            fireButton.performClick()
            rule.waitUntil(5_000) { session.infraredCount.get() == 1 }
            assertEquals(1, session.infraredCount.get())
        } finally {
            model.close()
        }
    }

    @Test
    fun singleFireEmitsCorrespondingFireEvents() = runBlocking {
        val session = FakeRobotSession()
        val runtime = FakeRobotRuntime(session)
        val model = testConsoleModel(robotRuntime = runtime)
        try {
            model.connect("127.0.0.1", "12345678")
            withTimeout(5_000) {
                while (!model.state.value.connected || model.state.value.busy) delay(10)
            }
            model.enableRemote()
            withTimeout(5_000) {
                while (!model.remoteEnabled.value || model.state.value.busy) delay(10)
            }

            val events = mutableListOf<AmmoType>()
            val job = launch {
                model.fireEvents.collect { events.add(it) }
            }
            delay(50)

            model.fire()
            withTimeout(2_000) {
                while (events.isEmpty()) delay(10)
            }
            assertEquals(listOf(AmmoType.INFRARED), events)

            model.switchAmmo()
            assertTrue(model.gelSelected.value)
            model.fireGel()
            withTimeout(2_000) {
                while (events.size < 2) delay(10)
            }
            assertEquals(listOf(AmmoType.INFRARED, AmmoType.GEL), events)

            job.cancel()
        } finally {
            model.close()
        }
    }

    @Test
    fun startFiringEmitsEventsAndUpdatesFiringState() = runBlocking {
        val session = FakeRobotSession()
        val runtime = FakeRobotRuntime(session)
        val model = testConsoleModel(robotRuntime = runtime)
        try {
            model.connect("127.0.0.1", "12345678")
            withTimeout(5_000) {
                while (!model.state.value.connected || model.state.value.busy) delay(10)
            }
            model.enableRemote()
            withTimeout(5_000) {
                while (!model.remoteEnabled.value || model.state.value.busy) delay(10)
            }

            assertFalse(model.firing.value)
            val events = mutableListOf<AmmoType>()
            val job = launch {
                model.fireEvents.collect { events.add(it) }
            }
            delay(50)

            model.startFiring()
            withTimeout(2_000) {
                while (!model.firing.value || events.isEmpty()) delay(10)
            }
            assertTrue(model.firing.value)
            assertTrue(events.all { it == AmmoType.INFRARED })

            model.stopFiring()
            withTimeout(2_000) {
                while (model.firing.value) delay(10)
            }
            assertFalse(model.firing.value)

            job.cancel()
        } finally {
            model.close()
        }
    }

    @Test
    fun hudIconButtonPressChangeTriggersOnDownAndUp() {
        val history = mutableListOf<Boolean>()
        rule.setContent {
            WorkbenchTheme {
                cn.elonzh.hanppie.ui.robot.remote.HudIconButton(
                    label = "Test Fire",
                    symbol = WorkbenchGlyph.CROSSHAIR,
                    enabled = true,
                    onPressChange = { history.add(it) }
                )
            }
        }
        val button = rule.onNodeWithContentDescription("Test Fire")
        button.performTouchInput {
            down(center)
            up()
        }
        rule.waitForIdle()
        assertEquals(listOf(true, false), history, "onPressChange must record press down and release")
    }

    @Test
    fun scriptFailureRecordsErrorDetailsInRunLogAndConsoleLog() = runBlocking {
        var frameHandler: ((DussFrame) -> Unit)? = null
        val session = FakeRobotSession()
        val runtime = object : RobotRuntime {
            override suspend fun discover(timeoutMillis: Long): List<DiscoveredRobot> = emptyList()
            override suspend fun waitForRouterPairing(appId: String): RouterPairing = error("unused")
            override suspend fun acknowledgeRouterPairing(pairing: RouterPairing, appId: String) {}
            override fun open(
                target: RobotTarget,
                onFrame: (DussFrame) -> Unit,
                onLog: (String) -> Unit,
                onLost: (RobotSession, String) -> Unit,
            ): RobotSession {
                frameHandler = onFrame
                return session
            }
        }
        val model = testConsoleModel(robotRuntime = runtime)
        try {
            model.connect("127.0.0.1", "12345678")
            withTimeout(5_000) {
                while (!model.state.value.connected || model.state.value.busy) delay(10)
            }
            model.runScript("def start():\n    pass\n", "Fail test")
            withTimeout(2_000) {
                while (model.state.value.scriptRunId == null) delay(10)
            }
            val runId = model.state.value.scriptRunId!!

            val errorMsg = "AttributeError: 'RobotTools' object has no attribute 'time'"
            val tbBytes = errorMsg.encodeToByteArray()
            val prefix = ByteArray(29) { 0 }
            val lenBytes = byteArrayOf((tbBytes.size and 0xFF).toByte(), ((tbBytes.size ushr 8) and 0xFF).toByte())
            val payload = byteArrayOf(5) + runId.encodeToByteArray() + prefix + lenBytes + tbBytes
            val frame = DussFrame(20, 9, 2, 1, 0, 0x3f, 0xa5, payload, true)
            frameHandler?.invoke(frame)

            withTimeout(5_000) {
                while (model.state.value.scriptRunPhase != ScriptRunPhase.FAILED) delay(10)
            }

            assertEquals(ScriptRunPhase.FAILED, model.state.value.scriptRunPhase)
            assertEquals("运行失败", model.state.value.scriptStatus)
            assertTrue(model.state.value.scriptMessages.contains(errorMsg), "Script messages must contain error message: ${model.state.value.scriptMessages}")
            assertTrue(model.state.value.logs.any { it.contains("运行失败") }, "Console logs must contain failure message: ${model.state.value.logs}")
        } finally {
            model.close()
        }
    }
}
