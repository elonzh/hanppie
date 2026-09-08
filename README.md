# Hanppie（憨皮）

[简体中文](./README.md) | [English](./README_EN.md)

[![CI](https://github.com/elonzh/hanppie/actions/workflows/ci.yml/badge.svg)](https://github.com/elonzh/hanppie/actions/workflows/ci.yml)
[![Python](https://img.shields.io/badge/Python-3.10-blue)](https://www.python.org/)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](./LICENSE)

Hanppie 是一个面向 DJI RoboMaster S1 的开源保存与电脑编程工具箱。项目目标是在不依赖手机 App 的前提下，恢复可审计、可回滚的连接、编程、遥测和远程控制能力。

项目尚处于 Alpha 阶段，不隶属于 DJI，也未获得 DJI 背书。

## 文档分工

为避免同一结论被复制到多处，仓库按以下边界维护文档：

| 文档 | 唯一职责 |
| --- | --- |
| [技术架构](./docs/architecture.md) | 当前软硬件架构、协议、能力状态、项目机制和安全边界的唯一权威来源 |
| README | 安装、命令用法和文档导航 |
| [初次恢复记录](./docs/s1-live-debug-2026-08-29.md)、[App/Lab 回归记录](./docs/s1-live-regression-2026-08-30.md)、[AppEnvelope 直控记录](./docs/s1-direct-control-2026-08-31.md) | 特定日期的命令、输出、故障和测量证据，不维护当前结论 |
| [早期调研报告](./docs/robomaster-s1-revival-report.md) | S.BUS、SocketCAN、vcan、ROS 2 和备选路线的历史调研 |
| [`src/robomaster/UPSTREAM.md`](./src/robomaster/UPSTREAM.md) | 内置 DJI SDK fork 的来源与改动边界 |

使用实机前请先阅读技术架构中的[安全与恢复模型](./docs/architecture.md#713-安全与恢复模型)；当前验证状态只查看其中的[能力矩阵](./docs/architecture.md#711-当前能力矩阵)。

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

在手机上连接 S1 所在的 Wi-Fi，打开“憨皮”→“搜索设备”，或选择“手动连接”。“脚本”页使用系统文件选择器打开/保存 `.py`；在“设置”→“语音服务”中选择识别服务或配置系统朗读。

Android 模拟器使用默认 NAT/DHCP 即可尝试直连机器人，不要把虚拟 Wi-Fi 手动改为家庭局域网的静态 IP；发现不到时使用“手动连接”填写机器人的真实局域网 IPv4 和 AppID。macOS 的“系统设置 → 隐私与安全性 → 本地网络”中需允许启动模拟器的应用访问局域网。若电脑能连接而模拟器报 `No route to host`，先完全退出模拟器，再使用现有 SDK 冷启动（保留应用及数据，不使用 Wipe Data）：

```bash
"$ANDROID_HOME/emulator/emulator" -list-avds
"$ANDROID_HOME/emulator/emulator" -avd <上一步的设备名称> -no-snapshot-load
```

`ANDROID_HOME` 指向 IDEA 使用的同一套 SDK。不要为此额外安装 SDK。当前验证边界见[客户端架构](./docs/architecture.md#730-单体仓库与-kotlin-多平台客户端)。

### 桌面程序

Android 和桌面连接后进入全屏驾驶舱，开启视频或监听机器人；点“开始遥控”后使用左侧底盘、右侧云台摇杆。键盘使用 WASD 移动、Q 降档、E 升档、按住 Shift 缓行、方向键控制云台、R 切换红外/水弹、空格发射当前弹药、Esc 停止。屏幕加减按钮也可换档；Shift 松开恢复当前档位。云台向外转过一定角度时底盘开始软件联动，松手停止；机械效果仍待实机验证，见[架构边界](./docs/architecture.md)。也可以点击弹药名称切换，再点击“开火”；默认红外，切换本身不会发射。水弹使用直控单发命令，不运行 Lab 脚本。点“控制台”返回对话、脚本和设置；离开驾驶舱会停止遥控，桌面窗口失焦也会停止。音视频为机器人下行播放，不是双向通话。

桌面音视频需要安装支持 H.264、Opus 的 FFmpeg，将 `ffmpeg` 加入应用进程的 PATH，或以 `HANPPIE_FFMPEG` 环境变量指定可执行文件绝对路径（macOS Homebrew 通常为 `/opt/homebrew/bin/ffmpeg`）。分发包不包含 FFmpeg；Android 使用系统解码器，不需要 FFmpeg。

安装 JDK 21，在仓库根目录运行（Windows 将 `./gradlew` 替换为 `gradlew.bat`）：

```bash
./gradlew :desktopApp:run
./gradlew :packages:robot-core:desktopTest :shared:jvmTest
./gradlew :desktopApp:createDistributable
```

分发程序位于 `desktopApp/build/compose/binaries/main/app/`，包含运行时，不需要安装 Python。
打开后点击“搜索设备”，点击发现的目标连接；也可选择“手动连接”，填写机器人 IPv4 和 AppID 后连接。
在“脚本”页打开或编辑 Python 3.6 脚本，先上传，再勾选执行授权并启动。
编辑后需要重新上传；“停止脚本”用于发送机内停止命令。
在“对话”页点击回复旁的“朗读”播报文字。Linux 需要已安装并配置 Speech Dispatcher（`spd-say`）。
能力与验证边界以[客户端架构](./docs/architecture.md#730-单体仓库与-kotlin-多平台客户端)为准。

Android 和桌面均可进入“设置”，填写兼容 API 地址、模型及 API Key，点击“保存设置”；地址、模型、密钥和自动朗读选项将在下次启动恢复。清空 API Key 再保存可移除已保存的密钥。保存失败会显示提示，不会改为明文存储。聊天记录仅保留本次运行；“新对话”清空上下文。需要控制机器人时先在“设备”页连接指定目标，在聊天中检查生成的完整脚本并“确认执行”。“取消”仅取消对话；停止机内脚本使用“脚本”页的“停止脚本”。

手机“对话”页点发送旁的麦克风图标，阅读系统识别说明并允许麦克风权限后说话；可点“结束录音”，识别文字回填输入框，检查后再点“发送”。点回复旁的“朗读”播报该条文字，或在“设置”中开启“自动朗读”播报后续完整回复；“停止朗读”随时打断。当前使用手机的音频输入/输出，不是机器人麦克风或扬声器。系统识别服务可能联网；缺少兼容服务时会提示，应用不会自动安装或切换系统引擎。

桌面也可通过 `HANPPIE_LLM_ENDPOINT`、`HANPPIE_LLM_MODEL`、`HANPPIE_LLM_API_KEY` 环境变量注入配置。不要把真实密钥写入源码或提交。显式设置 `HANPPIE_LLM_API_KEY` 后可运行不连接机器人的真实接口测试：

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
uv run hanppie --help
```

`uv sync` 会安装 Hanppie、自有 App/Lab 后端和仓库内的 `robomaster` SDK fork。后者保持官方主要导入接口：

```python
from robomaster import robot

s1 = robot.Robot()
```

当前只测试 Python 3.10；准确的依赖约束以 [`pyproject.toml`](./pyproject.toml) 和 [`uv.lock`](./uv.lock) 为准。

## 一键实机诊断

`diag` 是项目唯一的实机验证与调试入口。它使用 Typer 和 Rich 提供交互式与非交互式执行，并为每次运行生成 Markdown 报告和 JSONL 事件日志：

```bash
# 查看全部项目和风险等级
uv run hanppie diag --list

# 交互选择，并逐项确认机械运动、红外和水弹测试
uv run hanppie diag --interactive

# 标准非机械诊断：发现、App、原生直控遥测、媒体、Lab、灯光、ADB 和机内信息
uv run hanppie diag --no-interactive

# 完整非交互回归；三类危险能力必须分别显式解锁
uv run hanppie diag \
  --no-interactive \
  --all \
  --robot-ip "$S1_IP" \
  --appid "$S1_APPID" \
  --allow-motion \
  --allow-infrared \
  --allow-gel
```

完整检查顺序、证据层级和安全清理机制只在[技术架构的诊断章节](./docs/architecture.md#710-cli完整诊断与质量边界)维护。机械、红外和水弹必须通过三个独立选项显式授权。

默认输出位于 `.hanppie/diagnosis/<timestamp>/report.md` 和 `events.jsonl`。该目录不会进入 Git，报告只表示本次运行证据；技术结论仍只更新到架构文档。涉及 `adb` 或 `system` 时会临时开放 root ADB，并在结束时重启机器人、确认 TCP 5555 已关闭，不提供跳过安全清理的兼容选项。

## Python 直控

`DirectRobot` 直接使用 S1 的 App 数据会话，不上传 Lab 程序，也不调用仓库内的官方 SDK fork。机械命令必须显式进入控制模式、arm，并设置短租约：

```python
import time

from hanppie.lab import DirectRobot

robot = DirectRobot(robot_ip="192.168.1.100", appid="0123abcd")
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

当前 API、能力与未完成项只在[技术架构](./docs/architecture.md#711-当前能力矩阵)维护。手柄、Web 和 ROS 2 输入层尚未实现，不能绕过未来的控制仲裁层直接驱动执行器。

## Codex MCP 对话控制

Hanppie 提供本机 STDIO MCP 服务。默认安装命令会安全地加入 user 范围的 Codex 共享配置；同一台电脑上的 ChatGPT 桌面端、Codex CLI 和 IDE 扩展共用该配置。Windows、macOS、Linux 和 WSL 都使用当前 Python 解释器的绝对路径启动服务，不要求 `codex` 命令位于 PATH。

```bash
# 自动发现并连接局域网内唯一可用的 S1
uv run hanppie mcp install

# 也可以只写当前项目的 .codex/config.toml
uv run hanppie mcp install --scope project

# 也可以固定目标，避免存在多台 S1 时需要重新配置
uv run hanppie mcp install \
  --replace \
  --robot-ip "$S1_IP" \
  --appid "$S1_APPID"

# 需要调试客户端配置时，也可以直接运行服务
uv run hanppie mcp serve
```

安装后重启对应的 Codex 客户端，并用 `/mcp` 确认 `hanppie` 已连接。`install` 会保留其他 Codex 设置和 MCP 服务；相同配置重复执行不会改文件，已有不同的 Hanppie 配置必须显式传入 `--replace`。user 范围依次使用 `--codex-home`、`CODEX_HOME` 或 `~/.codex/config.toml`，project 范围写入项目根的 `.codex/config.toml`。本地 MCP 配置不适用于 ChatGPT Web，详见 [Codex MCP 文档](https://learn.chatgpt.com/docs/extend/mcp)。

MCP 提供连接、状态、Python 上下文、Python 执行和断开工具。首次连接可以自动选择局域网内唯一可用的 S1，之后同一 MCP 服务在连续对话和多次工具调用之间复用当前 App 连接，不再为每条指令反复初始化。`execute_python` 默认用 `robot_access=auto` 按需连接；`reuse` 只提供已有连接，`none` 明确执行 Host-only Python。每次 Python 调用仍使用新命名空间，提供 `robot`、`time`、`sleep`、`output_dir`、`save_frame` 和 `checkpoint`，并返回 `result`、输出、阶段事件、错误和制品路径；任意源码只在电脑 Python 3.10 worker 中执行。

安装和启动没有动作、红外或水弹权限选项。底盘、云台和红外直接使用 `DirectRobot` 的正常 `robot.arm()` 与租约 API；水弹调用 `result = robot.fire_gel()` 或 `robot.fire("gel")`，MCP 会切换到已验证的 Lab/Bridge 路径并等待执行结果。Lab 切换失败会重试并尝试恢复原 Direct 连接；共享灯光和 stop 不会触发无意义的后端切换。每次调用正常或异常结束仍会归零并 `disarm()`，但健康连接不会关闭；超时会结束整个 worker，并在下一次调用时重建连接。速度乘以持续时间不是精确角度或距离证明，多阶段动作可在每个完成点调用 `checkpoint("阶段名", ...)` 保留部分进度。执行失败会以 MCP 工具错误返回，便于 Codex 直接识别失败而不是误判为完成。

这是面向可信本地用户的任意 Python 代码执行入口，不是安全沙箱。MCP 启动、握手和列出工具不会写文件；首次实际调用 Hanppie 工具后才会创建 `.hanppie/mcp/sessions/<session-id>/`。`server.log` 保存服务与 worker 运行日志，`calls.jsonl` 为每次调用保存同一 `call_id` 的 `started`/`completed` 事件，`calls/<run-id>/` 保存该次 Python 调用生成的文件、图片和检查点。`get_python_context` 本身也算一次调用，会激活记录并返回当前版本、真实 facade 签名、能力边界和记录路径；`--artifact-dir` 可以整体修改这个根目录。调用记录包含提交的 Python 源码和返回内容，应按本机运行记录管理。完整执行边界、清理和并发限制只在[技术架构](./docs/architecture.md#734-codex-mcp-与持久-host-python-worker)维护。

## 小憨批语音智能体

先让 Hanppie 直接完成一次 ChatGPT Codex 设备授权，并允许终端访问系统麦克风，即可持续监听“小憨批”，在一次唤醒后的活动窗口内连续对话、组合机器人动作或理解当前相机画面。此模式不要求安装或启动 Codex：

```bash
uv run hanppie agent login

# 未设置 OPENAI_API_KEY 时，auto 默认使用 Hanppie 保存的 Codex OAuth
uv run hanppie agent run

# 直接执行文本，无需唤醒或音频设备；重复 -p 复用上下文和机器人连接
uv run hanppie agent run --auth codex \
  -p "观察一下附近有些什么东西？" -p "刚才画面中最显眼的是什么？"

# 多台设备时固定目标
uv run hanppie agent run \
  --auth codex \
  --robot-ip "$S1_IP" \
  --appid "$S1_APPID"
```

测试低延迟规划模型，同时指定独立视觉模型：

```bash
uv run hanppie agent run --auth codex \
  --codex-model gpt-5.3-codex-spark --codex-vision-model gpt-5.6-sol \
  --reasoning-effort low --model-timeout 30 -p "把装甲灯设为蓝色常亮"
```

模型访问权限由账户决定；参数或模型不支持时直接报错，不自动切换。计时口径与当前性能边界见[技术架构](./docs/architecture.md#735-唤醒词连续对话与-langgraph-智能体)。

Codex 模式由 Hanppie 自己执行 device-code OAuth、刷新凭据，并以 Bearer token 直接流式请求 ChatGPT 的 Codex Responses 后端；没有 Codex CLI、`codex exec` 或 App Server 子进程。凭据默认原子写入 `~/.hanppie/auth.json`，在 POSIX 上权限为 `0600`，`HANPPIE_HOME` 可修改目录；`hanppie agent logout` 只删除这份本地凭据。请求使用 `store=false`，连续上下文由 Hanppie 在内存中重放，默认模型为 `gpt-5.6-sol`，也可用 `--codex-model` 覆盖。该消费者后端不是 OpenAI Platform 公共 API，兼容性取决于当前 Codex OAuth 协议。

本地 `faster-whisper` 负责转写（首次使用默认 `small` 模型时会下载模型），系统语音负责播报。若已有 OpenAI API key，`auto` 会保留原来的 OpenAI 转写、Responses 和 TTS 链路；也可以用 `--auth api-key` 或 `--auth codex` 明确选择。

例如说“小憨批，观察一下附近有些什么东西？”，智能体会读取 S1 当前前向相机帧并用视觉模型回答。唤醒后的默认 45 秒内可以直接追问；说“退下”可让会话休眠，活动会话中的“停止”“停下”“别动”会走不等待大模型的已有连接停止路径。`--wake-phrase` 可重复配置别名，`--active-timeout` 调整连续对话窗口，`--audio-device` 选择电脑音频输入设备，`--local-transcription-model` 选择本地 Whisper 模型，`--no-tts` 关闭语音播放。

`--prompt` 无需唤醒，禁用音频输入和播放；执行失败返回非零退出码，并停止后续 prompt。对话和调用记录在实际唤醒或执行 prompt 后写入 `.hanppie/agent/`。完整数据边界、LangGraph 状态、代码策略和验证边界见[技术架构](./docs/architecture.md#735-唤醒词连续对话与-langgraph-智能体)。

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

项目约束见 [`AGENTS.md`](./AGENTS.md)。架构、协议、能力边界或项目机制发生变化时，只在技术架构中维护结论；实机运行则生成新的证据报告。

## 许可证

Hanppie 自有代码使用 [MIT License](./LICENSE)。`src/robomaster` 中的 DJI 派生代码使用其目录内的 [Apache License 2.0](./src/robomaster/LICENSE.txt)，来源见 [`UPSTREAM.md`](./src/robomaster/UPSTREAM.md)。
