package cn.elonzh.hanppie.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Portable message catalog, shared by Compose UI and non-composable service messages. */
internal object Localization {
    var choice by mutableStateOf("system"); private set
    var systemLanguage by mutableStateOf("zh"); private set
    private var persist: (String) -> Unit = {}
    val english get() = (if(choice == "system") systemLanguage else choice) != "zh"
    val languageTag get() = if(english) "en-US" else "zh-CN"
    fun initialize(systemLanguage: String, saved: String?, save: (String) -> Unit = {}) {
        this.systemLanguage = if(systemLanguage.startsWith("zh")) "zh" else "en"
        choice = saved?.takeIf { it in listOf("system","zh","en") } ?: "system"
        persist = save
    }
    fun select(value: String) {
        require(value in listOf("system","zh","en"))
        persist(value)
        choice = value
    }
    val messages: Map<String,String> = CATALOG.trimIndent().lines().filter { it.isNotBlank() }.associate {
        val pair = it.split("|",limit=2)
        require(pair.size == 2)
        pair[0] to pair[1]
    }
    fun text(key: String, vararg args: Any?): String {
        val canonical = if(key in messages) key else messages.entries.firstOrNull { it.value == key }?.key ?: key
        val template = if(english) messages[canonical] ?: canonical else canonical
        return Regex("\\{(\\d+)\\}").replace(template) { m -> args.getOrNull(m.groupValues[1].toInt())?.toString() ?: m.value }
    }
}

internal fun tr(key: String, vararg args: Any?) = Localization.text(key, *args)

