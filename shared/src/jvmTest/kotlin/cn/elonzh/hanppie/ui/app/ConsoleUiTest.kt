package cn.elonzh.hanppie.ui.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.agent.tools.DeleteLabScriptTool
import cn.elonzh.hanppie.agent.tools.ExecuteLabPythonTool
import cn.elonzh.hanppie.agent.tools.LabApiReferenceTool
import cn.elonzh.hanppie.agent.tools.SaveLabScriptTool
import cn.elonzh.hanppie.agent.tools.StopLabTool
import cn.elonzh.hanppie.robot.protocol.DussFrame
import cn.elonzh.hanppie.robot.lab.ScriptRunPhase
import cn.elonzh.hanppie.robot.product.RobotComponent
import cn.elonzh.hanppie.robot.product.RobotModel
import cn.elonzh.hanppie.robot.product.RobotProduct
import cn.elonzh.hanppie.ui.chat.ChatLine
import cn.elonzh.hanppie.ui.chat.ChatMarkdown
import cn.elonzh.hanppie.ui.chat.ChatPage
import cn.elonzh.hanppie.ui.chat.ChatPhase
import cn.elonzh.hanppie.ui.chat.ChatRole
import cn.elonzh.hanppie.ui.chat.ChatState
import cn.elonzh.hanppie.ui.chat.ToolApproval
import ai.koog.agents.core.agent.execution.AgentExecutionInfo
import cn.elonzh.hanppie.agent.runtime.MessageEvent
import cn.elonzh.hanppie.agent.runtime.TestSessionHistory
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.message.MessagePart
import cn.elonzh.hanppie.ui.design.WorkbenchTheme
import cn.elonzh.hanppie.ui.i18n.Localization
import cn.elonzh.hanppie.ui.i18n.uiText
import cn.elonzh.hanppie.ui.robot.remote.RemotePage
import cn.elonzh.hanppie.ui.scripts.EditorDocument
import cn.elonzh.hanppie.ui.settings.AppearanceController
import cn.elonzh.hanppie.ui.settings.AppearanceSettings
import cn.elonzh.hanppie.ui.settings.ControlAction
import cn.elonzh.hanppie.ui.settings.ControlKey
import cn.elonzh.hanppie.ui.settings.KeyBinding
import cn.elonzh.hanppie.ui.settings.NightMode
import cn.elonzh.hanppie.ui.settings.ModelProviderPreset
import cn.elonzh.hanppie.ui.settings.ModelSettings
import cn.elonzh.hanppie.ui.settings.SettingsDropdown
import cn.elonzh.hanppie.ui.settings.SettingsPage
import cn.elonzh.hanppie.ui.speech.SpeechInput
import cn.elonzh.hanppie.ui.speech.SpeechInputState
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test

class ConsoleUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun connectedDeviceUsesTheProtocolReportedProductName() {
        val model = testConsoleModel()
        try {
            model.state.value = ConsoleState(connected = true, connectedAddress = "192.0.2.1",
                robotProduct = RobotProduct(model = RobotModel.ROBOMASTER_EP))
            val document = mutableStateOf(EditorDocument())
            rule.setContent { WorkbenchTheme { Box(Modifier.requiredSize(393.dp, 740.dp)) { Console(model, document) } } }
            rule.onNodeWithText("RoboMaster EP").assertIsDisplayed()
            rule.onNodeWithText("RoboMaster S1").assertDoesNotExist()
        } finally { model.close() }
    }

    @Test fun receivedProductFramesUpdateModelAndCapabilitiesIndependently() {
        val model = testConsoleModel()
        try {
            model.receive(DussFrame(0, 0x28, 2, 1, 0xc0, 0x3f, 0xfe, byteArrayOf(0, 2), true))
            model.receive(DussFrame(0, 0x28, 2, 2, 0x00, 0x3f, 0x12,
                byteArrayOf(1, 0, 3, 0), true))

            assertEquals(RobotModel.ROBOMASTER_EP, model.state.value.robotProduct.model)
            assertTrue(RobotComponent.CHASSIS in model.state.value.robotProduct.capabilities)
        } finally { model.close() }
    }

    @Test fun languageSwitchPreservesDocumentAndLocalizesNavigation() {
        Localization.initialize("zh",null)
        val model=testConsoleModel()
        val document=mutableStateOf(EditorDocument(source="print('用户脚本')"))
        try {
            rule.setContent { WorkbenchTheme { Box(Modifier.requiredSize(393.dp,740.dp)) { Console(model,document) } } }
            rule.onNodeWithText("RoboMaster").assertIsDisplayed()
            rule.onNodeWithText("S1").assertDoesNotExist()
            rule.onNodeWithContentDescription("设置").performClick()
            openSettingsCategory("general")
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
            openSettingsCategory("general")
            rule.onNodeWithContentDescription("language-selector").performClick()
            rule.onNodeWithContentDescription("language-zh").performClick()
            rule.onNodeWithText("语言").assertIsDisplayed()
        } finally { model.close(); Localization.initialize("zh",null) }
    }

    @Test fun englishCockpitAtPhoneAndDesktopSizes() {
        Localization.initialize("en",null)
        val model=testConsoleModel()
        val width=mutableStateOf(740.dp)
        try {
            model.state.value = ConsoleState(connected = true, battery = 82, signalQuality = 37,
                gimbal = cn.elonzh.hanppie.robot.telemetry.GimbalTelemetry(42.0, 0.0, 42.0, 0.0, 0))
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
            rule.onNodeWithText("S1", substring = true).assertDoesNotExist()
            snapshot("phone-cockpit-en")
            rule.runOnIdle { width.value=1040.dp }
            rule.waitForIdle()
            snapshot("desktop-cockpit-en")
        } finally { model.close(); Localization.initialize("zh",null) }
    }

    @Test fun gearKeysShiftDoesNotSlowAndStop() {
        val model=testConsoleModel()
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
        val model=testConsoleModel()
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
        val model=testConsoleModel()
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

    @Test fun voiceRecognitionFillsDraftAndStopsOnNavigation() {
        val input = object : SpeechInput {
            override val state = kotlinx.coroutines.flow.MutableStateFlow(SpeechInputState())
            override fun start() { state.value = state.value.copy(active = true) }
            override fun finish() { state.value = state.value.copy(active = false, result = "看看连接状态", resultId = 1) }
            override fun cancel() { state.value = state.value.copy(active = false) }
            override fun consumeResult() { state.value = state.value.copy(result = "") }
            override fun close() { cancel() }
        }
        val model = testConsoleModel(input)
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
            rule.runOnIdle { model.chat.state.value = model.chat.state.value.copy(
                replyRevision = 1, lastReply = "目前未连接机器人。", lines = listOf(ChatLine(ChatRole.ASSISTANT, "目前未连接机器人。"))) }
            rule.waitUntil(5000) { rule.onAllNodesWithText("目前未连接机器人。").fetchSemanticsNodes().isNotEmpty() }
            snapshot("phone-chat-voice")
            rule.onNodeWithContentDescription("设备").performClick()
            rule.onNodeWithContentDescription("对话").performClick()
            rule.waitForIdle()
            assertTrue(!input.state.value.active)
        } finally { model.close() }
    }

    @Test fun phoneLayoutAndEditorRemainUsable() {
        val model = testConsoleModel()
        try {
            rule.setContent { WorkbenchTheme {
                Box(Modifier.requiredSize(393.dp, 740.dp)) { Console(model, mutableStateOf(EditorDocument())) }
            } }
            rule.onNodeWithTag("bottom-navigation").assertIsDisplayed()
            rule.onNodeWithText("自动连接").assertIsDisplayed()
            rule.onNodeWithText("手机或电脑需与机器人连接同一 Wi-Fi").assertDoesNotExist()
            snapshot("phone-device")
            rule.onNodeWithContentDescription("脚本").performClick()
            rule.onNodeWithTag("script-library").assertIsDisplayed()
            rule.onNodeWithTag("script-new").performClick()
            rule.onNodeWithTag("script-editor").assertIsDisplayed().performTextReplacement("def start():\n    pass")
            rule.onNodeWithTag("script-save").assertIsDisplayed()
            rule.onNodeWithTag("script-run").assertIsDisplayed()
            rule.onNodeWithTag("script-console").assertIsDisplayed()
            snapshot("phone-script")
        } finally { model.close() }
    }

    @Test fun phoneChatLayoutShowsComposerAndConfiguration() {
        val model = testConsoleModel()
        try {
            rule.setContent { WorkbenchTheme {
                Box(Modifier.requiredSize(393.dp, 740.dp)) { Console(model, mutableStateOf(EditorDocument())) }
            } }
            rule.onNodeWithContentDescription("对话").performClick()
            rule.onNodeWithTag("chat-input").assertIsDisplayed()
            rule.onNodeWithContentDescription("发送").assertIsNotEnabled()
            snapshot("phone-chat")
            rule.onNodeWithContentDescription("设置").performClick()
            openSettingsCategory("model")
            rule.onNodeWithText("API Key").performScrollTo().assertIsDisplayed()
            snapshot("phone-chat-settings")
        } finally { model.close() }
    }

    @Test fun sendAvailabilityUsesTheRuntimePhase() {
        val model = testConsoleModel()
        try {
            rule.setContent { WorkbenchTheme {
                Box(Modifier.requiredSize(393.dp, 740.dp)) { ChatPage(model) }
            } }
            rule.waitUntil(5_000) { model.chat.state.value.ready }
            rule.runOnIdle {
                model.chat.updateDraft("继续")
                model.chat.state.value = model.chat.state.value.copy(phase = ChatPhase.MANAGING)
            }
            rule.onNodeWithContentDescription("发送").assertIsNotEnabled()

            rule.runOnIdle {
                model.chat.state.value = model.chat.state.value.copy(phase = ChatPhase.IDLE)
            }
            rule.onNodeWithContentDescription("发送").assertIsEnabled()
        } finally { model.close() }
    }

    @Test fun composerSendsWithEnterAndButtonWhileShiftEnterInsertsALineBreak() {
        val model = testConsoleModel()
        try {
            rule.setContent { WorkbenchTheme {
                Box(Modifier.requiredSize(1040.dp, 740.dp)) { ChatPage(model) }
            } }
            rule.waitUntil(5_000) { model.chat.state.value.ready }
            rule.runOnIdle { model.modelSettings.value = ModelSettings(apiKey = "test-key") }

            rule.onNodeWithTag("chat-input").performClick().performTextInput("第一行")
            rule.onNodeWithTag("chat-input").performKeyInput {
                keyDown(Key.ShiftLeft); pressKey(Key.Enter); keyUp(Key.ShiftLeft)
            }
            rule.onNodeWithTag("chat-input").performTextInput("第二行")
            rule.runOnIdle { assertEquals("第一行\n第二行", model.chat.state.value.draft) }

            rule.onNodeWithTag("chat-input").performKeyInput { pressKey(Key.Enter) }
            rule.waitUntil(5_000) {
                model.chat.state.value.lines.any {
                    it.role == ChatRole.USER && it.text == "第一行\n第二行"
                }
            }
            rule.waitUntil(5_000) { model.chat.state.value.ready }

            rule.onNodeWithTag("chat-input").performTextInput("按钮发送")
            rule.onNodeWithContentDescription("发送").performClick()
            rule.waitUntil(5_000) {
                model.chat.state.value.lines.any {
                    it.role == ChatRole.USER && it.text == "按钮发送"
                }
            }
        } finally { model.close() }
    }

    @Test fun sessionHistoryAdaptsBetweenPhoneDialogAndDesktopSidebar() {
        val history = TestSessionHistory()
        kotlinx.coroutines.runBlocking {
            val first = history.create("机器人巡检")
            history.append(first.id, MessageEvent(
                eventId = "history-message",
                runId = "history-run",
                timestamp = 1,
                executionInfo = AgentExecutionInfo(null, "test"),
                message = prompt("history-ui") { user("检查当前连接") }.messages.single(),
            ))
            history.create("灯光方案")
        }
        val model = testConsoleModel(sessionHistory = history)
        val width = mutableStateOf(393.dp)
        try {
            rule.setContent { WorkbenchTheme {
                Box(Modifier.requiredSize(width.value, 740.dp)) { ChatPage(model) }
            } }
            rule.waitUntil(5_000) { model.chat.state.value.ready && model.chat.state.value.sessions.size == 2 }
            rule.onNodeWithTag("conversation-list").assertDoesNotExist()
            rule.onNodeWithContentDescription("对话记录").performClick()
            rule.onNodeWithTag("conversation-list").assertIsDisplayed()
            rule.onNodeWithText("机器人巡检").assertIsDisplayed()
            rule.onNodeWithTag("new-conversation").assertIsDisplayed()
            assertTrue(rule.onNodeWithTag("conversation-list").fetchSemanticsNode().boundsInRoot.height < 400f)
            rule.onAllNodesWithContentDescription("对话操作").assertCountEquals(2)
            rule.onAllNodesWithContentDescription("对话操作").onFirst().performClick()
            rule.onNodeWithText("重命名", substring = false).assertIsDisplayed()
            rule.onNodeWithText("删除", substring = false).assertIsDisplayed()
            rule.onNodeWithText("重命名", substring = false).performClick()
            rule.onNodeWithText("取消", substring = false).performClick()
            rule.onNodeWithText("归档", substring = false).assertDoesNotExist()
            snapshot("phone-conversation-list", rule.onNodeWithTag("conversation-list"))

            rule.onNodeWithText("机器人巡检").performClick()
            rule.runOnIdle { width.value = 1040.dp }
            rule.waitForIdle()
            rule.onNodeWithTag("conversation-list").assertIsDisplayed()
            rule.onNodeWithText("机器人巡检").assertIsDisplayed()
            val sidebarBounds = rule.onNodeWithTag("conversation-list").fetchSemanticsNode().boundsInRoot
            val chatBounds = rule.onNodeWithTag("chat-surface").fetchSemanticsNode().boundsInRoot
            val newConversationBounds = rule.onNodeWithTag("new-conversation").fetchSemanticsNode().boundsInRoot
            assertTrue(sidebarBounds.height >= chatBounds.height * .95f)
            assertTrue(newConversationBounds.left >= sidebarBounds.left && newConversationBounds.right <= sidebarBounds.right)
            snapshot("desktop-conversation-sidebar")
        } finally { model.close() }
    }

    @Test fun fencedPythonCodeUsesTheHighlightedMarkdownRenderer() {
        rule.setContent { WorkbenchTheme {
            Box(Modifier.requiredSize(700.dp, 320.dp).padding(24.dp)) {
                ChatMarkdown("""
                    ```python
                    def start():
                        log_ctrl.print_msg("ready")
                    ```
                """.trimIndent(), Modifier.testTag("highlighted-python"))
            }
        } }

        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("def start():", substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes().any { node ->
                    node.config[SemanticsProperties.Text].any { text ->
                        text.spanStyles.map { range -> range.item.color }
                            .filter { color -> color != Color.Unspecified }
                            .distinct().size >= 2
                    }
                }
        }
        snapshot("chat-python-syntax-highlight")
    }

    @Test fun scriptDeletionApprovalNamesTheExactSavedScript() {
        val model = testConsoleModel()
        try {
            rule.setContent { WorkbenchTheme {
                Box(Modifier.requiredSize(393.dp, 740.dp)) { ChatPage(model) }
            } }
            rule.waitUntil(5_000) { model.chat.state.value.ready }
            rule.runOnIdle {
                model.chat.state.value = model.chat.state.value.copy(
                    phase = ChatPhase.RUNNING,
                    approval = ToolApproval(
                        MessagePart.Tool.Call("delete-call", DeleteLabScriptTool.NAME, "{\"name\":\"旧巡检\"}"),
                        "旧巡检",
                    ),
                )
            }

            rule.onNodeWithText("永久删除这段脚本？").assertIsDisplayed()
            rule.onNodeWithText("旧巡检", substring = false).assertIsDisplayed()
            rule.onNodeWithText("删除", substring = false).assertIsDisplayed()
            snapshot("phone-agent-delete-script-approval")

            rule.runOnIdle {
                model.chat.state.value = model.chat.state.value.copy(
                    approval = ToolApproval(
                        MessagePart.Tool.Call("future-call", "future_approval_tool", "{}"),
                        "通用审批预览",
                    ),
                )
            }
            rule.onNodeWithText("确认执行此工具操作？").assertIsDisplayed()
            rule.onNodeWithText("通用审批预览").assertIsDisplayed()
            rule.onNodeWithText("确认", substring = false).assertIsDisplayed()
        } finally { model.close() }
    }

    @Test fun desktopChatShortcutsCreateConversationAndFocusComposer() {
        val history = TestSessionHistory()
        val model = testConsoleModel(sessionHistory = history)
        var settingsOpened = false
        try {
            rule.setContent { WorkbenchTheme {
                Box(Modifier.requiredSize(1040.dp, 740.dp)) { ChatPage(model, onSettings = { settingsOpened = true }) }
            } }
            rule.waitUntil(5_000) { model.chat.state.value.ready }
            val initial = model.chat.state.value.sessions.size
            rule.onNodeWithTag("chat-input").performClick().performKeyInput {
                keyDown(Key.CtrlLeft); pressKey(Key.N); keyUp(Key.CtrlLeft)
            }
            // A new conversation only focuses an empty composer: no session is stored until it is sent.
            rule.waitUntil(5_000) { model.chat.state.value.sessionId == null }
            rule.runOnIdle { assertEquals(initial, model.chat.state.value.sessions.size) }
            rule.onNodeWithTag("chat-input").performKeyInput {
                keyDown(Key.CtrlLeft); pressKey(Key.L); keyUp(Key.CtrlLeft)
            }
            rule.onNodeWithTag("chat-input").assertIsFocused()
            rule.onNodeWithTag("chat-input").performKeyInput {
                keyDown(Key.CtrlLeft); pressKey(Key.Comma); keyUp(Key.CtrlLeft)
            }
            rule.runOnIdle { assertTrue(settingsOpened) }
        } finally { model.close() }
    }

    @Test fun theModelIdRowOpensAFilterableModelPicker() {
        Localization.initialize("zh", null)
        val model = testConsoleModel()
        try {
            rule.setContent { WorkbenchTheme {
                Box(Modifier.requiredSize(393.dp, 740.dp)) { Console(model, mutableStateOf(EditorDocument())) }
            } }
            rule.onNodeWithContentDescription("设置").performClick()
            openSettingsCategory("model")
            rule.onNodeWithTag("model-picker").performScrollTo().performClick()
            rule.onNodeWithTag("model-filter").performTextReplacement("deepseek")
            rule.waitForIdle()
            rule.onNodeWithTag("model-option-deepseek-v4-flash").performClick()
            rule.runOnIdle { assertEquals("deepseek-v4-flash", model.modelSettings.value.model) }
            snapshot("phone-model-picker")
        } finally { model.close() }
    }

    @Test fun streamingMarkdownAppendsAndStartsANewReply() {
        val model = testConsoleModel()
        val width = mutableStateOf(393.dp)
        try {
            rule.setContent { WorkbenchTheme { Box(Modifier.requiredSize(width.value, 740.dp)) { ChatPage(model) } } }
            rule.waitUntil(5_000) { model.chat.state.value.ready }
            rule.runOnIdle {
                model.chat.state.value = model.chat.state.value.copy(
                    phase = ChatPhase.RUNNING,
                    lines = listOf(ChatLine(ChatRole.USER, "检查状态")),
                    streaming = "## 连接状态\n\n电量 **88",
                )
            }
            rule.waitUntil(5000) { rule.onAllNodesWithText("连接状态").fetchSemanticsNodes().size == 1 }
            rule.runOnIdle { model.chat.state.value = model.chat.state.value.copy(
                streaming = "## 连接状态\n\n电量 **88%**。\n\n- 已连接\n- 待机") }
            rule.waitUntil(5000) { rule.onAllNodesWithText("待机", substring = true).fetchSemanticsNodes().isNotEmpty() }
            rule.onAllNodesWithText("连接状态").assertCountEquals(1)
            snapshot("phone-markdown-stream")
            rule.onNodeWithText("我", substring = false).assertDoesNotExist()
            rule.onNodeWithText("憨皮", substring = false).assertDoesNotExist()
            rule.runOnIdle {
                val old = model.chat.state.value
                model.chat.state.value = old.copy(lines = old.lines + ChatLine(ChatRole.ASSISTANT, old.streaming),
                    streaming = "## 下一步\n\n等待指令。")
            }
            rule.waitUntil(5000) { rule.onAllNodesWithText("下一步").fetchSemanticsNodes().size == 1 }
            rule.onAllNodesWithText("连接状态").assertCountEquals(1)
            rule.onAllNodesWithText("下一步").assertCountEquals(1)
            rule.runOnIdle { width.value = 1040.dp }
            rule.waitForIdle()
            snapshot("desktop-agent-chat")
        } finally { model.close() }
    }

    @Test fun sentMessageAndGrowingReplyStayAtTheBottom() {
        val model = testConsoleModel()
        fun isAtBottom(): Boolean {
            val range = rule.onNodeWithTag("chat-messages").fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange]
            return range.maxValue() - range.value() < 1f
        }
        try {
            rule.setContent { WorkbenchTheme {
                Box(Modifier.requiredSize(1040.dp, 740.dp)) { ChatPage(model) }
            } }
            rule.waitUntil(5_000) { model.chat.state.value.ready }
            rule.runOnIdle {
                model.chat.state.value = model.chat.state.value.copy(
                    lines = List(250) { index -> ChatLine(ChatRole.ASSISTANT, "历史消息 $index") },
                )
            }
            rule.waitUntil(5_000, ::isAtBottom)

            rule.onNodeWithTag("chat-messages").performTouchInput { swipeDown() }
            rule.waitUntil(5_000) { !isAtBottom() }
            rule.runOnIdle {
                val old = model.chat.state.value
                model.chat.state.value = old.copy(
                    phase = ChatPhase.RUNNING,
                    lines = (old.lines + ChatLine(ChatRole.USER, "刚发送的消息")).takeLast(250),
                    streaming = "正在回复",
                    userMessageRevision = old.userMessageRevision + 1,
                )
            }
            rule.waitUntil(5_000) {
                rule.onAllNodesWithText("刚发送的消息").fetchSemanticsNodes().isNotEmpty() && isAtBottom()
            }

            rule.runOnIdle {
                model.chat.state.value = model.chat.state.value.copy(
                    streaming = List(40) { "持续生成的内容 $it" }.joinToString("\n\n") + "\n\n回复末尾",
                )
            }
            rule.waitUntil(5_000, ::isAtBottom)
            rule.onNodeWithText("回复末尾", substring = true, useUnmergedTree = true).assertIsDisplayed()
            snapshot("desktop-chat-follow-latest")

            rule.onNodeWithTag("chat-messages").performTouchInput { swipeDown() }
            rule.waitUntil(5_000) { !isAtBottom() }
            rule.runOnIdle {
                model.chat.state.value = model.chat.state.value.copy(
                    streaming = model.chat.state.value.streaming + "\n\n用户上滚后新增的内容",
                )
            }
            rule.waitForIdle()
            assertFalse(isAtBottom())
        } finally { model.close() }
    }

    @Test fun toolBusinessOutcomesAreLocalizedAndExpandableWithoutRawEnums() {
        val model = testConsoleModel()
        val executionRejected = Json.encodeToString(
            ExecuteLabPythonTool.Result(ExecuteLabPythonTool.Status.USER_REJECTED),
        )
        val deletionRejected = Json.encodeToString(
            DeleteLabScriptTool.Result("旧巡检", DeleteLabScriptTool.Status.USER_REJECTED),
        )
        val stopSent = Json.encodeToString(StopLabTool.Result(StopLabTool.Status.STOP_COMMAND_SENT))
        val executeSent = Json.encodeToString(
            ExecuteLabPythonTool.Result(ExecuteLabPythonTool.Status.START_COMMAND_SENT, "run-1"),
        )
        val rawFailure = "Tool approval arguments could not be parsed"
        try {
            rule.setContent { WorkbenchTheme {
                Box(Modifier.requiredSize(1040.dp, 740.dp)) { ChatPage(model) }
            } }
            rule.waitUntil(5_000) { model.chat.state.value.ready }
            rule.runOnIdle {
                model.chat.state.value = model.chat.state.value.copy(lines = listOf(
                    ChatLine(
                        ChatRole.TOOL,
                        toolCall = MessagePart.Tool.Call("execute-rejected", ExecuteLabPythonTool.NAME, "{\"source\":\"def start(): pass\"}"),
                        toolResult = MessagePart.Tool.Result("execute-rejected", ExecuteLabPythonTool.NAME, executionRejected),
                    ),
                    ChatLine(
                        ChatRole.TOOL,
                        toolCall = MessagePart.Tool.Call("delete-rejected", DeleteLabScriptTool.NAME, "{\"name\":\"旧巡检\"}"),
                        toolResult = MessagePart.Tool.Result("delete-rejected", DeleteLabScriptTool.NAME, deletionRejected),
                    ),
                    ChatLine(
                        ChatRole.TOOL,
                        toolCall = MessagePart.Tool.Call("stop-sent", StopLabTool.NAME, "{}"),
                        toolResult = MessagePart.Tool.Result("stop-sent", StopLabTool.NAME, stopSent),
                    ),
                    ChatLine(
                        ChatRole.TOOL,
                        toolCall = MessagePart.Tool.Call(
                            "execute-sent",
                            ExecuteLabPythonTool.NAME,
                            "{\"source\":\"def start(): pass\"}",
                        ),
                        toolResult = MessagePart.Tool.Result("execute-sent", ExecuteLabPythonTool.NAME, executeSent),
                    ),
                    ChatLine(
                        ChatRole.TOOL,
                        toolCall = MessagePart.Tool.Call("failed-save", SaveLabScriptTool.NAME, "{}"),
                        toolResult = MessagePart.Tool.Result("failed-save", SaveLabScriptTool.NAME, rawFailure, isError = true),
                    ),
                ))
            }

            rule.onAllNodesWithText("已取消", substring = false).assertCountEquals(2)
            rule.onAllNodesWithText("已发送", substring = false).assertCountEquals(2)
            rule.onNodeWithText("未上传、未启动。").assertIsDisplayed()
            rule.onNodeWithText("已取消删除，脚本库未变。").assertIsDisplayed()
            rule.onNodeWithText("停止命令已发送；未获得机内停止确认。").assertIsDisplayed()
            rule.onNodeWithText("STOP_COMMAND_SENT", substring = false).assertDoesNotExist()
            rule.onNodeWithText("工具调用失败，请展开查看详情。").assertIsDisplayed()
            rule.onNodeWithText(rawFailure, substring = false).assertDoesNotExist()

            rule.onNodeWithTag("tool-message-header-${StopLabTool.NAME}")
                .assertHeightIsAtLeast(48.dp)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "工具详情已折叠"))
            rule.onNodeWithTag("tool-message-header-${SaveLabScriptTool.NAME}").performClick()
            rule.onNodeWithText(rawFailure, substring = false).assertIsDisplayed()
        } finally { model.close() }
    }

    @Test fun toolResultsUseCompactToolSpecificRowsAndStayCollapsed() {
        val model = testConsoleModel()
        val apiFact = "LAB_API_RESULT_SHOULD_BE_COLLAPSED"
        val saveName = "结构化脚本"
        val apiDetails = Json.encodeToString(LabApiReferenceTool.Result(
            inCatalog = true,
            availableCategories = listOf("runtime", "logging"),
            sections = listOf(LabApiReferenceTool.Section("runtime", listOf(apiFact))),
            guidance = "只使用已核对的 API",
        ))
        val saveDetails = Json.encodeToString(SaveLabScriptTool.Result(
            name = saveName,
            created = true,
            sourceLength = 17,
            updatedAtEpochMillis = 1234,
        ))
        try {
            rule.setContent { WorkbenchTheme {
                Box(Modifier.requiredSize(1040.dp, 740.dp)) { ChatPage(model) }
            } }
            rule.waitUntil(5_000) { model.chat.state.value.ready }
            rule.runOnIdle {
                model.chat.state.value = model.chat.state.value.copy(lines = listOf(
                    ChatLine(
                        ChatRole.TOOL,
                        toolCall = MessagePart.Tool.Call("api-call", LabApiReferenceTool.NAME, "{\"query\":\"runtime\"}"),
                        toolResult = MessagePart.Tool.Result("api-call", LabApiReferenceTool.NAME, apiDetails),
                    ),
                    ChatLine(
                        ChatRole.TOOL,
                        toolCall = MessagePart.Tool.Call(
                            "save-call",
                            SaveLabScriptTool.NAME,
                            "{\"name\":\"$saveName\",\"source\":\"def start(): pass\"}",
                        ),
                        toolResult = MessagePart.Tool.Result("save-call", SaveLabScriptTool.NAME, saveDetails),
                    ),
                ))
            }

            rule.onNodeWithTag("tool-message-${LabApiReferenceTool.NAME}").assertIsDisplayed()
            rule.onNodeWithTag("tool-message-${SaveLabScriptTool.NAME}").assertIsDisplayed()
            rule.onNodeWithText("查询 Lab API").assertIsDisplayed()
            rule.onNodeWithText("保存 Lab 脚本").assertIsDisplayed()
            rule.onNodeWithText("查询：runtime").assertIsDisplayed()
            rule.onNodeWithText("脚本：$saveName").assertIsDisplayed()
            rule.onNodeWithText(apiFact).assertDoesNotExist()
            rule.onNodeWithText("created").assertDoesNotExist()

            rule.onNodeWithTag("tool-message-header-${LabApiReferenceTool.NAME}").performClick()
            rule.onNodeWithText("参数").assertIsDisplayed()
            rule.onNodeWithText("结果").assertIsDisplayed()
            rule.onNodeWithText("query").assertIsDisplayed()
            rule.onNodeWithText("inCatalog").assertIsDisplayed()
            rule.onNodeWithText(apiFact).assertIsDisplayed()
            rule.onNodeWithText("created").assertDoesNotExist()
            snapshot("desktop-chat-tool-activities")

            rule.onNodeWithTag("tool-message-header-${LabApiReferenceTool.NAME}").performClick()
            rule.onNodeWithText(apiFact).assertDoesNotExist()
            rule.onNodeWithTag("tool-message-header-${SaveLabScriptTool.NAME}").performClick()
            rule.onNodeWithText("source").assertIsDisplayed()
            rule.onNodeWithText("def start(): pass", substring = true).assertIsDisplayed()
            rule.onNodeWithText("created").assertIsDisplayed()
            snapshot("desktop-chat-script-tool-activity")
        } finally { model.close() }
    }

    @Test fun userMessageBubbleWrapsShortContentAndCapsLongMessages() {
        val model = testConsoleModel()
        val width = mutableStateOf(393.dp)
        try {
            rule.setContent { WorkbenchTheme {
                Box(Modifier.requiredSize(width.value, 740.dp)) { ChatPage(model) }
            } }
            rule.waitUntil(5_000) { model.chat.state.value.ready }
            rule.runOnIdle {
                model.chat.state.value = model.chat.state.value.copy(
                    lines = listOf(ChatLine(ChatRole.USER, "你好")),
                )
            }
            val shortWidth = rule.onNodeWithTag("user-message").fetchSemanticsNode().boundsInRoot.width
            assertTrue(shortWidth < 160f)

            rule.runOnIdle {
                width.value = 1040.dp
                model.chat.state.value = model.chat.state.value.copy(
                    lines = listOf(ChatLine(ChatRole.USER, "请检查当前机器人连接与脚本运行状态，并说明下一步应该如何处理。".repeat(8))),
                )
            }
            rule.waitForIdle()
            val longWidth = rule.onNodeWithTag("user-message").fetchSemanticsNode().boundsInRoot.width
            assertTrue(longWidth > shortWidth)
            assertTrue(longWidth <= 600.5f)
        } finally { model.close() }
    }

    @Test fun connectionFailureDetailsDoNotOccupyThePageHeader() {
        val model = testConsoleModel()
        try {
            val failure = "机器人未确认 App 会话，请检查网络或关闭其他控制器"
            model.state.value = model.state.value.lost(failure)
            rule.setContent { WorkbenchTheme {
                Box(Modifier.requiredSize(1040.dp, 740.dp)) {
                    Console(model, mutableStateOf(EditorDocument()))
                }
            } }
            rule.onNodeWithTag("connection-status").assertTextContains("未连接")
            rule.onNodeWithText(failure, substring = false).assertDoesNotExist()

            rule.onNodeWithContentDescription("对话").performClick()
            rule.onNodeWithTag("connection-status").assertTextContains("未连接")
            rule.onNodeWithText(failure, substring = false).assertDoesNotExist()

            rule.onNodeWithContentDescription("脚本").performClick()
            rule.onNodeWithTag("script-library").assertIsDisplayed()
            rule.onNodeWithText(failure, substring = false).assertDoesNotExist()

            rule.onNodeWithTag("script-new").performClick()
            rule.onNodeWithTag("script-editor").assertIsDisplayed()
            rule.onNodeWithText(failure, substring = false).assertDoesNotExist()
        } finally { model.close() }
    }

    @Test fun keyboardHintsFollowInputAndTouchReleaseZerosMotion() {
        val model = testConsoleModel()
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
        val model = testConsoleModel()
        val document = mutableStateOf(EditorDocument(source = "print('keep me')"))
        var saved: String? = null
        val appearance = AppearanceController(persist = { saved = it })
        try {
            rule.setContent { WorkbenchTheme(appearance) { Box(Modifier.requiredSize(393.dp, 740.dp)) { Console(model, document) } } }
            rule.onNodeWithContentDescription("设置").performClick()
            openSettingsCategory("general")
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

    @Test fun homepageKeepsConnectionChoicesOutOfTheEverydayFlow() {
        val model = testConsoleModel()
        val width = mutableStateOf(393.dp)
        try {
            model.connectionPreferences.value = cn.elonzh.hanppie.ui.settings.ConnectionPreferences(
                appId = "AABBCCDD",
                robots = listOf(cn.elonzh.hanppie.ui.settings.RememberedRobot(
                    "192.0.2.10", "AABBCCDD", "00:11:22:33:44:55", cn.elonzh.hanppie.ui.settings.ConnectionMode.ROUTER,
                )),
            )
            model.state.value = model.state.value.copy(devices = listOf(
                cn.elonzh.hanppie.robot.protocol.DiscoveredRobot("192.0.2.20", "AA:BB:CC:DD:EE:FF", "AABBCCDD", false),
            ))
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
            checkPair("auto-connect", "connection-guide")
            rule.onNodeWithTag("manual-connect").assertDoesNotExist()
            rule.onNodeWithText("192.0.2.10", substring = true).assertDoesNotExist()
            rule.onNodeWithText("192.0.2.20", substring = true).assertDoesNotExist()
            snapshot("home-actions-phone")
            rule.runOnIdle { width.value = 1040.dp }
            checkPair("auto-connect", "connection-guide")
            snapshot("home-actions-desktop")
            rule.onNodeWithContentDescription("诊断").performClick()
            rule.onNodeWithTag("manual-connect").assertIsDisplayed()
            rule.onNodeWithContentDescription("设备").performClick()
            rule.runOnIdle { model.state.value = model.state.value.copy(connected = true, connectedAddress = "192.0.2.1") }
            rule.waitUntil(3_000) { rule.onAllNodesWithTag("enter-remote").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("auto-connect").assertDoesNotExist()
            rule.onNodeWithTag("connection-guide").assertDoesNotExist()
            rule.onNodeWithText("192.0.2.1", substring = true).assertDoesNotExist()
            rule.onNodeWithText("机器人已准备好").assertIsDisplayed()
            snapshot("home-connected-desktop")
            rule.runOnIdle { width.value = 393.dp }
            rule.waitForIdle()
            snapshot("home-connected-phone")
            rule.onNodeWithTag("connection-status").performClick()
            rule.onNodeWithText("断开连接").assertIsDisplayed()
            assertTrue(!model.remoteEnabled.value)
        } finally { model.close() }
    }

    @Test fun connectionGuideOffersDirectAndRouterWorkflows() {
        val model = testConsoleModel()
        val width = mutableStateOf(393.dp)
        var wifiSettingsOpenCount = 0
        try {
            rule.setContent { WorkbenchTheme { Box(Modifier.requiredSize(width.value, 740.dp)) {
                Console(model, mutableStateOf(EditorDocument()), onOpenWifiSettings = { wifiSettingsOpenCount++ })
            } } }
            rule.onNodeWithTag("connection-guide").performClick()
            rule.onNodeWithText("配置连接方式").assertIsDisplayed()
            rule.onNodeWithTag("direct-mode").assertIsDisplayed()
            rule.onNodeWithTag("router-mode").assertIsDisplayed()
            snapshot("connection-modes-phone")
            rule.runOnIdle { width.value = 1040.dp }
            rule.waitForIdle()
            snapshot("connection-modes-desktop")
            rule.runOnIdle { width.value = 393.dp }
            rule.waitForIdle()
            rule.onNodeWithTag("direct-mode").performClick()
            rule.onNodeWithText("切换为直连模式").assertIsDisplayed()
            rule.onNodeWithText("连接机器人 Wi-Fi").assertIsDisplayed()
            snapshot("connection-direct-phone")
            rule.onNodeWithText("S1 出厂热点通常为 RMS1-XXXXXX", substring = true).performScrollTo().assertIsDisplayed()
            rule.onNodeWithTag("open-wifi-settings").performScrollTo().performClick()
            rule.runOnIdle { assertEquals(1, wifiSettingsOpenCount) }
            rule.onNodeWithTag("connection-guide-back").performClick()
            rule.runOnIdle { width.value = 1040.dp }
            rule.waitForIdle()
            rule.onNodeWithTag("router-mode").performClick()
            rule.onNodeWithText("生成路由器配网二维码").assertIsDisplayed()
            rule.onNodeWithTag("router-qr-placeholder").assertIsDisplayed()
            snapshot("connection-router-desktop")
            rule.onNodeWithTag("router-ssid").performTextReplacement("HanppieLab")
            rule.onNodeWithTag("router-password").performTextReplacement("12341234")
            rule.onNodeWithTag("router-qr-code").assertIsDisplayed()
            rule.onNodeWithTag("pair-router").assertIsEnabled()
            snapshot("connection-router-qr-desktop")
            rule.runOnIdle { width.value = 393.dp }
            rule.waitForIdle()
            rule.onNodeWithTag("router-qr-code").performScrollTo()
            snapshot("connection-router-qr-phone")
            rule.onNodeWithText("整个流程无需 RoboMaster 官方 App", substring = true).performScrollTo().assertIsDisplayed()
            rule.onNodeWithTag("manual-connect-from-guide").assertDoesNotExist()
        } finally { model.close() }
    }

    private fun openSettingsCategory(id: String) {
        if (rule.onAllNodesWithTag("settings-category-$id").fetchSemanticsNodes().isEmpty()) {
            rule.onNodeWithTag("settings-back").performClick()
        }
        rule.onNodeWithTag("settings-category-$id").performScrollTo().performClick()
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

    @Test fun beginnerViewHidesParametersAndModelSettingsAreExplicit() {
        val model = testConsoleModel()
        try {
            rule.setContent { WorkbenchTheme { Console(model, mutableStateOf(EditorDocument())) } }
            rule.onNodeWithText("连接", substring = false).assertDoesNotExist()
            rule.onNodeWithText("语音", substring = false).assertDoesNotExist()
            rule.onNodeWithContentDescription("对话").performClick()
            rule.onNodeWithContentDescription("设置").performClick()
            openSettingsCategory("model")
            rule.onNodeWithText("供应商").performScrollTo().assertExists()
            rule.onNodeWithContentDescription("model-provider-selector").performScrollTo().performClick()
            ModelProviderPreset.entries.forEach { provider ->
                rule.onNodeWithContentDescription("model-provider-${provider.name}").assertExists()
            }
            rule.onNodeWithContentDescription("model-provider-DEEPSEEK").performClick()
            rule.runOnIdle {
                assertEquals(ModelProviderPreset.DEEPSEEK, model.modelSettings.value.provider)
                assertEquals(ModelProviderPreset.DEEPSEEK.defaultEndpoint, model.modelSettings.value.endpoint)
            }
            rule.onNodeWithText("自动朗读").assertDoesNotExist()
            openSettingsCategory("control")
            rule.onNodeWithContentDescription("gimbal-sensitivity-selector").assertExists()
            openSettingsCategory("lights")
            val original = model.controlSettings.value.remoteLeds.standby
            rule.onNodeWithContentDescription("remote-led-standby").performScrollTo().performClick()
            rule.onNodeWithTag("led-brightness").performTouchInput {
                swipe(center.copy(x = width - 12f, y = height - 24f), center.copy(x = width * 0.5f, y = height - 24f))
            }
            rule.onNodeWithTag("led-color-apply").performClick()
            rule.runOnIdle { assertTrue(original != model.controlSettings.value.remoteLeds.standby) }
            rule.onNodeWithContentDescription("remote-led-talking").performScrollTo().assertExists()
            if (rule.onAllNodesWithTag("settings-back").fetchSemanticsNodes().isNotEmpty()) rule.onNodeWithTag("settings-back").performClick()
            rule.onNodeWithContentDescription("restore-default-settings").assertIsEnabled().performClick()
            rule.onNodeWithText("恢复所有默认设置？").assertIsDisplayed()
            rule.onNodeWithText("取消").performClick()
            rule.onNodeWithText("恢复所有默认设置？").assertDoesNotExist()
        } finally { model.close() }
    }

    @Test fun settingsRenderAndOfferAModelOutsideTheBuiltInCatalog() {
        val model = testConsoleModel()
        try {
            model.modelSettings.value = ModelSettings(model = "qwen-plus")

            rule.setContent {
                WorkbenchTheme {
                    Box(Modifier.requiredSize(393.dp, 740.dp)) { SettingsPage(model) }
                }
            }

            // The id lives in one field and the picker offers it alongside the built-in catalog.
            openSettingsCategory("model")
            rule.onNodeWithTag("model-picker").performScrollTo().performClick()
            rule.onNodeWithTag("model-option-qwen-plus").assertExists()
        } finally { model.close() }
    }

    @Test fun remoteModelCatalogLoadsOnlyWhenTheModelSelectorOpens() {
        var clientCreations = 0
        val model = testConsoleModel(createAgentHttpClient = {
            clientCreations += 1
            error("Offline model catalog fixture")
        })
        try {
            model.modelSettings.value = ModelSettings(apiKey = "test-key")
            rule.setContent {
                WorkbenchTheme {
                    Box(Modifier.requiredSize(393.dp, 740.dp)) { SettingsPage(model) }
                }
            }

            rule.runOnIdle { assertEquals(0, clientCreations) }
            openSettingsCategory("model")
            openSettingsCategory("model")
            rule.onNodeWithTag("model-picker").performScrollTo().performClick()
            rule.waitUntil(3_000) { clientCreations == 1 }
            rule.onNodeWithText("获取模型列表失败", substring = true).assertExists()
        } finally { model.close() }
    }

    @Test fun dropdownRendersAnUnavailableCurrentValueInsteadOfCrashing() {
        rule.setContent {
            WorkbenchTheme {
                SettingsDropdown(
                    label = "Model",
                    tag = "unavailable-value",
                    selected = "custom-model",
                    values = listOf("built-in" to "Built-in"),
                    change = {},
                )
            }
        }

        rule.onNodeWithContentDescription("unavailable-value-selector")
            .assertTextContains("custom-model")
    }

    @Test fun connectingUsesOnlyTheHeaderStatus() {
        val model = testConsoleModel()
        try {
            model.state.value = model.state.value.copy(
                busy = true,
                connecting = true,
                statusMessage = uiText(Res.string.automatically_finding_robot),
            )
            rule.setContent { WorkbenchTheme {
                Box(Modifier.requiredSize(393.dp, 740.dp)) {
                    Console(model, mutableStateOf(EditorDocument()))
                }
            } }
            rule.onNodeWithTag("connection-status").assertTextContains("连接中")
            rule.onNodeWithTag("automatic-connection-progress").assertDoesNotExist()
            rule.onNodeWithText("正在自动识别并连接机器人…", substring = false).assertDoesNotExist()
            snapshot("phone-device-connecting")
        } finally { model.close() }
    }

    @Test fun actualEditorInputAndDiscardDialog() {
        val model = testConsoleModel()
        val document = mutableStateOf(EditorDocument())
        try {
            rule.setContent { WorkbenchTheme { Console(model, document) } }
            rule.onNodeWithContentDescription("脚本").performClick()
            rule.onNodeWithTag("script-new").performClick()
            rule.onNodeWithTag("script-editor").performTextReplacement("def start():\n    pass\n")
            rule.onNodeWithText("新脚本 · 未保存").assertExists()
            rule.onNodeWithTag("script-run").assertIsNotEnabled()
            rule.onNodeWithText("运行脚本").assertDoesNotExist()
            rule.onNodeWithTag("script-more").performClick()
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
        val model = testConsoleModel()
        try {
            rule.setContent { WorkbenchTheme { Console(model, mutableStateOf(EditorDocument())) } }
            rule.onNodeWithContentDescription("诊断").performClick()
            rule.onNodeWithTag("manual-connect").performClick()
            rule.onNodeWithText("连接", substring = false).performClick()
            rule.waitUntil(timeoutMillis = 3000) {
                rule.onAllNodesWithText("请指定机器人 IPv4 地址").fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithText("未连接", substring = false).assertExists()
        } finally { model.close() }
    }

    @Test fun receivedFramesReachActualTelemetryAndMessageViews() {
        val model = testConsoleModel()
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
            model.state.value = model.state.value.copy(connected = true, connectedAddress = "192.0.2.1", scriptTitle = "fixture",
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
            rule.onNodeWithTag("script-console").assertIsDisplayed()
            rule.onNodeWithText("fixture robot message").assertExists()
            rule.onNodeWithTag("script-status-strip").assertIsDisplayed()
        } finally { model.close() }
    }

    @Test fun unconfirmedStopKeepsTheRunNoticeVisibleWithoutASecondStop() {
        Localization.initialize("zh", null)
        val model = testConsoleModel()
        try {
            model.state.value = model.state.value.copy(
                connected = true,
                scriptTitle = "好奇哨兵",
                scriptRunPhase = ScriptRunPhase.STOP_UNCONFIRMED,
                scriptMessage = uiText(Res.string.stop_command_sent_robot_stop_is_unconfirmed),
            )
            rule.setContent { WorkbenchTheme {
                Box(Modifier.requiredSize(393.dp, 740.dp)) { Console(model, mutableStateOf(EditorDocument())) }
            } }
            rule.onNodeWithTag("script-run-banner").assertIsDisplayed()
            rule.onNode(
                hasText("停止命令已发送；未获得机内停止确认。") and
                    hasAnyAncestor(hasTestTag("script-run-banner")),
                useUnmergedTree = true,
            ).assertIsDisplayed()
            rule.onNodeWithTag("script-banner-stop").assertDoesNotExist()
            snapshot("phone-agent-stop-unconfirmed-banner")
        } finally { model.close() }
    }

    @Test fun activeScriptAndLatestOutputRemainVisibleAcrossPages() {
        val model = testConsoleModel()
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
            rule.onNodeWithTag("script-console").assertIsDisplayed()
            rule.onNodeWithTag("script-run-log").assertIsDisplayed()
            rule.onNodeWithText("运行日志").assertIsDisplayed()
            rule.onNodeWithText("运行标识：$runId").assertIsDisplayed()
            // The console is inline, so the library the user was on is still there.
            rule.onNodeWithText("我的脚本").assertIsDisplayed()
            snapshot("active-script-run-phone")
            rule.runOnIdle { width.value = 320.dp; height.value = 568.dp }
            rule.waitForIdle()
            rule.onNodeWithTag("script-console").assertIsDisplayed()
            rule.onNodeWithTag("script-run-log").assertIsDisplayed()
            rule.onNodeWithText("Sentry scan 2/3: left").assertIsDisplayed()
            snapshot("active-script-run-compact-phone")
            rule.runOnIdle { width.value = 1040.dp; height.value = 760.dp }
            rule.waitForIdle()
            rule.onNodeWithTag("script-console").assertIsDisplayed()
            rule.onNodeWithText("Sentry scan 2/3: left").assertIsDisplayed()
            snapshot("active-script-run-desktop")
            rule.runOnIdle { width.value = 740.dp; height.value = 393.dp }
            rule.waitForIdle()
            rule.onNodeWithTag("script-console").assertIsDisplayed()
            rule.onNodeWithTag("script-run-log").assertIsDisplayed()
            rule.onNodeWithText("Sentry scan 2/3: left").assertIsDisplayed()
            snapshot("active-script-run-landscape")
        } finally { model.close() }
    }

    @Test fun presetCanBeSavedRenamedAndDeletedFromScriptLibrary() {
        Localization.initialize("zh", null)
        val model = testConsoleModel()
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
            rule.onNodeWithText("本地").assertDoesNotExist()
            // List management lives behind a long press, so the card itself stays button-free.
            rule.onNodeWithTag("script-card-$id").performTouchInput { longClick() }
            rule.onNodeWithTag("script-rename-$id").performClick()
            rule.onNodeWithContentDescription("script-name").performTextReplacement("电量检查")
            rule.onNodeWithText("保存", substring = false).performClick()
            rule.waitUntil(3_000) { model.scriptLibrary.state.value.scripts.singleOrNull()?.name == "电量检查" }
            rule.onNodeWithTag("script-back").performClick()
            rule.onNodeWithTag("script-card-$id").performTouchInput { longClick() }
            // The menu row is the confirmation: no second dialog is stacked on top of it.
            rule.onNodeWithTag("script-delete-$id").performClick()
            rule.waitUntil(3_000) { model.scriptLibrary.state.value.scripts.isEmpty() }
            rule.onNodeWithText("删除脚本").assertDoesNotExist()
            rule.onNodeWithTag("script-library").assertIsDisplayed()
        } finally {
            model.close()
            Localization.initialize("zh", null)
        }
    }

    @Test fun shortcutEditorCapturesAChordWithoutPopup() {
        Localization.initialize("zh", null)
        val model = testConsoleModel()
        try {
            rule.setContent { WorkbenchTheme { Box(Modifier.requiredSize(393.dp, 740.dp)) { SettingsPage(model) } } }
            openSettingsCategory("shortcuts")
            rule.onNodeWithText("对话快捷键").assertIsDisplayed()
            rule.onNodeWithText("Enter", substring = false).assertIsDisplayed()
            rule.onNodeWithText("Shift+Enter", substring = false).assertIsDisplayed()
            snapshot("phone-settings-shortcuts")
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
