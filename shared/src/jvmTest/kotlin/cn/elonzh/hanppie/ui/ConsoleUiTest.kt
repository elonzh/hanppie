package cn.elonzh.hanppie.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import cn.elonzh.hanppie.robot.DussFrame
import cn.elonzh.hanppie.resources.*
import androidx.compose.ui.input.key.Key

class ConsoleUiTest {
    @get:Rule val rule = createComposeRule()
    @Test fun languageSwitchPreservesDocumentAndLocalizesNavigation() {
        Localization.initialize("zh",null)
        val model=ConsoleModel()
        val document=mutableStateOf(EditorDocument(source="print('用户脚本')"))
        try {
            rule.setContent { WorkbenchTheme { Box(Modifier.requiredSize(393.dp,740.dp)) { Console(model,document) } } }
            rule.onNodeWithContentDescription("设置").performClick()
            rule.onNodeWithContentDescription("language-selector").performClick()
            rule.onNodeWithContentDescription("language-en").performClick()
            rule.onNodeWithText("Language").assertIsDisplayed()
            rule.onNode(hasContentDescription("Settings") and hasClickAction()).assertIsDisplayed()
            assertEquals("print('用户脚本')",document.value.source)
            snapshot("phone-settings-en")
            rule.onNodeWithContentDescription("Chat").performClick()
            rule.onNodeWithText("What would you like to do?").assertIsDisplayed()
            snapshot("phone-chat-en")
            rule.onNodeWithContentDescription("Script").performClick()
            snapshot("phone-script-en")
            rule.onNodeWithContentDescription("Debug").performClick()
            snapshot("phone-debug-en")
            rule.onNodeWithContentDescription("Settings").performClick()
            rule.onNodeWithContentDescription("language-selector").performClick()
            rule.onNodeWithContentDescription("language-zh").performClick()
            rule.onNodeWithText("语言").assertIsDisplayed()
        } finally { model.close(); Localization.initialize("zh",null) }
    }

    @Test fun englishCockpitAtPhoneAndDesktopSizes() {
        Localization.initialize("en",null)
        val model=ConsoleModel()
        val width=mutableStateOf(740.dp)
        try {
            model.state.value = ConsoleState(connected = true, battery = 82, signalQuality = 37,
                gimbal = cn.elonzh.hanppie.robot.GimbalTelemetry(42.0, 0.0, 42.0, 0.0, 0))
            rule.setContent { WorkbenchTheme { Box(Modifier.requiredSize(width.value,393.dp)) { RemotePage(model, onBack = {}) } } }
            rule.waitUntil(2000) { !model.state.value.busy }
            rule.runOnIdle { model.remoteEnabled.value = true }
            rule.waitForIdle()
            rule.onNodeWithContentDescription("Chassis joystick").assertIsDisplayed()
            rule.onNodeWithContentDescription("Gimbal joystick").assertIsDisplayed()
            rule.onNodeWithContentDescription("Enable remote control").assertDoesNotExist()
            rule.onNodeWithContentDescription("Switch ammo").assertIsDisplayed()
            rule.onNodeWithContentDescription("Stop video").assertIsDisplayed()
            rule.onNodeWithContentDescription("Take photo").assertIsDisplayed()
            rule.onNodeWithContentDescription("Start recording").assertIsDisplayed()
            rule.onNodeWithContentDescription("Gear 3").assertIsDisplayed()
            rule.onNodeWithContentDescription("Signal strength 37").assertIsDisplayed()
            rule.onNodeWithContentDescription("Chassis heading relative to camera -42°").assertIsDisplayed()
            snapshot("phone-cockpit-en")
            rule.runOnIdle { width.value=1040.dp }
            rule.waitForIdle()
            snapshot("desktop-cockpit-en")
        } finally { model.close(); Localization.initialize("zh",null) }
    }

