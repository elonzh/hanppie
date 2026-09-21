# Hanppie

[简体中文](./README.md) | [English](./README_EN.md)

[![CI](https://github.com/elonzh/hanppie/actions/workflows/ci.yml/badge.svg)](https://github.com/elonzh/hanppie/actions/workflows/ci.yml)
[![Python](https://img.shields.io/badge/Python-3.10-blue)](https://www.python.org/)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](./LICENSE)

Hanppie is an open preservation and computer-programming toolkit for DJI RoboMaster robots. Its goal is to restore auditable and reversible connectivity, programming, telemetry, and remote control without depending on the mobile app. All current physical-device evidence comes from S1; this is not a closed model allowlist, and models such as EP are not claimed as supported until each capability is verified.

The project is Alpha software. It is independent from and not endorsed by DJI.

## Documentation ownership

Each document has one responsibility so that a conclusion is never maintained in multiple places:

| Document | Sole responsibility |
| --- | --- |
| [Hanppie technical architecture](./docs/architecture.md) (Chinese) | The only source of truth for current project structure, implementation, capability status, and safety boundaries |
| [RoboMaster S1 architecture, protocols, and research](./docs/architecture-robomaster.md) (Chinese) | The only source of truth for native S1 hardware, firmware, protocols, and external ecosystem |
| [Agent runtime evolution plan](./docs/agent-runtime-plan.md) (Chinese) | Implemented foundation plus later event, recovery, Koog, and provider evolution boundaries |
| [Conversation product requirements](./docs/conversation-product-requirements.md) (Chinese) | Current model setup, conversation management, shortcuts, and staged acceptance requirements |
| [Conversation agent product design](./docs/conversation-agent-product-design.md) (Chinese) | Current P0 tools plus later agent responsibilities, risk policy, and interaction loop |
| README | Installation, command usage, and navigation |
| [Initial recovery log](./docs/s1-live-debug-2026-08-29.md), [App/Lab regression log](./docs/s1-live-regression-2026-08-30.md), [AppEnvelope direct-control log](./docs/s1-direct-control-2026-08-31.md) | Commands, output, failures, and measurements from a dated run; never the current conclusion |
| [Early research report](./docs/robomaster-s1-revival-report.md) | Historical research into S.BUS, SocketCAN, vcan, ROS 2, and alternative approaches |
| [`src/robomaster/UPSTREAM.md`](./src/robomaster/UPSTREAM.md) | Provenance and maintenance boundary of the bundled DJI SDK fork |

Before using physical hardware, read the architecture's [safety and recovery model](./docs/architecture.md#113-安全与恢复模型). Its [capability matrix](./docs/architecture.md#111-当前能力矩阵) is the sole current status table.

## Installation

### Desktop application

Both desktop and Android support **Settings → Language → System default / 简体中文 / English**. The choice is saved and takes effect immediately without reconnecting the robot.

Install JDK 21 and run from the repository root (use `gradlew.bat` on Windows):

```bash
./gradlew :desktopApp:run
./gradlew :packages:robot-core:desktopTest :shared:jvmTest
./gradlew :desktopApp:createDistributable
```

The packaged application is under `desktopApp/build/compose/binaries/main/app/` and includes its runtime.
Desktop video/audio requires FFmpeg with H.264 and Opus support on the application process PATH, or an absolute executable path in `HANPPIE_FFMPEG`. FFmpeg is not bundled; Android uses system codecs. See the cockpit controls below for keyboard and touch operation.
Connecting opens a full-screen cockpit with a video background and touch controls. **控制台** returns to conversation, scripts and settings. The water-shot button sends one direct command; it does not upload or start a Lab script. Physical water firing remains unverified.
Click a discovered robot to connect, or select manual connection and enter an explicit IPv4/AppID. The script tab manages local scripts and editable copies of five multi-step presets, with import/export, rename, and delete operations. Choosing **Run script** is the execution intent: the app validates the source, overwrites the single `python_raw.dsp`, and sends the start command without a second confirmation checkbox. Lab scripts use SDK-provided objects such as `time` directly and must not contain ordinary imports. Starting a script opens a focused execution view where lifecycle state and output sent through `log_ctrl.print_msg(...)` take priority; returning to the editor does not stop the script, and a global status strip keeps the active run and Stop action visible on other pages. The run view shows a copyable run ID plus host-side upload, start, and report-reception diagnostics. If no onboard `STARTED` report arrives within 10 seconds, the run becomes unknown and retains the Stop action instead of waiting forever or retrying automatically. Normal return or failure closes the onboard run automatically, while a lost connection is also reported as unknown. Gimbal and chassis demonstrations are visibly marked, run for a finite number of rounds, and require clear space.
For Android, configure the SDK managed by IDEA in the ignored `local.properties`, install API 37 and Build Tools 36.1.0, and run `task android:check`. The APK is at `androidApp/build/outputs/apk/debug/androidApp-debug.apk`.
See the [client architecture](./docs/architecture.md#130-单体仓库与-kotlin-多平台客户端) for current implementation and verification boundaries.

On Android or desktop, open **设置** to select a DashScope, OpenAI, DeepSeek, or MiMo preset, or enter a custom model ID and compatible API URL. The remote model list loads on demand only when the model selector opens; opening Settings makes no catalog request, built-in presets and the current ID appear immediately, and manual input remains available after a catalog failure. **测试模型配置** independently checks local configuration, the model catalog, streaming text, and a side-effect-free tool call; the two model requests may incur a small provider charge. Each product-level conversation maps to an Agent Session. Semantic Session events are appended to runtime-owned JSONL, while list and complete Koog `Message` queries use the rebuildable projection in the independent `agent-runtime.db`, not the application's `hanppie.db`. Sessions can be created, opened, renamed, and permanently deleted after confirmation, and survive restarts. Connect a specific robot in **设备** before requesting scripts, and review the generated source before confirming execution. Cancelling the current agent run does not stop an onboard script; use **脚本 → 停止脚本** for that.

On Android, the microphone icon beside Send records through the phone's system speech-recognition service after consent and microphone permission. Review the recognized draft before sending it. This is phone speech input, not the robot microphone. The recognition service may process audio online; the app does not install or switch system services automatically. Reply read-aloud and automatic read-aloud have been removed from the KMP client.

Desktop configuration also accepts `HANPPIE_LLM_PROVIDER`, `HANPPIE_LLM_ENDPOINT`, `HANPPIE_LLM_MODEL` and `HANPPIE_LLM_API_KEY` environment variables. Keyboard help is kept in **Settings → Keyboard shortcuts** instead of being repeated in the chat view. Never commit real credentials. With `HANPPIE_LLM_LIVE_TEST=1`, the opt-in `ChatAgentTest.liveCompatibleConversationWithoutRobot` test uses the configured cloud API with a simulated status tool and never connects to a robot.
The [Chinese usage guide](./README.md#桌面程序) also documents opt-in, no-motion hardware integration tests.

On Android and desktop, entering the full-screen cockpit establishes the direct-control channel; with no input it sends only zero values. Use the left chassis stick and right gimbal stick. Touch controls provide direct gear `1–5` at the lower left, plus ammunition selection and direct fire at the lower right. On a keyboard, use WASD to move, Q/E to rotate in place, `1–5` to select a gear, arrow keys to aim, G to switch ammunition, Space to fire, and Esc to return to the console. Outward aiming beyond the follow threshold engages software chassis turning; release stops it. Physical follow behavior is not yet validated; see [architecture boundaries](./docs/architecture.md). Infrared is selected by default; switching never fires. Gel beads use a direct single-shot command, not a Lab script. Leaving the cockpit or losing desktop window focus zeros input and exits the current direct-control channel. Returning to the foreground cockpit restores the channel with cleared keys and sticks. Robot audio/video supports downstream playback plus push-to-talk clips that record while held and send on release; it is not full-duplex calling.

### Python tools

Python 3.10 is the development and test baseline. [uv](https://docs.astral.sh/uv/) manages the environment, while [Task](https://taskfile.dev/) is the optional unified task runner.

```bash
git clone https://github.com/elonzh/hanppie.git
cd hanppie
uv sync
```

`uv sync` installs Hanppie's core library (including direct and Lab backends) and the repository's `robomaster` SDK fork. The fork preserves the primary DJI imports:

```python
from robomaster import robot

s1 = robot.Robot()
```

Direct connection or Lab sessions from Hanppie can also be imported directly:

```python
from hanppie import Robot
```

Only Python 3.10 is tested. [`pyproject.toml`](./pyproject.toml) and [`uv.lock`](./uv.lock) are authoritative for dependency constraints.

## Direct Python control

`Robot` uses the RoboMaster App data session directly; this path is currently verified on S1 only. It neither uploads a Lab program nor calls the bundled official SDK fork. Mechanical commands require explicit control mode, arming, and a short lease:

```python
import time

from hanppie import Robot

robot = Robot(robot_ip="192.168.1.100", appid="0123abcd")
try:
    robot.initialize()
    robot.enter_control_mode()
    robot.set_led(red=0, green=255, blue=0)

    # Permit mechanical movement only in a clear, controlled area.
    robot.arm()
    robot.chassis.drive_speed(x=0.1, lease_seconds=0.25)
    time.sleep(0.4)
finally:
    robot.disarm()
    robot.close()
```

The current API, capabilities, and open gaps are maintained only in the [technical architecture](./docs/architecture.md#110-当前能力矩阵). Gamepad, Web, and ROS 2 input layers are not implemented yet and must eventually feed a control arbiter rather than drive actuators directly.

## Development toolchain

```bash
task sync       # synchronize the uv development environment
task format     # format and apply safe Ruff fixes
task lint       # run Ruff formatting and lint checks
task test       # run pytest with coverage
task check      # run lint and tests
task build      # build the sdist and wheel
task hooks      # install prek hooks
task prek       # run hooks against all files
```

Repository constraints live in [`AGENTS.md`](./AGENTS.md). When architecture, protocol, capability boundaries, or project mechanisms change, maintain the conclusion only in the technical architecture; physical runs produce new evidence reports.

## License

Hanppie's own code uses the [Apache License 2.0](./LICENSE). DJI-derived code under `src/robomaster` uses the directory's [Apache License 2.0](./src/robomaster/LICENSE.txt); see [`UPSTREAM.md`](./src/robomaster/UPSTREAM.md) for provenance.
