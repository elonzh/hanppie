# Hanppie（憨皮）

[简体中文](./README.md) | [English](./README_EN.md)

[![CI](https://github.com/elonzh/hanppie/actions/workflows/ci.yml/badge.svg)](https://github.com/elonzh/hanppie/actions/workflows/ci.yml)
[![Python](https://img.shields.io/badge/Python-3.10-blue)](https://www.python.org/)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](./LICENSE)

Hanppie 是一个面向 DJI RoboMaster 系列机器人的开源保存与电脑编程工具箱。项目目标是在不依赖手机 App 的前提下，恢复可审计、可回滚的连接、编程、遥测和远程控制能力。当前实机验证均来自 S1；这不是封闭的支持型号列表，EP 等型号在完成逐项验证前也不宣称受支持。

项目尚处于 Alpha 阶段，不隶属于 DJI，也未获得 DJI 背书。

## 文档分工

为避免同一结论被复制到多处，仓库按以下边界维护文档：

| 文档 | 唯一职责 |
| --- | --- |
| [Hanppie 技术架构](./docs/architecture.md) | 当前项目结构、实现机制、能力状态和安全边界的唯一权威来源 |
| [RoboMaster S1 架构、协议与调查](./docs/architecture-robomaster.md) | S1 原生硬件、固件、协议和外部生态的唯一权威来源 |
| [智能体运行时演进方案](./docs/agent-runtime-plan.md) | 已实施基础切片及后续事件协议、恢复、Koog 与供应商演进边界 |
| [对话产品需求](./docs/conversation-product-requirements.md) | 当前模型配置、对话管理、交互、快捷键与分阶段产品验收标准 |
| [对话智能体产品设计](./docs/conversation-agent-product-design.md) | 当前 P0 工具及后续智能体职责、风险策略与交互闭环 |
| README | 安装、命令用法和文档导航 |
| [初次恢复记录](./docs/s1-live-debug-2026-08-29.md)、[App/Lab 回归记录](./docs/s1-live-regression-2026-08-30.md)、[AppEnvelope 直控记录](./docs/s1-direct-control-2026-08-31.md) | 特定日期的命令、输出、故障和测量证据，不维护当前结论 |
| [早期调研报告](./docs/robomaster-s1-revival-report.md) | S.BUS、SocketCAN、vcan、ROS 2 和备选路线的历史调研 |
| [`src/robomaster/UPSTREAM.md`](./src/robomaster/UPSTREAM.md) | 内置 DJI SDK fork 的来源与改动边界 |

使用实机前请先阅读技术架构中的[安全与恢复模型](./docs/architecture.md#113-安全与恢复模型)；当前验证状态只查看其中的[能力矩阵](./docs/architecture.md#111-当前能力矩阵)。

## 安装

### Android 程序

Android 和桌面均可在「设置 → 语言」选择跟随系统、简体中文或 English；选择自动保存，即时生效，无需重新连接机器人。

在 IDEA 的 Android SDK Manager 中安装 API 37 和 Build Tools 36.1.0。项目根目录的 `local.properties` 设置 `sdk.dir` 指向同一 SDK 目录（不要提交该文件）。

```bash
task android:check
```

APK 位于 `androidApp/build/outputs/apk/debug/androidApp-debug.apk`。手机开启 USB 调试并确认 USB 安装后，可安装测试版：

```bash
adb -s <手机设备ID> install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

打开“憨皮”后，软件只会按内部保存的连接记录自动尝试一次连接；失败、失联或窗口重新激活都不会反复重试，后续由用户主动连接。设备页不会展示扫描结果或历史设备列表，连接成功后只呈现当前机器人、日常操作和状态。首次使用、添加机器人或切换网络拓扑时进入“添加或更换机器人”：直连模式会引导手机加入机器人机身标签所示的热点，并在收不到广播时探测直连协议默认地址；路由器模式直接在 Hanppie 内输入 Wi-Fi 名称和密码并生成机器人可扫描的配网二维码，不需要 RoboMaster 官方 App。AppID、最近连接过的设备以及路由器配置保存在应用私有数据中，仅供自动决策，密码不写入日志和诊断；只有开发或故障排查才从“诊断”页使用手动 IPv4/AppID。连接后可从“诊断”→“FTP”浏览机器人经匿名 FTP 开放的内部维护数据区，并快速打开、上传、下载、重命名、新建或删除空目录；快速打开先下载到应用临时目录再交给系统，同名上传自动保留为新名称，Lab 当前槽位只读。当前 S1 实机的原厂 FTP 不支持 UTF-8，上传的非 ASCII 文件名会先转换为安全英文名；实机会变换非空上传内容，下载和快速打开取得的是原始机内数据，关联应用不一定能识别。该页面不是普通音频库或系统分区浏览器。“脚本”页可以新建、导入、保存、重命名、删除和导出本机脚本，也可从预置脚本创建副本；导入/导出 `.py` 使用系统文件选择器。在 Android“设置”→“通用”→“语音服务”中可以选择系统语音识别服务。

Android 模拟器使用默认 NAT/DHCP 即可尝试直连机器人，不要把虚拟 Wi-Fi 手动改为家庭局域网的静态 IP；开发或排障时可在“诊断”页使用“手动连接”，填写机器人的真实局域网 IPv4 和 AppID。macOS 桌面应用首次查找设备时会请求本地网络权限；拒绝后可在“系统设置 → 隐私与安全性 → 本地网络”中重新允许 Hanppie。运行 Android 模拟器时，还需允许启动模拟器的应用访问局域网。若电脑能连接而模拟器报 `No route to host`，先完全退出模拟器，再使用现有 SDK 冷启动（保留应用及数据，不使用 Wipe Data）：

```bash
"$ANDROID_HOME/emulator/emulator" -list-avds
"$ANDROID_HOME/emulator/emulator" -avd <上一步的设备名称> -no-snapshot-load
```

`ANDROID_HOME` 指向 IDEA 使用的同一套 SDK。不要为此额外安装 SDK。当前验证边界见[客户端架构](./docs/architecture.md#130-单体仓库与-kotlin-多平台客户端)。

编辑脚本时，键盘可用 `Tab` / `Shift+Tab` 缩进，`Ctrl/Cmd+Z` 撤销，`Ctrl/Cmd+Shift+Z` 重做，`Ctrl/Cmd+F` 查找，`Ctrl/Cmd+S` 保存到脚本库。查找区分大小写，点击替换只替换当前选中的匹配项；保存不会运行机器人。

### 桌面程序

Android 和桌面连接后进入驾驶舱即建立直控通道，使用左侧底盘、右侧云台摇杆；未输入时只发送零值。触摸端可在左下直接选择五档速度，在右下切换红外/水弹并发射当前弹药。键盘使用 WASD 移动、Q/E 原地转向、数字 `1–5` 选档、方向键控制云台、G 切换弹药、空格发射、Esc 返回控制台；云台向外转过一定角度时底盘开始软件联动，松手停止；机械效果仍待实机验证，见[架构边界](./docs/architecture.md)。默认红外，切换弹药本身不会发射；水弹使用直控单发命令，不运行 Lab 脚本。离开驾驶舱或桌面窗口失焦会立即归零并退出当前直控；切回前台且驾驶舱取得焦点后自动恢复通道，按键和摇杆从零开始。机器人音视频支持下行播放，并提供按住采集、松开发送的扬声器短片对讲，不是实时双工通话。

桌面音视频需要安装支持 H.264、Opus 的 FFmpeg，将 `ffmpeg` 加入应用进程的 PATH，或以 `HANPPIE_FFMPEG` 环境变量指定可执行文件绝对路径（macOS Homebrew 通常为 `/opt/homebrew/bin/ffmpeg`）。分发包不包含 FFmpeg；Android 使用系统解码器，不需要 FFmpeg。

安装 JDK 21，在仓库根目录运行（Windows 将 `./gradlew` 替换为 `gradlew.bat`）：

```bash
./gradlew :desktopApp:run
./gradlew :packages:robot-core:desktopTest :shared:jvmTest
./gradlew :desktopApp:createDistributable
```

分发程序位于 `desktopApp/build/compose/binaries/main/app/`，包含运行时，不需要安装 Python。
打开后默认只自动尝试一次最近的机器人；连接记录只在内部用于自动决策，不显示为可选列表。首次配置、添加或更换机器人时进入“添加或更换机器人”：直连模式引导电脑加入机器人热点，随后自动发现或探测默认地址；路由器模式由 Hanppie 本地生成配网二维码，等待机器人扫码、建立会话并确认配网。手动 IPv4/AppID 只在“诊断”页提供给开发和故障排查。
在“脚本”页从“我的脚本”或“预置脚本”进入编辑器；“保存到脚本库”持久化当前脚本，“导出 .py”另存副本。预置脚本包含电量心情秀、彩虹音阶、好奇哨兵、方形巡演和胜利舞会，每次运行会执行有限轮次；涉及云台或底盘动作的脚本会直接标明风险，使用前仍需检查完整源码并留出安全空间。
连接机器人后直接点击“运行脚本”；软件会先校验源码、把当前源码覆盖上传到唯一的 `python_raw.dsp`，再发送启动命令，不把这个单一运行槽位包装成程序管理。Lab 脚本直接使用机内提供的 `time` 等对象，不写普通 `import`。启动后进入以运行状态和 `log_ctrl.print_msg(...)` 输出为主体的运行界面；返回编辑器不会停止脚本，切换到其他页面后仍可从全局状态条查看并返回。运行页显示可复制的运行标识以及上传、启动和回报接收诊断。启动命令发出后 10 秒内没有收到机内 `STARTED` 回报会标为状态未知并保留停止入口，不会永久等待或自动重试。正常返回或报错会自动结束机内运行态；断开连接后无法确认机内状态，也会明确标为未知。
能力与验证边界以[客户端架构](./docs/architecture.md#130-单体仓库与-kotlin-多平台客户端)为准。

Android 和桌面均可进入“设置”，从 DashScope、OpenAI、DeepSeek、MiMo 预设中快速选择模型，也可填写自定义模型 ID 和兼容 API 地址；地址、供应商、模型及 API Key 由应用私有的 Preferences DataStore 保存。远端模型列表只在展开模型选择器时按需获取，打开设置页不会请求；内置预设与当前模型会立即显示，目录失败仍可手动输入。点击“测试模型配置”会独立检查本地配置、模型目录、流式文本和无副作用工具调用，可能产生少量供应商费用。产品中每条“对话记录”对应一个 Agent Session；Session 语义事件先追加到运行时私有 JSONL，列表和完整 Koog `Message` 从独立 `agent-runtime.db` 的可重建投影读取，不与应用的 `hanppie.db` 共库；支持新建、打开、重命名和二次确认后永久删除，重启后恢复。对话智能体会先查询由机内运行时源码核对的 Lab API，再生成 Python 3.6 脚本；它可以按名称列出、读取、保存、重命名和经确认后删除“我的脚本”，保存不会自动上传或运行。回答、流式输出和审批源码支持 fenced code 语法高亮。需要控制机器人时先在“设备”页连接指定目标，在聊天中检查生成的完整脚本并“确认执行”。“取消”仅取消当前 agent run；停止机内脚本使用“脚本”页的“停止脚本”。

手机“对话”页点发送旁的麦克风图标，阅读系统识别说明并允许麦克风权限后说话；可点“结束录音”，识别文字回填输入框，检查后再点“发送”。该入口只使用手机的系统语音识别，不是机器人麦克风。系统识别服务可能联网；缺少兼容服务时会提示，应用不会自动安装或切换系统引擎。KMP 客户端不再提供回复朗读或自动朗读。

桌面也可通过 `HANPPIE_LLM_PROVIDER`、`HANPPIE_LLM_ENDPOINT`、`HANPPIE_LLM_MODEL`、`HANPPIE_LLM_API_KEY` 环境变量注入配置。键盘操作说明集中在“设置 → 快捷键”，不会散落在对话页。不要把真实密钥写入源码或提交。显式设置 `HANPPIE_LLM_API_KEY` 后可运行不连接机器人的真实接口测试：

```bash
HANPPIE_LLM_LIVE_TEST=1 ./gradlew :shared:jvmTest --tests '*ChatAgentTest.liveCompatibleConversationWithoutRobot' --rerun-tasks
```

默认测试只访问本机模拟服务。后续实机测试必须显式设置目标和上传授权，会覆盖机器人当前 Lab 上传文件：

```bash
HANPPIE_TEST_ROBOT_IP=192.168.1.100 \
HANPPIE_TEST_APPID=0123abcd \
HANPPIE_TEST_ALLOW_LAB=1 \
./gradlew :packages:robot-core:desktopTest --tests '*HardwareIntegrationTest' --rerun-tasks
```

桌面界面实机测试使用同样的 `HANPPIE_TEST_ROBOT_IP`、`HANPPIE_TEST_APPID`，运行 `./gradlew :shared:jvmTest --tests '*DesktopLiveTest' --rerun-tasks`。默认仅接收音视频；`HANPPIE_TEST_ALLOW_LAB=1` 加上模型环境配置验证无运动脚本的真实模型执行与回传（覆盖机内 Lab 文件）；`HANPPIE_TEST_ALLOW_REMOTE=1` 额外测试短时云台、红外和 Esc 停止。测试前关闭其他机器人控制会话。

### Python 工具链

项目以 Python 3.10 为开发与测试基线，并使用 [uv](https://docs.astral.sh/uv/) 管理环境。开发者还可以安装 [Task](https://taskfile.dev/) 作为统一任务入口。

```bash
git clone https://github.com/elonzh/hanppie.git
cd hanppie
uv sync
```

`uv sync` 会安装 Hanppie 核心库（包含直控与 Lab 后端）和仓库内的 `robomaster` SDK fork。后者保持官方主要导入接口：

```python
from robomaster import robot

s1 = robot.Robot()
```

同时也支持直接使用 Hanppie 的机器人控制：

```python
from hanppie import Robot
```

当前只测试 Python 3.10；准确的依赖约束以 [`pyproject.toml`](./pyproject.toml) 和 [`uv.lock`](./uv.lock) 为准。

## Python 直控

`Robot` 直接使用 RoboMaster App 数据会话；这条路径当前只在 S1 上完成实机验证。它不上传 Lab 程序，也不调用仓库内的官方 SDK fork。机械命令必须显式进入控制模式、arm，并设置短租约：

```python
import time

from hanppie import Robot

robot = Robot(robot_ip="192.168.1.100", appid="0123abcd")
try:
    robot.initialize()
    robot.enter_control_mode()
    robot.set_led(red=0, green=255, blue=0)

    # 只有在地面和射界安全时才允许机械动作。
    robot.arm()
    robot.chassis.drive_speed(x=0.1, lease_seconds=0.25)
    time.sleep(0.4)
finally:
    robot.disarm()
    robot.close()
```

当前 API、能力与未完成项只在[技术架构](./docs/architecture.md#110-当前能力矩阵)维护。手柄、Web 和 ROS 2 输入层尚未实现，不能绕过未来的控制仲裁层直接驱动执行器。


## 开发工具链

```bash
task sync       # uv 同步开发环境
task format     # Ruff 格式化和安全修复
task lint       # Ruff 格式与 lint 检查
task test       # pytest + coverage
task check      # lint + test
task build      # 构建 sdist 和 wheel
task hooks      # 安装 prek hooks
task prek       # 对全部文件执行 hooks
```

项目约束见 [`AGENTS.md`](./AGENTS.md)。Hanppie 实现与能力结论只在[技术架构](./docs/architecture.md)维护，RoboMaster 原生架构与协议结论只在[专项文档](./docs/architecture-robomaster.md)维护；实机运行则生成新的证据报告。

## 许可证

Hanppie 自有代码使用 [Apache License 2.0](./LICENSE)。`src/robomaster` 中的 DJI 派生代码使用其目录内的 [Apache License 2.0](./src/robomaster/LICENSE.txt)，来源见 [`UPSTREAM.md`](./src/robomaster/UPSTREAM.md)。