    @Test fun gearKeysShiftDoesNotSlowAndStop() {
        val model=ConsoleModel()
        try {
            rule.setContent { WorkbenchTheme { Box(Modifier.requiredSize(1040.dp,760.dp)) { RemotePage(model) } } }
            rule.runOnIdle { model.remoteEnabled.value=true }
            rule.waitForIdle()
            fun tap(key: Key) { rule.onNodeWithTag("remote-surface").performKeyInput { keyDown(key); keyUp(key) } }
            tap(Key.One); rule.waitUntil(2000) { model.driveGear.value==1 }
            assertTrue(model.remoteInput.value.all { it==0.0 }, "Gear keys must not move or rotate")
            rule.onNodeWithTag("remote-surface").performKeyInput { keyDown(Key.Q) }
            rule.waitUntil(2000) { model.remoteInput.value[2]==-30.0 }
            rule.onNodeWithTag("remote-surface").performKeyInput { keyUp(Key.Q) }
            rule.waitUntil(2000) { model.remoteInput.value.all { it==0.0 } }
            tap(Key.NumPad5); rule.waitUntil(2000) { model.driveGear.value==5 }
            rule.onNodeWithTag("remote-surface").performKeyInput { keyDown(Key.E) }
            rule.waitUntil(2000) { model.remoteInput.value[2]==150.0 }
            rule.onNodeWithTag("remote-surface").performKeyInput { keyUp(Key.E) }
            rule.waitUntil(2000) { model.remoteInput.value.all { it==0.0 } }
            rule.onNodeWithTag("remote-surface").performKeyInput { keyDown(Key.W) }
            rule.waitUntil(2000) { model.remoteInput.value[0]==1.0 }
            rule.onNodeWithTag("remote-surface").performKeyInput { keyDown(Key.ShiftLeft) }
            rule.waitUntil(2000) { model.remoteInput.value[0]==1.0 }
            rule.onNodeWithTag("remote-surface").performKeyInput { keyDown(Key.ShiftRight); keyUp(Key.ShiftLeft) }
            rule.waitUntil(2000) { model.remoteInput.value[0]==1.0 }
            tap(Key.Three); rule.waitUntil(2000) { model.remoteInput.value[0]==.65 }
            assertEquals(3,model.driveGear.value)
            rule.onNodeWithTag("remote-surface").performKeyInput { keyUp(Key.ShiftRight) }
            rule.waitUntil(2000) { model.remoteInput.value[0]==.65 }
            rule.onNodeWithTag("remote-surface").performKeyInput { keyUp(Key.W) }
            rule.waitUntil(2000) { model.remoteInput.value.all { it==0.0 } }
            tap(Key.Escape)
            rule.waitUntil(2000) { model.remoteInput.value.all { it==0.0 } && !model.remoteEnabled.value }
        } finally { model.close() }
    }

    @Test fun phoneCockpitControlsFitWithoutScrolling() {
        val model=ConsoleModel()
        try {
            rule.setContent { WorkbenchTheme { Box(Modifier.requiredSize(640.dp,360.dp)) { RemotePage(model, onBack = {}) } } }
            rule.onNodeWithContentDescription("底盘 摇杆").assertIsDisplayed()
            rule.onNodeWithContentDescription("云台 摇杆").assertIsDisplayed()
            rule.onNodeWithContentDescription("启用遥控").assertDoesNotExist()
            rule.onNodeWithContentDescription("停止遥控").assertDoesNotExist()
            rule.onNodeWithContentDescription("3 档").assertIsDisplayed()
            rule.onNodeWithContentDescription("慢行").assertDoesNotExist()
            val chassis = rule.onNodeWithContentDescription("底盘 摇杆").fetchSemanticsNode().boundsInRoot
            val gimbal = rule.onNodeWithContentDescription("云台 摇杆").fetchSemanticsNode().boundsInRoot
            val gear = rule.onNodeWithContentDescription("3 档").fetchSemanticsNode().boundsInRoot
            assertTrue(chassis.center.x < 640f * .25f && gimbal.center.x > 640f * .75f)
            assertTrue(chassis.bottom <= 360f && gimbal.bottom <= 360f)
            assertTrue(gear.bottom <= chassis.top)
            assertTrue(gear.width >= 48f && gear.height >= 48f)
            rule.onNodeWithContentDescription("切换弹药").assertIsDisplayed().performClick()
            val media = rule.onNodeWithContentDescription("关闭视频").assertIsDisplayed()
                .fetchSemanticsNode().boundsInRoot
            val telemetry = rule.onNodeWithTag("remote-telemetry").fetchSemanticsNode().boundsInRoot
            assertTrue(telemetry.right <= media.left)
            rule.onNodeWithContentDescription("拍照").assertIsDisplayed()
            rule.onNodeWithContentDescription("开始录像").assertIsDisplayed()
            rule.onNodeWithContentDescription("水弹单发").assertIsDisplayed()
            snapshot("compact-phone-game-controls")
        } finally { model.close() }
    }