private const val CATALOG = """
语言|Language
跟随系统|System default
未连接|Disconnected
尚未上传|Not uploaded
连接失效|Connection lost
连接失效；机内执行状态未知|Connection lost; robot execution state unknown
新对话|New chat
说说你想做什么|What would you like to do?
聊一聊，或一起编写机器人脚本。|Chat or create a robot script together.
我|You
工具|Tool
脚本|Script
憨皮|Hanppie
系统|System
朗读|Read aloud
等待确认|Awaiting approval
憨皮正在思考…|Hanppie is thinking…
执行这段脚本？|Run this script?
拒绝|Reject
确认执行|Approve and run
停止朗读|Stop reading
正在识别…|Recognizing…
正在听，请说话…|Listening…
发消息|Message
语音输入|Voice input
取消识别|Cancel recognition
结束录音|Finish recording
取消|Cancel
发送|Send
模型服务|Model service
API 地址|API endpoint
模型|Model
API Key · 仅本次运行|API key · this session only
自动朗读智能体回复|Read assistant replies automatically
自动朗读|Read replies aloud
语音服务|Speech services
设备|Robot
诊断|Debug
对话|Chat
设置|Settings
手动连接|Manual connection
机器人 IPv4|Robot IPv4
AppID · 8 位十六进制|AppID · 8 hex characters
连接|Connect
替换未保存的脚本？|Replace unsaved script?
当前修改尚未保存。|Your changes have not been saved.
返回|Back
选择文件|Choose file
我的机器人|My robot
已连接|Connected
已连接 |Connected
尚未连接|Not connected
断开 / 清理会话|Disconnect / close session
搜索中…|Searching…
搜索设备|Find robots
电量|Battery
接收帧|Packets
手机或电脑需与 S1 连接同一 Wi-Fi|Connect your phone or computer to the same Wi-Fi as S1.
新脚本|New script
 · 未保存| · Unsaved
打开 .py|Open .py
保存 .py|Save .py
允许执行此脚本|Allow this script to run
执行说明|Execution details
脚本可能产生机械动作。编辑后需重新上传；断开连接不保证机内脚本停止。|Scripts may move the robot. Upload again after editing. Disconnecting does not guarantee scripts stop.
上传（不启动）|Upload only
执行已上传脚本|Run uploaded script
停止脚本|Stop script
日志|Logs
遥测|Telemetry
报文|Packets
清空|Clear
原始字段 · 未标定|Raw fields · uncalibrated
暂无记录|No records
S1 设备图标|S1 robot icon
请先停止 Lab 脚本|Stop the Lab script first
遥控模式；需重新上传脚本|Remote control; upload the script again before running
请先启用遥控|Enable remote control first
媒体请求队列已满|Media request queue is full
媒体停止队列已满|Media stop queue is full
请先停止状态未确认的脚本|Stop the script with unknown state first
机器人未连接|Robot is not connected
正在上传|Uploading
正在发送启动命令；结果待确认|Sending start command; result unconfirmed
启动命令已发送（未确认完成）|Start command sent (completion unconfirmed)
上传已确认，启动命令已发送；尚未确认动作执行或完成。|Upload confirmed and start command sent; action execution and completion are unconfirmed.
停止命令已发送|Stop command sent
停止命令已发送；未获得机内停止确认。|Stop command sent; robot stop is unconfirmed.
机器人未连接或应用不在前台|Robot disconnected or app not in foreground
设备正在执行其他操作|Another operation is in progress
上传未确认|Upload unconfirmed
请先取消当前对话，再切换连接或手动操作|Cancel the current chat before changing connections or using manual control
上传失败|Upload failed
监听局域网设备广播…|Listening for robots on the local network…
请先断开当前连接|Disconnect the current session first
连接失败|Connection failed
会话已结束；机内状态未知|Session ended; robot state unknown
连接已关闭；断开连接不代表任意机内脚本已经停止|Connection closed; scripts on the robot may still be running
上传已确认，尚未启动|Upload confirmed; not started
heading_like（未标定）|heading_like (uncalibrated)
连接已关闭；机内状态未知|Connection closed; robot state unknown
返回控制台|Back to console
全屏驾驶舱|Fullscreen cockpit
控制台|Console
全屏|Fullscreen
退出|Exit
底盘相对镜头朝向|Chassis heading relative to camera
底盘朝向|Chassis heading
等待朝向|Awaiting heading
镜头前进|Camera-relative
降档|Shift down
升档|Shift up
底盘|Chassis
切换弹药|Switch ammo
水弹 ⇄|Gel ⇄
红外 ⇄|IR ⇄
水弹单发|Fire one gel bead
红外开火|Fire infrared
◎ 开火|◎ Fire
云台|Gimbal
● 遥控中|● Active
○ 待机|○ Standby
停止遥控|Stop remote control
启用遥控|Enable remote control
■ 停止 · Esc|■ Stop · Esc
开始遥控|Start control
WASD  移动    Q/E  降/升档    SHIFT  缓行    方向键  云台    R  弹药    SPACE  开火|WASD Move · Q/E Gears · Shift Creep · Arrows Aim · R Ammo · Space Fire
未检测到系统语音引擎|No system speech engine found
macOS 系统语音|macOS speech
Windows 系统语音|Windows speech
单次播报最多 4000 个字符|Speech is limited to 4,000 characters per reply
系统语音引擎不可用|System speech engine unavailable
视频未开启|Video off
等待视频帧…|Waiting for video…
媒体启动失败|Could not start media
音频启动失败|Could not start audio
机器人实时画面|Robot live video
关闭视频|Stop video
开启视频|Start video
静音|Mute
监听机器人|Listen
视频输入积压，请重新开启视频|Video queue overflow; restart video
音频输入积压，请重新开启监听|Audio queue overflow; restart listening
视频解码器已退出|Video decoder exited
视频解码失败|Video decoding failed
音频解码器已退出|Audio decoder exited
音频播放失败|Audio playback failed
媒体传输失败|Media transport failed
退出 Hanppie？|Quit Hanppie?
未保存修改将丢失。断开连接不保证机内脚本停止。|Unsaved changes will be lost. Disconnecting does not guarantee robot scripts stop.
仍然退出|Quit anyway
保存 Python 脚本|Save Python script
打开 Python 脚本|Open Python script
语音识别超时，请重试|Speech recognition timed out. Try again.
系统未提供语音识别服务；可使用输入法语音输入或安装兼容的系统识别服务|No speech recognizer installed. Use keyboard dictation or install a compatible service.
未配置可用识别服务，请在对话设置中选择语音识别服务|Select an available recognition service in Settings.
没有识别到文字，请重试|No text recognized. Try again.
识别服务连接失败（5），请在对话设置中检查或更换语音识别服务|Speech service connection failed (5). Check or change the service in Settings.
憨皮的麦克风权限未授予，请在应用权限设置中允许录音|Hanppie has no microphone permission. Allow recording in app permissions.
识别服务拒绝录音（9）：系统默认识别服务未配置。请打开设置 → 语音服务 → 系统默认语音输入进行配置|Recording denied (9): no default recognizer. Configure it in Settings → Speech services → Default voice input.
识别服务拒绝录音（9），憨皮已获麦克风权限。请在设置 → 语音服务中检查服务权限及系统默认语音输入|Recording denied (9), although Hanppie has microphone permission. Check service permissions and default voice input in Settings → Speech services.
没有听清，请重试|Could not understand. Try again.
系统语音识别网络不可用|Speech recognition network unavailable
系统语音服务正忙，请稍后重试|Speech service busy. Try again later.
系统语音服务不支持当前语言或缺少语音包|Language unsupported or speech language pack missing
无法启动系统语音识别，请检查麦克风权限和语音服务|Could not start speech recognition. Check microphone permissions and speech services.
麦克风权限未授予；仍可直接输入文字|Microphone permission denied; you can still type
请先连接机器人所在的 Wi-Fi|Connect to the robot's Wi-Fi first
语音识别|Speech recognition
无法打开识别服务权限设置|Could not open recognition service permissions
无法打开系统默认语音输入设置|Could not open default voice input settings
系统默认语音输入|Default voice input
无法打开系统朗读设置|Could not open text-to-speech settings
系统朗读设置|Text-to-speech settings
使用手机麦克风|Use phone microphone
语音由系统服务识别，服务可能联网处理声音。文字会回填到输入框，由你确认发送；应用不保存录音。|Your system speech service may process audio online. Recognized text appears in the composer for review before sending. The app does not save recordings.
继续|Continue
退出憨皮？|Quit Hanppie?
视频帧超过解码缓冲区|Video frame exceeds decoder buffer
等待音频…|Waiting for audio…
音频|Audio
正在初始化系统语音…|Initializing system speech…
系统语音不可用|System speech unavailable
请在系统语音设置中配置引擎|Configure an engine in system speech settings
系统文字转语音|System text-to-speech
请安装当前语言的离线语音包|Install an offline speech pack for this language
系统播报失败|System speech failed
无法开始播报|Could not start speaking
{0} 档 · 缓行|Gear {0} · Creep
{0} 档|Gear {0}
{0} 摇杆|{0} joystick
视频已解码 {0} 帧|Video: {0} decoded frames
音频已解码 {0} 字节|Audio: {0} decoded bytes
首字 {0} · 本轮 {1}ms|First token {0} · Turn {1}ms
{0} · 已选择|{0} · Selected
使用 {0}|Use {0}
{0} · 权限设置|{0} · Permissions
系统播报失败：{0}|System speech failed: {0}
系统语音识别失败（{0}）|Speech recognition failed ({0})
系统语音进程退出：{0}|Speech process exited: {0}
请填写 HTTPS API 地址|Enter an HTTPS API endpoint
请填写模型和 API Key|Enter a model and API key
消息过长|Message too long
对话上下文已满，请开启新对话|Context full. Start a new chat.
模型输出中断，未执行不完整指令|Model output interrupted; incomplete instructions were not executed
模型输出被截断|Model output truncated
模型没有返回可见回复|The model returned no visible reply
已达到本轮工具循环上限，请检查执行记录后继续|Tool iteration limit reached. Review the execution history before continuing.
本轮超过 120 秒，已取消。请检查工具记录；机内脚本不会因此自动停止。|Turn timed out after 120 seconds. Review tool history; robot scripts do not stop automatically.
本轮已取消；取消对话不等于停止机内脚本。|Turn canceled; canceling chat does not stop robot scripts.
请先停止已启动的脚本，再上传新脚本|Stop the running script before uploading another
上传期间机器人连接已断开|Robot disconnected during upload
Lab 会话未就绪|Lab session not ready
启动命令已经发送；如需重跑，请先停止脚本|Start already sent; stop the script before running it again
请先上传脚本|Upload a script first
机器人未连接，无法发送停止命令|Robot disconnected; cannot send stop
FTP 二进制模式失败|FTP binary mode failed
请指定机器人 IPv4 地址|Specify a robot IPv4 address
AppID 必须是 8 位十六进制字符|AppID must contain 8 hex characters
遥控未启用|Remote control not enabled
会话已经打开|Session already open
机器人未确认 App 会话，请检查网络或关闭其他控制端|Robot did not acknowledge the App session. Check the network or close other controllers.
初始化期间连接中断|Connection interrupted during initialization
机器人 AppID 与目标不一致|Robot AppID does not match the target
会话已关闭|Session closed
5 秒未收到机器人数据，连接已失效|No robot data for 5 seconds; connection lost
无法启动 FFmpeg；请安装或设置 HANPPIE_FFMPEG：{0}|Cannot start FFmpeg. Install it or set HANPPIE_FFMPEG: {0}
媒体错误：{0}|Media error: {0}
视频错误：{0}|Video error: {0}
音频错误：{0}|Audio error: {0}
音频播放失败：{0}|Audio playback failed: {0}
媒体请求失败：{0}|Media request failed: {0}
退出遥控：{0}|Leaving remote control: {0}
水弹单发直控命令已发送 seq={0}；未确认物理发射，不自动重试|Gel fire command sent, seq={0}; physical firing unconfirmed, no automatic retry
错误：{0}|Error: {0}
发现 {0} 台设备；已连接设备可能不广播，可手动填写目标|Found {0} robots; connected robots may not broadcast. Enter a target manually if needed.
正在连接 {0}|Connecting to {0}
已连接 {0}|Connected to {0}
本轮未完成（{0}）。请检查网络、模型配置与工具记录。|Turn incomplete ({0}). Check network, model settings and tool history.
读取状态|Read status
执行 Lab 脚本|Run Lab script
用户拒绝执行；未上传、未启动。|User rejected execution; nothing uploaded or started.
代码已显示在对话中。|Code is shown in the conversation.
脚本为空或超过 32KB 字符限制|Script is empty or exceeds the 32K character limit
脚本待确认，尚未上传或启动。|Script awaiting approval; not uploaded or started.
{0}：已开始，结果尚未知|{0}: started, result unknown
{0} 失败；请检查设备状态，勿自动重试。{1}|{0} failed; check robot state and do not retry automatically. {1}
"""
