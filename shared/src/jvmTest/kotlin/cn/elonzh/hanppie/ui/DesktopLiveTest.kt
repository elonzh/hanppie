package cn.elonzh.hanppie.ui

import cn.elonzh.hanppie.resources.*

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.*
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Opt-in device test; mechanical inputs need ALLOW_REMOTE. Never fires projectiles. */
class DesktopLiveTest {
    @get:Rule val rule = createComposeRule()
    @Test fun desktopVideoAudioAndAgent() {
        val ip = System.getenv("HANPPIE_TEST_ROBOT_IP")
        val id = System.getenv("HANPPIE_TEST_APPID")
        Assume.assumeTrue(!ip.isNullOrBlank() && !id.isNullOrBlank())
        val saveModel = System.getenv("HANPPIE_TEST_SAVE_MODEL") == "1"
        val model = ConsoleModel(persistSettings = saveModel)
        try {
            rule.waitUntil(10000) { !model.settingsBusy.value }
            if (saveModel) {
                model.saveSettings()
                rule.waitUntil(10000) { !model.settingsBusy.value }
                assertEquals(cn.elonzh.hanppie.resources.Res.string.settings_saved, model.settingsMessage.value?.resource)
            }
            rule.setContent { WorkbenchTheme {
                Box(Modifier.requiredSize(1040.dp,760.dp)) { Console(model, mutableStateOf(EditorDocument())) }
            } }
            model.connect(ip!!,id!!)
            rule.waitUntil(15000) { model.state.value.connected && !model.state.value.busy }
            rule.waitUntil(5000) { rule.onAllNodesWithText("开启视频").fetchSemanticsNodes().isNotEmpty() }
            val videoStart = System.nanoTime()
            rule.onNodeWithText("开启视频").performClick()
            rule.waitUntil(20000) { rule.onAllNodesWithContentDescription("机器人实时画面").fetchSemanticsNodes().isNotEmpty() }
            println("Desktop video first frame ms=${(System.nanoTime()-videoStart)/1_000_000}")
            screenshot("desktop-live-video")
            rule.onNodeWithText("监听机器人").performClick()
            rule.waitUntil(20000) { rule.onAllNodesWithText("音频已解码", substring=true).fetchSemanticsNodes().isNotEmpty() }
            rule.waitUntil(10000) { rule.onAllNodesWithText("视频已解码 30 帧").fetchSemanticsNodes().isNotEmpty() }
            screenshot("desktop-live-audio")
            if (System.getenv("HANPPIE_TEST_ALLOW_REMOTE") == "1") {
                rule.onNodeWithContentDescription("启用遥控").performClick()
                rule.waitUntil(5000) { model.remoteEnabled.value }
                rule.waitUntil(5000) { model.state.value.values.isNotEmpty() }
                println("Remote idle telemetry=${model.state.value.values}")
                screenshot("remote-before")
                rule.waitUntil(3000) { model.cameraYaw.value != null }
                rule.onNodeWithTag("remote-surface").performKeyInput { keyDown(Key.DirectionUp) }
                rule.waitUntil(2000) { model.remoteInput.value[3] > 0 }
                Thread.sleep(300)
                rule.onNodeWithTag("remote-surface").performKeyInput { keyUp(Key.DirectionUp) }
                rule.waitUntil(2000) { model.remoteInput.value.all { it == 0.0 } }
                Thread.sleep(1000)
                screenshot("remote-pitch-up")
                rule.onNodeWithTag("remote-surface").performKeyInput { keyDown(Key.DirectionDown) }
                rule.waitUntil(2000) { model.remoteInput.value[3] < 0 }
                Thread.sleep(300)
                rule.onNodeWithTag("remote-surface").performKeyInput { keyUp(Key.DirectionDown) }
                rule.waitUntil(2000) { model.remoteInput.value.all { it == 0.0 } }
                Thread.sleep(1000)
                screenshot("remote-pitch-down")
                rule.onNodeWithTag("remote-surface").performKeyInput { keyDown(Key.DirectionLeft) }
                rule.waitUntil(2000) { model.remoteInput.value[4] < 0 }
                Thread.sleep(300)
                rule.onNodeWithTag("remote-surface").performKeyInput { keyUp(Key.DirectionLeft) }
                rule.waitUntil(2000) { model.remoteInput.value.all { it == 0.0 } }
                Thread.sleep(1000)
                screenshot("remote-after-gimbal")
                println("After gimbal telemetry=${model.state.value.values}")
                rule.onNodeWithTag("remote-surface").performKeyInput { keyDown(Key.W) }
                rule.waitUntil(2000) { model.remoteInput.value[0] > 0 }
                Thread.sleep(300)
                rule.onNodeWithTag("remote-surface").performKeyInput { keyUp(Key.W) }
                rule.waitUntil(2000) { model.remoteInput.value.all { it == 0.0 } }
                Thread.sleep(1000)
                screenshot("remote-after-chassis")
                println("After chassis telemetry=${model.state.value.values}")
                for(key in listOf(Key.S,Key.A,Key.D)) {
                    rule.onNodeWithTag("remote-surface").performKeyInput { keyDown(key) }
                    rule.waitUntil(2000) { model.remoteInput.value.take(3).any { it!=0.0 } }
                    Thread.sleep(300)
                    rule.onNodeWithTag("remote-surface").performKeyInput { keyUp(key) }
                    rule.waitUntil(2000) { model.remoteInput.value.all { it==0.0 } }
                    Thread.sleep(700)
                    println("After key $key telemetry=${model.state.value.values}")
                    screenshot("remote-key-${key.toString().substringAfterLast(' ')}")
                }
                Thread.sleep(1000)
                println("Stopped telemetry=${model.state.value.values}")
                for(label in listOf("底盘 摇杆","云台 摇杆")) {
                    rule.onNodeWithContentDescription(label).performTouchInput { down(center); moveTo(center+Offset(16f,0f)) }
                    rule.waitUntil(2000) { model.remoteInput.value.any { it!=0.0 } }
                    Thread.sleep(300)
                    rule.onNodeWithContentDescription(label).performTouchInput { up() }
                    rule.waitUntil(2000) { model.remoteInput.value.all { it==0.0 } }
                    Thread.sleep(1000)
                    screenshot(if(label.startsWith("底盘")) "remote-touch-chassis" else "remote-touch-gimbal")
                }
                rule.onRoot().performKeyInput { pressKey(Key.Escape) }
                rule.waitUntil(3000) { !model.remoteEnabled.value }
                rule.onNodeWithContentDescription("启用遥控").assertIsDisplayed()
            }
            rule.onNodeWithText("静音").performClick()
            rule.onNodeWithText("关闭视频").performClick()
            if (System.getenv("HANPPIE_TEST_ALLOW_LAB") == "1") {
                rule.onNodeWithContentDescription("返回控制台").performClick()
                assertTrue(model.modelSettings.value.apiKey.isNotBlank(), "Real model credentials required for Lab test")
                rule.onNodeWithText("对话").performClick()
                val source = """
                    def start():
                        builtins = rm_define.__dict__["__builtins__"]
                        importer = builtins["__import__"] if isinstance(builtins, dict) else builtins.__import__
                        module = importer("rm_module", globals(), locals(), [], 0)
                        module.Mobile(chassis_ctrl.event_client).custom_msg_send(0, 0, "HANPPIE_DESKTOP_LIVE")
                """.trimIndent()
                model.chat.send("请调用 execute_lab_python 执行以下无运动脚本，源码必须完全照抄：\n$source",model.modelSettings.value)
                rule.waitUntil(60000) { model.chat.state.value.approval != null || !model.chat.state.value.running }
                assertNull(model.chat.state.value.error, "Model request failed")
                assertEquals(source.trim(), model.chat.state.value.approval?.trim(),
                    "Expected an execution proposal; replies=${model.chat.state.value.lines}")
                model.chat.approve(true)
                rule.waitUntil(60000) { !model.chat.state.value.running }
                rule.waitUntil(15000) { model.state.value.scriptMessages.any { it.contains("type=0 level=0 HANPPIE_DESKTOP_LIVE") } }
                assertNull(model.chat.state.value.error)
                screenshot("desktop-live-agent")
                println("Desktop live agent confirmed by robot custom message; elapsedMs=${model.chat.state.value.elapsedMs}")
                model.stop()
                rule.waitUntil(10000) { !model.state.value.busy }
            }
        } finally { model.haltRemote(); model.close() }
    }
    private fun screenshot(name: String) {
        val image = rule.onRoot().captureToImage()
        val pixels = IntArray(image.width*image.height); image.readPixels(pixels)
        val output = BufferedImage(image.width,image.height,BufferedImage.TYPE_INT_ARGB)
        output.setRGB(0,0,image.width,image.height,pixels,0,image.width)
        val file = File("build/reports/ui/$name.png"); file.parentFile.mkdirs()
        ImageIO.write(output,"png",file)
    }
}