    @Test fun remoteKeyboardHeldInputAndRelease() {
        val model=ConsoleModel()
        try {
            rule.setContent { WorkbenchTheme { Box(Modifier.requiredSize(1040.dp,760.dp)) { RemotePage(model) } } }
            rule.runOnIdle { model.remoteEnabled.value=true }
            rule.waitForIdle()
            rule.onNodeWithTag("remote-surface").performKeyInput { keyDown(androidx.compose.ui.input.key.Key.W) }
            rule.waitUntil(3000) { model.remoteInput.value[0] > 0 }
            assertEquals(0.0,model.remoteInput.value[2])
            rule.onNodeWithTag("remote-surface").performKeyInput { keyUp(androidx.compose.ui.input.key.Key.W) }
            rule.waitUntil(3000) { model.remoteInput.value.all { it == 0.0 } }
            rule.onNodeWithTag("remote-surface").performKeyInput { keyDown(androidx.compose.ui.input.key.Key.G) }
            rule.waitUntil(3000) { model.gelSelected.value }
            rule.onNodeWithTag("remote-surface").performKeyInput { keyUp(androidx.compose.ui.input.key.Key.G) }
            rule.onNodeWithTag("remote-surface").performKeyInput { keyDown(androidx.compose.ui.input.key.Key.G); keyUp(androidx.compose.ui.input.key.Key.G) }
            rule.waitUntil(3000) { !model.gelSelected.value }
            rule.onNodeWithTag("remote-surface").performKeyInput { keyDown(androidx.compose.ui.input.key.Key.DirectionRight) }
            rule.waitUntil(3000) { model.remoteInput.value[4] > 0 }
            assertEquals(0.0,model.remoteInput.value[2])
            rule.onNodeWithTag("remote-surface").performKeyInput { keyUp(androidx.compose.ui.input.key.Key.DirectionRight) }
            rule.waitUntil(3000) { model.remoteInput.value.all { it == 0.0 } }
            snapshot("desktop-game-controls")
        } finally { model.close() }
    }

    @Test fun voiceRecognitionFillsDraftAndReplyReadingDoesNotReplayOnNavigation() {
        val spoken = mutableListOf<String>()
        val speech = object : SpeechEngine {
            override val state = kotlinx.coroutines.flow.MutableStateFlow(SpeechState("fixture", true))
            override fun speak(text: String) { spoken += text }
            override fun stop() {}
            override fun close() {}
        }
        val input = object : SpeechInput {
            override val state = kotlinx.coroutines.flow.MutableStateFlow(SpeechInputState())
            override fun start() { state.value = state.value.copy(active = true) }
            override fun finish() { state.value = state.value.copy(active = false, result = "看看连接状态", resultId = 1) }
            override fun cancel() { state.value = state.value.copy(active = false) }
            override fun consumeResult() { state.value = state.value.copy(result = "") }
            override fun close() { cancel() }
        }
        val model = ConsoleModel(speech, input)
        try {
            rule.setContent { WorkbenchTheme {
                Box(Modifier.requiredSize(393.dp, 740.dp)) {
                    Console(model, mutableStateOf(EditorDocument()), onVoiceInput = input::start)
                }
            } }
            rule.onNodeWithContentDescription("对话").performClick()
            rule.onNodeWithContentDescription("语音输入").performClick()
            rule.onNodeWithContentDescription("发送").assertIsNotEnabled()
            rule.onNodeWithContentDescription("结束录音").performClick()
            rule.waitUntil { rule.onAllNodesWithText("看看连接状态").fetchSemanticsNodes().isNotEmpty() }
            assertTrue(model.chat.state.value.lines.isEmpty())
            rule.onNodeWithContentDescription("设置").performClick()
            rule.onNode(isToggleable()).performScrollTo().performClick()
            rule.onNodeWithContentDescription("对话").performClick()
            rule.runOnIdle { model.chat.state.value = model.chat.state.value.copy(
                replyRevision = 1, lastReply = "目前未连接机器人。", lines = listOf(ChatLine(ChatRole.ASSISTANT, "目前未连接机器人。"))) }
            rule.waitUntil { spoken.size == 1 }
            rule.waitUntil(5000) { rule.onAllNodesWithText("目前未连接机器人。").fetchSemanticsNodes().isNotEmpty() }
            snapshot("phone-chat-voice")
            rule.onNodeWithContentDescription("设备").performClick()
            rule.onNodeWithContentDescription("对话").performClick()
            rule.waitForIdle()
            assertEquals(1, spoken.size)
            assertTrue(!input.state.value.active)
        } finally { model.close() }
    }

