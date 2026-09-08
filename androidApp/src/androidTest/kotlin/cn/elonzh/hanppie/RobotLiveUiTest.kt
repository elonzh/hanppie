package cn.elonzh.hanppie

import android.os.ParcelFileDescriptor
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assume.assumeTrue
import java.io.File

/** Explicit target only. Default path receives video, but never moves or fires. */
class RobotLiveUiTest {
    @get:Rule val rule = createEmptyComposeRule()
    @Test fun targetConnectionAndVideo() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val ip = args.getString("robotIp")
        val appId = args.getString("robotAppId")
        assumeTrue(ip != null && appId != null)
        ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand("am start -W -n cn.elonzh.hanppie/.MainActivity")).use { it.readBytes() }
        rule.waitUntil(10000) { rule.onAllNodesWithText("手动连接").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("手动连接").performClick()
        rule.onNodeWithText("机器人 IPv4").performTextReplacement(ip!!)
        rule.onNodeWithText("AppID · 8 位十六进制").performTextReplacement(appId!!)
        rule.onNodeWithText("连接", substring = false).performClick()
        try {
            rule.waitUntil(20000) { rule.onAllNodesWithText("开启视频").fetchSemanticsNodes().isNotEmpty() }
        } catch (failure: Throwable) {
            println(rule.onRoot().printToString())
            throw failure
        }
        rule.onNodeWithText("开启视频").performClick()
        rule.waitUntil(15000) { rule.onAllNodesWithText("视频已解码", substring = true).fetchSemanticsNodes().isNotEmpty() }
        var videoView: android.view.SurfaceView? = null
        inst.runOnMainSync {
            fun find(view: android.view.View): android.view.SurfaceView? {
                if (view is android.view.SurfaceView) return view
                if (view is android.view.ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
                return null
            }
            val activities = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
            videoView = activities.firstNotNullOfOrNull { find(it.window.decorView) }
        }
        val view = requireNotNull(videoView)
        val decoded = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val copied = java.util.concurrent.CountDownLatch(1)
        var copyResult = -1
        android.view.PixelCopy.request(view, decoded, { copyResult = it; copied.countDown() }, android.os.Handler(android.os.Looper.getMainLooper()))
        check(copied.await(5, java.util.concurrent.TimeUnit.SECONDS) && copyResult == android.view.PixelCopy.SUCCESS)
        File(inst.targetContext.getExternalFilesDir(null), "robot-camera.png").outputStream().use { decoded.compress(Bitmap.CompressFormat.PNG,100,it) }
        decoded.recycle()
        if (args.getString("remote") == "1") {
            rule.onNodeWithContentDescription("启用遥控").performClick()
            rule.waitUntil(10000) { rule.onAllNodesWithContentDescription("停止遥控").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("remote-surface").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Q) }
            rule.onNodeWithText("1 档").assertIsDisplayed()
            rule.onNodeWithTag("remote-surface").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.E) }
            rule.onNodeWithText("2 档").assertIsDisplayed()
            rule.onNodeWithTag("remote-surface").performKeyInput { keyDown(androidx.compose.ui.input.key.Key.ShiftLeft) }
            rule.onNodeWithText("2 档 · 缓行").assertIsDisplayed()
            rule.onNodeWithTag("remote-surface").performKeyInput { keyUp(androidx.compose.ui.input.key.Key.ShiftLeft) }
            rule.onNodeWithContentDescription("云台 摇杆").performTouchInput {
                down(center); moveTo(center + androidx.compose.ui.geometry.Offset(18f,0f))
            }
            Thread.sleep(250)
            rule.onNodeWithContentDescription("云台 摇杆").performTouchInput { up() }
            rule.onNodeWithTag("remote-surface").performKeyInput { keyDown(androidx.compose.ui.input.key.Key.DirectionLeft) }
            Thread.sleep(120)
            rule.onNodeWithTag("remote-surface").performKeyInput { keyUp(androidx.compose.ui.input.key.Key.DirectionLeft) }
            rule.onNodeWithTag("remote-surface").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.R) }
            rule.onNodeWithContentDescription("水弹单发").assertIsDisplayed()
            rule.onNodeWithTag("remote-surface").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.R) }
            rule.onNodeWithContentDescription("红外开火").assertIsDisplayed()
            rule.onNodeWithContentDescription("停止遥控").performClick()
            rule.onNodeWithContentDescription("启用遥控").assertIsDisplayed()
        }
        if (args.getString("audio") == "1") {
            rule.onNodeWithText("监听机器人").performClick()
            rule.waitUntil(15000) { rule.onAllNodesWithText("音频已解码", substring = true).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("静音").performClick()
        }
        val bitmap = requireNotNull(inst.uiAutomation.takeScreenshot())
        File(inst.targetContext.getExternalFilesDir(null), "robot-video.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
        rule.onNodeWithText("关闭视频").performClick()
        if (args.getString("agent") == "1") {
            rule.onNodeWithContentDescription("返回控制台").performClick()
            val keyFile = File(inst.targetContext.filesDir, "live-test-key")
            val apiKey = keyFile.readText().trim()
            keyFile.delete()
            rule.onNodeWithText("设置").performClick()
            rule.onNodeWithText("API Key").performTextReplacement(apiKey)
            rule.onNodeWithText("对话").performClick()
            val script = """def start():
    builtins = rm_define.__dict__["__builtins__"]
    importer = builtins["__import__"] if isinstance(builtins, dict) else builtins.__import__
    module = importer("rm_module", globals(), locals(), [], 0)
    module.Mobile(chassis_ctrl.event_client).custom_msg_send(0, 0, "HANPPIE_LIVE_2")
"""
            rule.onNodeWithTag("chat-input").performTextReplacement("先读取机器人状态，然后通过 execute_lab_python 执行以下无运动、无发射的测试脚本，不做任何其他动作：\n" + script)
            rule.onNodeWithContentDescription("发送").performClick()
            rule.waitUntil(60000) { rule.onAllNodesWithText("确认执行").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("确认执行").performScrollTo().performClick()
            rule.waitUntil(60000) { rule.onAllNodesWithContentDescription("取消").fetchSemanticsNodes().isEmpty() }
            rule.onNodeWithText("脚本", substring = false).performClick()
            rule.waitUntil(10000) { rule.onAllNodesWithText("type=0 level=0 HANPPIE_LIVE_2", substring = false).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("停止脚本").performClick()
            rule.waitUntil(10000) { rule.onAllNodesWithText("停止命令已发送").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("对话", substring = false).performClick()
            rule.onNodeWithTag("chat-input").performTextReplacement("继续，无运动无发射。在机内计算 7*8，沿用刚才的自定义消息回报方式，将计算结果以 HANPPIE_CALC_56 的格式发回来。不要复用上轮脚本的固定回报内容。")
            rule.onNodeWithContentDescription("发送").performClick()
            rule.waitUntil(60000) { rule.onAllNodesWithText("确认执行").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("确认执行").performScrollTo().performClick()
            rule.waitUntil(60000) { rule.onAllNodesWithContentDescription("取消").fetchSemanticsNodes().isEmpty() }
            rule.onNodeWithText("脚本", substring = false).performClick()
            rule.waitUntil(10000) { rule.onAllNodesWithText("type=0 level=0 HANPPIE_CALC_56").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("停止脚本").performClick()
            rule.onNodeWithText("设备").performClick()
        }
        rule.onNodeWithContentDescription("断开 / 清理会话").performClick()
    }
}