    @Test fun phoneLayoutAndEditorRemainUsable() {
        val model = ConsoleModel()
        try {
            rule.setContent { WorkbenchTheme {
                Box(Modifier.requiredSize(393.dp, 740.dp)) { Console(model, mutableStateOf(EditorDocument())) }
            } }
            rule.onNodeWithTag("bottom-navigation").assertIsDisplayed()
            rule.onNodeWithText("查找设备").assertIsDisplayed()
            snapshot("phone-device")
            rule.onNodeWithContentDescription("脚本").performClick()
            rule.onNodeWithTag("script-library").assertIsDisplayed()
            rule.onNodeWithTag("script-new").performClick()
            rule.onNodeWithTag("script-editor").assertIsDisplayed().performTextReplacement("def start():\n    pass")
            rule.onNodeWithTag("script-save").assertIsDisplayed()
            rule.onNodeWithText("运行脚本").assertIsDisplayed()
            snapshot("phone-script")
        } finally { model.close() }
    }

    @Test fun phoneChatLayoutShowsComposerAndConfiguration() {
        val model = ConsoleModel()
        try {
            rule.setContent { WorkbenchTheme {
                Box(Modifier.requiredSize(393.dp, 740.dp)) { Console(model, mutableStateOf(EditorDocument())) }
            } }
            rule.onNodeWithContentDescription("对话").performClick()
            rule.onNodeWithTag("chat-input").assertIsDisplayed()
            rule.onNodeWithContentDescription("发送").assertIsNotEnabled()
            snapshot("phone-chat")
            rule.onNodeWithContentDescription("设置").performClick()
            rule.onNodeWithText("API Key").performScrollTo().assertIsDisplayed()
            snapshot("phone-chat-settings")
        } finally { model.close() }
    }

    @Test fun streamingMarkdownAppendsAndStartsANewReply() {
        val model = ConsoleModel()
        try {
            model.chat.state.value = ChatState(running = true,
                lines = listOf(ChatLine(ChatRole.USER, "检查状态")), streaming = "## 连接状态\n\n电量 **88")
            rule.setContent { WorkbenchTheme { Box(Modifier.requiredSize(393.dp, 740.dp)) { ChatPage(model) } } }
            rule.waitUntil(5000) { rule.onAllNodesWithText("连接状态").fetchSemanticsNodes().size == 1 }
            rule.runOnIdle { model.chat.state.value = model.chat.state.value.copy(
                streaming = "## 连接状态\n\n电量 **88%**。\n\n- 已连接\n- 待机") }
            rule.waitUntil(5000) { rule.onAllNodesWithText("待机", substring = true).fetchSemanticsNodes().isNotEmpty() }
            rule.onAllNodesWithText("连接状态").assertCountEquals(1)
            snapshot("phone-markdown-stream")
            rule.runOnIdle {
                val old = model.chat.state.value
                model.chat.state.value = old.copy(lines = old.lines + ChatLine(ChatRole.ASSISTANT, old.streaming),
                    streaming = "## 下一步\n\n等待指令。")
            }
            rule.waitUntil(5000) { rule.onAllNodesWithText("下一步").fetchSemanticsNodes().size == 1 }
            rule.onAllNodesWithText("连接状态").assertCountEquals(1)
            rule.onAllNodesWithText("下一步").assertCountEquals(1)
        } finally { model.close() }
    }

    @Test fun keyboardHintsFollowInputAndTouchReleaseZerosMotion() {
        val model = ConsoleModel()
        try {
            rule.setContent { WorkbenchTheme { Box(Modifier.requiredSize(740.dp, 393.dp)) { RemotePage(model) } } }
            rule.onNodeWithTag("keyboard-hints").assertDoesNotExist()
            rule.runOnIdle { model.remoteEnabled.value = true }
            rule.waitForIdle()
            rule.onNodeWithTag("remote-surface").performKeyInput { keyDown(Key.W); keyUp(Key.W) }
            rule.onNodeWithTag("keyboard-hints").assertIsDisplayed()
            val stick = rule.onNodeWithContentDescription("底盘 摇杆")
            stick.performTouchInput { down(center); moveTo(center.copy(y = center.y - 40f)) }
            rule.waitUntil(2000) { model.remoteInput.value[0] > 0 }
            rule.onNodeWithTag("keyboard-hints").assertDoesNotExist()
            stick.performTouchInput { up() }
            rule.waitUntil(2000) { model.remoteInput.value.all { it == 0.0 } }
            rule.onNodeWithTag("remote-surface").performKeyInput { pressKey(Key.Escape) }
            rule.waitUntil(2000) { !model.remoteEnabled.value }
        } finally { model.close() }
    }

    @Test fun fixedAppearanceNightModePreservesEditor() {
        val model = ConsoleModel()
        val document = mutableStateOf(EditorDocument(source = "print('keep me')"))
        var saved: String? = null
        val appearance = AppearanceController(persist = { saved = it })
        try {
            rule.setContent { WorkbenchTheme(appearance) { Box(Modifier.requiredSize(393.dp, 740.dp)) { Console(model, document) } } }
            rule.onNodeWithContentDescription("设置").performClick()
            rule.onNodeWithContentDescription("theme-selector").assertDoesNotExist()
            rule.onNodeWithTag("palette-editor").assertDoesNotExist()
            rule.onNodeWithContentDescription("night-mode-selector").performClick()
            rule.onNodeWithContentDescription("night-mode-LIGHT").performClick()
            snapshot("appearance-light")
            rule.onNodeWithContentDescription("night-mode-selector").performClick()
            rule.onNodeWithContentDescription("night-mode-DARK").performClick()
            snapshot("appearance-dark")
            rule.runOnIdle {
                assertEquals(NightMode.DARK, appearance.settings.nightMode)
                assertEquals(appearance.settings, AppearanceSettings.decode(saved))
                assertEquals("print('keep me')", document.value.source)
                assertTrue(!model.state.value.connected)
            }
            rule.onNodeWithContentDescription("脚本").performClick()
            rule.onNodeWithTag("script-editor").assertIsDisplayed()
            rule.onNodeWithContentDescription("设置").performClick()
            rule.runOnIdle { assertEquals(NightMode.DARK, appearance.settings.nightMode) }
        } finally { model.close() }
    }

    @Test fun homepageActionsStayAlignedAcrossConnectionStates() {
        val model = ConsoleModel()
        val width = mutableStateOf(393.dp)
        try {
            rule.setContent { WorkbenchTheme { Box(Modifier.requiredSize(width.value, 740.dp)) {
                Console(model, mutableStateOf(EditorDocument()))
            } } }
            fun checkPair(first: String, second: String) {
                val a = rule.onNodeWithTag(first).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                val b = rule.onNodeWithTag(second).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertEquals(a.left, b.left)
                assertEquals(a.width, b.width)
                assertEquals(a.height, b.height)
                assertTrue(b.top > a.bottom)
            }
            checkPair("discover-robot", "manual-connect")
            snapshot("home-actions-phone")
            rule.runOnIdle { width.value = 1040.dp }
            checkPair("discover-robot", "manual-connect")
            snapshot("home-actions-desktop")
            rule.runOnIdle { model.state.value = model.state.value.copy(connected = true, connectedAddress = "192.0.2.1") }
            rule.waitUntil(3000) { rule.onAllNodesWithTag("disconnect-robot").fetchSemanticsNodes().isNotEmpty() }
            checkPair("enter-remote", "disconnect-robot")
            assertTrue(!model.remoteEnabled.value)
        } finally { model.close() }
    }

    private fun snapshot(name: String, node: SemanticsNodeInteraction = rule.onRoot()) {
        val image = node.captureToImage()
        val pixels = IntArray(image.width * image.height)
        image.readPixels(pixels)
        val buffered = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        buffered.setRGB(0, 0, image.width, image.height, pixels, 0, image.width)
        val output = File("build/reports/ui/$name.png")
        output.parentFile.mkdirs()
        ImageIO.write(buffered, "png", output)
    }

    @Test fun beginnerViewHidesParametersAndSpeechIsExplicit() {
        val model = ConsoleModel()
        try {
            rule.setContent { WorkbenchTheme { Console(model, mutableStateOf(EditorDocument())) } }
            rule.onNodeWithText("连接", substring = false).assertDoesNotExist()
            rule.onNodeWithText("语音", substring = false).assertDoesNotExist()
            rule.onNodeWithContentDescription("对话").performClick()
            rule.onNodeWithContentDescription("设置").performClick()
            rule.onNodeWithText("自动朗读").assertExists()
            rule.onNodeWithContentDescription("gimbal-sensitivity-selector").assertExists()
            val original = model.controlSettings.value.remoteLeds.standby
            rule.onNodeWithContentDescription("remote-led-standby").performScrollTo().performClick()
            rule.onNodeWithTag("led-brightness").performTouchInput {
                swipe(center.copy(x = width - 12f, y = height - 24f), center.copy(x = width * 0.5f, y = height - 24f))
            }
            rule.onNodeWithTag("led-color-apply").performClick()
            rule.runOnIdle { assertTrue(original != model.controlSettings.value.remoteLeds.standby) }
            rule.onNodeWithContentDescription("remote-led-talking").performScrollTo().assertExists()
            rule.onNodeWithContentDescription("restore-default-settings").performScrollTo().assertIsEnabled().performClick()
            rule.onNodeWithText("恢复所有默认设置？").assertIsDisplayed()
            rule.onNodeWithText("取消").performClick()
            rule.onNodeWithText("恢复所有默认设置？").assertDoesNotExist()
        } finally { model.close() }
    }

    @Test fun lostConnectionCanBeCleanedUp() {
        val model = ConsoleModel()
        try {
            model.state.value = model.state.value.lost("fixture timeout").copy(
                reconnecting = true, statusMessage = uiText(Res.string.reconnecting_attempt_value, 2))
            rule.setContent { WorkbenchTheme { Console(model, mutableStateOf(EditorDocument())) } }
            rule.onNodeWithText("断开连接").assertIsEnabled().performClick()
            rule.waitUntil(3000) { model.state.value.status == "未连接" }
        } finally { model.close() }
    }

    @Test fun actualEditorInputAndDiscardDialog() {
        val model = ConsoleModel()
        val document = mutableStateOf(EditorDocument())
        try {
            rule.setContent { WorkbenchTheme { Console(model, document) } }
            rule.onNodeWithContentDescription("脚本").performClick()
            rule.onNodeWithTag("script-new").performClick()
            rule.onNodeWithTag("script-editor").performTextReplacement("def start():\n    pass\n")
            rule.onNodeWithText("新脚本 · 未保存").assertExists()
            rule.onNodeWithText("运行脚本").assertIsNotEnabled()
            rule.onNodeWithTag("script-import").performClick()
            rule.onNodeWithText("替换未保存的脚本？").assertExists()
            rule.onNodeWithText("返回").performClick()
            rule.onNodeWithText("替换未保存的脚本？").assertDoesNotExist()
            rule.runOnIdle {
                assertEquals("def start():\n    pass\n", document.value.source)
                assertTrue(document.value.dirty)
            }
        } finally { model.close() }
    }

    @Test fun missingTargetReportsErrorAndDoesNotConnect() {
        val model = ConsoleModel()
        try {
            rule.setContent { WorkbenchTheme { Console(model, mutableStateOf(EditorDocument())) } }
            rule.onNodeWithText("手动连接").performClick()
            rule.onNodeWithText("连接", substring = false).performClick()
            rule.waitUntil(timeoutMillis = 3000) {
                rule.onAllNodesWithText("请指定机器人 IPv4 地址").fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithText("未连接", substring = false).assertExists()
        } finally { model.close() }
    }

    @Test fun receivedFramesReachActualTelemetryAndMessageViews() {
        val model = ConsoleModel()
        try {
            val payload = ByteArray(62)
            payload[10] = 88
            val bits = 1.25f.toBits()
            repeat(4) { payload[26 + it] = (bits ushr (it * 8)).toByte() }
            model.receive(DussFrame(20, 9, 2, 1, 0, 0x48, 8, payload, true))
            model.receive(DussFrame(24, 9, 2, 2, 0, 0x48, 8,
                byteArrayOf(0,10,0xe2.toByte(),4,0x6f,0xff.toByte(),0x0a,0xf7.toByte(),0x85.toByte(),0,0x85.toByte()), true))
            // Subsequent chassis messages must not overwrite the gimbal sample.
            model.receive(DussFrame(20, 9, 2, 3, 0, 0x48, 8, payload, true))
            val message = "fixture robot message".encodeToByteArray()
            model.state.value = model.state.value.copy(scriptTitle = "fixture",
                scriptRunPhase = ScriptRunPhase.RUNNING, scriptStartedAtEpochMillis = System.currentTimeMillis())
            model.receive(DussFrame(20, 9, 2, 2, 0, 0x3f, 0xa4,
                byteArrayOf(1, 2, message.size.toByte(), 0) + message, true))
            rule.setContent { WorkbenchTheme { Console(model, mutableStateOf(EditorDocument())) } }
            rule.onNodeWithText("88%", substring = true).assertExists()
            rule.onNodeWithContentDescription("诊断").performClick()
            rule.onNodeWithText("遥测").performClick()
            rule.onNodeWithText("云台协议角度 · 最近接收").assertExists()
            rule.onNodeWithText("-229.4°").assertExists()
            rule.onNodeWithText("0x85").assertExists()
            snapshot("gimbal-telemetry")
            rule.onNodeWithText("raw[0] / offset 26").assertExists()
            rule.onNodeWithText("1.25", substring = false).assertExists()
            rule.onNodeWithContentDescription("脚本").performClick()
            rule.onNodeWithTag("script-run-screen").assertIsDisplayed()
            rule.onNodeWithText("fixture robot message").assertExists()
        } finally { model.close() }
    }

    @Test fun activeScriptAndLatestOutputRemainVisibleAcrossPages() {
        val model = ConsoleModel()
        val runId = "0123456789abcdef"
        val width = mutableStateOf(393.dp)
        val height = mutableStateOf(740.dp)
        try {
            model.state.value = model.state.value.copy(
                connected = true,
                scriptRunId = runId,
                scriptTitle = "好奇哨兵",
                scriptRunPhase = ScriptRunPhase.STARTING,
                scriptStartedAtEpochMillis = System.currentTimeMillis(),
                scriptMessage = uiText(Res.string.waiting_for_script_start),
            )
            fun message(kind: String, text: String = ""): DussFrame {
                val value = "__HANPPIE_RUN__|$runId|$kind|$text".encodeToByteArray()
                return DussFrame(20, 9, 2, 2, 0, 0x3f, 0xa4,
                    byteArrayOf(0, 0, value.size.toByte(), 0) + value, true)
            }
            model.receive(message("STARTED"))
            val output = "Sentry scan 2/3: left".encodeToByteArray()
            model.receive(DussFrame(20, 9, 2, 3, 0, 0x3f, 0xa4,
                byteArrayOf(0, 0, output.size.toByte(), 0) + output, true))
            rule.setContent { WorkbenchTheme {
                Box(Modifier.requiredSize(width.value, height.value)) { Console(model, mutableStateOf(EditorDocument())) }
            } }
            rule.onNodeWithTag("script-run-banner").assertIsDisplayed()
            rule.onNodeWithText("好奇哨兵").assertIsDisplayed()
            rule.onNodeWithText("Sentry scan 2/3: left").assertIsDisplayed()
            snapshot("active-script-global-status")
            rule.onNodeWithTag("script-run-banner").performClick()
            rule.onNodeWithTag("script-run-screen").assertIsDisplayed()
            rule.onNodeWithTag("script-run-log").assertIsDisplayed()
            rule.onNodeWithText("运行日志").assertIsDisplayed()
            rule.onNodeWithText("我的脚本").assertDoesNotExist()
            snapshot("active-script-run-phone")
            rule.runOnIdle { width.value = 320.dp; height.value = 568.dp }
            rule.waitForIdle()
            rule.onNodeWithTag("script-run-screen").assertIsDisplayed()
            rule.onNodeWithTag("script-run-log").assertIsDisplayed()
            rule.onNodeWithText("Sentry scan 2/3: left").assertIsDisplayed()
            snapshot("active-script-run-compact-phone")
            rule.runOnIdle { width.value = 1040.dp; height.value = 760.dp }
            rule.waitForIdle()
            rule.onNodeWithTag("script-run-screen").assertIsDisplayed()
            rule.onNodeWithText("Sentry scan 2/3: left").assertIsDisplayed()
            snapshot("active-script-run-desktop")
            rule.runOnIdle { width.value = 740.dp; height.value = 393.dp }
            rule.waitForIdle()
            rule.onNodeWithTag("script-run-screen").assertIsDisplayed()
            rule.onNodeWithTag("script-run-log").assertIsDisplayed()
            rule.onNodeWithText("Sentry scan 2/3: left").assertIsDisplayed()
            snapshot("active-script-run-landscape")
        } finally { model.close() }
    }

    @Test fun presetCanBeSavedRenamedAndDeletedFromScriptLibrary() {
        Localization.initialize("zh", null)
        val model = ConsoleModel()
        val document = mutableStateOf(EditorDocument())
        try {
            rule.waitUntil(3_000) { !model.scriptLibrary.state.value.loading }
            rule.setContent { WorkbenchTheme {
                Box(Modifier.requiredSize(393.dp, 740.dp)) { Console(model, document) }
            } }
            rule.onNodeWithContentDescription("脚本").performClick()
            rule.onNodeWithText("我的脚本").assertIsDisplayed()
            rule.onNodeWithText("预置脚本").performScrollTo().assertIsDisplayed()
            snapshot("phone-script-library")
            rule.onNodeWithContentDescription("script-preset-battery-mood-show").performScrollTo().performClick()
            rule.onNodeWithTag("script-editor").assertIsDisplayed()
            rule.onNodeWithText("Python 3.6.6").assertDoesNotExist()
            rule.runOnIdle { assertTrue(document.value.source.contains("get_battery_percentage")) }
            rule.onNodeWithTag("script-back").performClick()
            rule.onNodeWithText("替换未保存的脚本？").assertDoesNotExist()
            rule.onNodeWithTag("script-library").assertIsDisplayed()
            rule.onNodeWithContentDescription("script-preset-battery-mood-show").performScrollTo().performClick()
            snapshot("phone-script-preset-editor")
            rule.onNodeWithTag("script-save").performClick()
            rule.onNodeWithContentDescription("script-name").performTextReplacement("我的电量脚本")
            rule.onNodeWithText("保存", substring = false).performClick()
            rule.waitUntil(3_000) { model.scriptLibrary.state.value.scripts.singleOrNull()?.name == "我的电量脚本" }
            val id = model.scriptLibrary.state.value.scripts.single().id
            rule.onNodeWithTag("script-back").performClick()
            rule.onNodeWithText("我的电量脚本").assertIsDisplayed()
            rule.onNodeWithTag("script-rename-$id").performClick()
            rule.onNodeWithContentDescription("script-name").performTextReplacement("电量检查")
            rule.onNodeWithText("保存", substring = false).performClick()
            rule.waitUntil(3_000) { model.scriptLibrary.state.value.scripts.singleOrNull()?.name == "电量检查" }
            rule.onNodeWithTag("script-delete").performClick()
            rule.onNodeWithText("删除脚本？").assertIsDisplayed()
            rule.onAllNodesWithText("删除", substring = false).onLast().performClick()
            rule.waitUntil(3_000) { model.scriptLibrary.state.value.scripts.isEmpty() }
            rule.onNodeWithTag("script-library").assertIsDisplayed()
        } finally {
            model.close()
            Localization.initialize("zh", null)
        }
    }

    @Test fun shortcutEditorCapturesAChordWithoutPopup() {
        Localization.initialize("zh", null)
        val model = ConsoleModel()
        try {
            rule.setContent { WorkbenchTheme { Box(Modifier.requiredSize(393.dp, 740.dp)) { SettingsPage(model) } } }
            rule.onNodeWithText("控制快捷键").performScrollTo().performClick()
            rule.onNodeWithText("切换弹药").performScrollTo().assertIsDisplayed()
            rule.onNodeWithText("G", substring = false).performClick()
            rule.onNodeWithTag("settings-page").performKeyInput {
                keyDown(Key.ShiftLeft); keyDown(Key.F); keyUp(Key.F); keyUp(Key.ShiftLeft)
            }
            rule.waitUntil(2_000) {
                model.controlSettings.value.shortcuts[ControlAction.SwitchAmmo] == KeyBinding(ControlKey.F, shift = true)
            }
        } finally { model.close() }
    }
}
