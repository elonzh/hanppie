# Hanppie

[简体中文](./README.md) | [English](./README_EN.md)

[![CI](https://github.com/elonzh/hanppie/actions/workflows/ci.yml/badge.svg)](https://github.com/elonzh/hanppie/actions/workflows/ci.yml)
[![Python](https://img.shields.io/badge/Python-3.10-blue)](https://www.python.org/)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](./LICENSE)

Hanppie is an open preservation and computer-programming toolkit for the DJI RoboMaster S1. Its goal is to restore auditable and reversible connectivity, programming, telemetry, and remote control without depending on the mobile app.

The project is Alpha software. It is independent from and not endorsed by DJI.

## Documentation ownership

Each document has one responsibility so that a conclusion is never maintained in multiple places:

| Document | Sole responsibility |
| --- | --- |
| [Technical architecture](./docs/architecture.md) (Chinese) | The only source of truth for current hardware/software architecture, protocols, capability status, Hanppie mechanisms, and safety boundaries |
| README | Installation, command usage, and navigation |
| [Initial recovery log](./docs/s1-live-debug-2026-08-29.md), [App/Lab regression log](./docs/s1-live-regression-2026-08-30.md), [AppEnvelope direct-control log](./docs/s1-direct-control-2026-08-31.md) | Commands, output, failures, and measurements from a dated run; never the current conclusion |
| [Early research report](./docs/robomaster-s1-revival-report.md) | Historical research into S.BUS, SocketCAN, vcan, ROS 2, and alternative approaches |
| [`src/robomaster/UPSTREAM.md`](./src/robomaster/UPSTREAM.md) | Provenance and maintenance boundary of the bundled DJI SDK fork |

Before using physical hardware, read the architecture's [safety and recovery model](./docs/architecture.md#713-安全与恢复模型). Its [capability matrix](./docs/architecture.md#711-当前能力矩阵) is the sole current status table.

## Installation

### Desktop application

Both desktop and Android support **Settings → Language → System default / 简体中文 / English**. The choice is saved and takes effect immediately without reconnecting the robot.

Install JDK 21 and run from the repository root (use `gradlew.bat` on Windows):

```bash
./gradlew :apps:desktop:run
./gradlew :packages:robot-core:desktopTest :apps:desktop:desktopTest
./gradlew :apps:desktop:createDistributable
```

The packaged application is under `apps/desktop/build/compose/binaries/main/app/` and includes its runtime.
Desktop video/audio requires FFmpeg with H.264 and Opus support on the application process PATH, or an absolute executable path in `HANPPIE_FFMPEG`. FFmpeg is not bundled; Android uses system codecs. See the cockpit controls below for keyboard and touch operation.
Connecting opens a full-screen cockpit with a video background and touch controls. **控制台** returns to conversation, scripts and settings. The water-shot button sends one direct command; it does not upload or start a Lab script. Physical water firing remains unverified.
Click a discovered robot to connect, or select manual connection and enter an explicit IPv4/AppID. Open a Python 3.6 script in the script tab,
upload it, and explicitly enable execution before starting. Editing requires another upload.
Use **朗读** beside an assistant reply to read it aloud. Linux requires a configured Speech Dispatcher (`spd-say`).
For Android, configure the SDK managed by IDEA in the ignored `local.properties`, install API 37 and Build Tools 36.1.0, and run `task android:check`. The APK is at `apps/android/build/outputs/apk/debug/android-debug.apk`.
See the [client architecture](./docs/architecture.md#730-单体仓库与-kotlin-多平台客户端) for current implementation and verification boundaries.

On Android or desktop, open **设置**, enter an OpenAI-compatible API URL, model and API key, then send messages. Settings and conversation history last only for the current process. Connect a specific robot in **设备** before requesting scripts, and review the generated source before confirming execution. Cancelling a conversation does not stop an onboard script; use **脚本 → 停止脚本** for that.

On Android, the microphone icon beside Send records through the phone's system speech service after consent and microphone permission. Review the recognized draft before sending it. **朗读** reads an assistant message, **设置 → 自动朗读** enables reading subsequent completed replies, and **停止朗读** interrupts playback. This uses phone audio, not the robot microphone or speaker. The system recognition service may process audio online; the app does not install or switch system services automatically.

Desktop configuration also accepts `HANPPIE_LLM_ENDPOINT`, `HANPPIE_LLM_MODEL` and `HANPPIE_LLM_API_KEY` environment variables. Never commit real credentials. With `HANPPIE_LLM_LIVE_TEST=1`, the opt-in `ChatAgentTest.liveCompatibleConversationWithoutRobot` test uses the configured cloud API with a simulated status tool and never connects to a robot.
The [Chinese usage guide](./README.md#桌面程序) also documents opt-in, no-motion hardware integration tests.

### Python tools

On Android and desktop, connecting a robot opens the cockpit. Enable remote control before using the chassis/gimbal sticks or WASD and arrow keys. Q shifts down, E shifts up; holding either Shift key slows translation until released. On-screen minus/plus buttons also change gears. Outward aiming beyond the follow threshold engages software chassis turning; release stops it. Physical follow behavior is not yet validated; see [architecture boundaries](./docs/architecture.md). R or the ammunition selector switches between infrared and gel beads; Space or **开火** fires the selected type, and Esc stops control. Infrared is selected by default; switching never fires. Gel beads use a direct single-shot command, not a Lab script. Enable video and robot microphone playback explicitly. Return to **控制台** for chat, scripts and settings. Leaving the cockpit or losing desktop window focus stops control. Audio/video are robot-to-app only, not two-way calling.

Python 3.10 is the development and test baseline. [uv](https://docs.astral.sh/uv/) manages the environment, while [Task](https://taskfile.dev/) is the optional unified task runner.

```bash
git clone https://github.com/elonzh/hanppie.git
cd hanppie
uv sync
uv run hanppie --help
```

`uv sync` installs Hanppie, its App/Lab backend, and the repository's `robomaster` SDK fork. The fork preserves the primary DJI imports:

```python
from robomaster import robot

s1 = robot.Robot()
```

Only Python 3.10 is tested. [`pyproject.toml`](./pyproject.toml) and [`uv.lock`](./uv.lock) are authoritative for dependency constraints.

## One-command physical-device diagnosis

`diag` is the project's only physical-device validation and debugging entry point. It supports interactive and non-interactive execution through Typer and Rich, and every run creates a Markdown report and JSONL event log:

```bash
# Show every check and its risk class
uv run hanppie diag --list

# Select checks interactively and confirm motion, infrared, and gel firing separately
uv run hanppie diag --interactive

# Standard non-mechanical diagnosis: discovery, App, native direct telemetry, media, Lab, lights, ADB, system
uv run hanppie diag --no-interactive

# Complete non-interactive regression; each hazardous class requires an explicit gate
uv run hanppie diag \
  --no-interactive \
  --all \
  --robot-ip "$S1_IP" \
  --appid "$S1_APPID" \
  --allow-motion \
  --allow-infrared \
  --allow-gel
```

The complete check order, evidence levels, and cleanup mechanism are maintained only in the [architecture diagnosis section](./docs/architecture.md#710-cli完整诊断与质量边界). Motion, infrared, and gel firing require three independent explicit gates.

Output defaults to `.hanppie/diagnosis/<timestamp>/report.md` and `events.jsonl`. The directory is ignored by Git, and its files are evidence for that run only. Architecture conclusions remain in the architecture document. Selecting `adb` or `system` temporarily exposes root ADB; cleanup always reboots the robot and confirms that TCP 5555 has closed. There is no compatibility option to skip this cleanup.

## Direct Python control

`DirectRobot` uses the S1 App data session directly. It neither uploads a Lab program nor calls the bundled official SDK fork. Mechanical commands require explicit control mode, arming, and a short lease:

```python
import time

from hanppie.lab import DirectRobot

robot = DirectRobot(robot_ip="192.168.1.100", appid="0123abcd")
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

The current API, capabilities, and open gaps are maintained only in the [technical architecture](./docs/architecture.md#711-当前能力矩阵). Gamepad, Web, and ROS 2 input layers are not implemented yet and must eventually feed a control arbiter rather than drive actuators directly.

## Codex MCP conversation control

Hanppie provides a local STDIO MCP server. The default installer safely adds it to the user-scoped Codex configuration shared by the ChatGPT desktop app, Codex CLI, and IDE extension on the same computer. Windows, macOS, Linux, and WSL all launch the current Python interpreter by absolute path, without requiring the `codex` command on `PATH`.

```bash
# Discover and connect the only usable S1 on the LAN automatically
uv run hanppie mcp install

# Or write only the current project's .codex/config.toml
uv run hanppie mcp install --scope project

# Pin the target when more than one S1 may be present
uv run hanppie mcp install \
  --replace \
  --robot-ip "$S1_IP" \
  --appid "$S1_APPID"

# Run the server directly when debugging client configuration
uv run hanppie mcp serve
```

Restart the relevant Codex client after installation, then use `/mcp` to confirm that `hanppie` is connected. `install` preserves every other Codex setting and MCP server. Repeating the same configuration is a no-op; changing an existing Hanppie entry requires `--replace`. User scope resolves `--codex-home`, then `CODEX_HOME`, then `~/.codex/config.toml`; project scope writes `.codex/config.toml` at the project root. Local MCP configuration is not available to ChatGPT Web; see the [Codex MCP documentation](https://learn.chatgpt.com/docs/extend/mcp).

The server exposes connection, status, Python-context, Python-execution, and disconnect tools. The first connection may automatically select the only usable S1 on the LAN. The same MCP server then reuses the active App connection across a continuous conversation and multiple tool calls instead of initializing it for every instruction. `execute_python` defaults to `robot_access=auto`; `reuse` exposes only an existing connection, while `none` explicitly runs host-only Python. Each call gets a fresh namespace with `robot`, `time`, `sleep`, `output_dir`, `save_frame`, and `checkpoint`, and returns `result`, streams, progress events, errors, and artifacts. Arbitrary source runs only in the PC-side Python 3.10 worker.

Installation and startup have no motion, infrared, or gel permission switches. Chassis, gimbal, and infrared use the normal `DirectRobot` `robot.arm()` and lease API. Gel firing is available as `result = robot.fire_gel()` or `robot.fire("gel")`; MCP switches to the verified Lab/Bridge path and waits for its execution result. A failed Lab transition is retried and attempts to restore the previous Direct connection; shared LED and stop operations do not cause unnecessary backend switches. Every successful or failed call still neutralizes and disarms without closing a healthy connection. A timeout kills the worker and the next call creates a new connection. Velocity multiplied by duration is not proof of an exact angle or distance; multi-stage code can call `checkpoint("stage", ...)` after each completed stage. Execution failures are returned as MCP tool errors so Codex does not mistake them for successful completion.

This is arbitrary Python execution for a trusted local user, not a security sandbox. Starting MCP, completing its handshake, and listing tools do not write files. The first actual Hanppie tool call activates `.hanppie/mcp/sessions/<session-id>/`: `server.log` contains server and worker events, `calls.jsonl` stores paired `started` and `completed` events under one `call_id`, and `calls/<run-id>/` contains files, images, and checkpoints produced by that Python call. `get_python_context` is itself a tool call, so it activates recording and reports the version, actual facade signatures, capability boundaries, and active paths; `--artifact-dir` changes the whole root. Call records contain submitted Python source and returned content and should be managed as local execution records. The [technical architecture](./docs/architecture.md#734-codex-mcp-与持久-host-python-worker) is authoritative for execution, cleanup, and concurrency boundaries.

## Xiaohanpi voice agent

Authorize Hanppie directly with the ChatGPT Codex device flow and grant the terminal access to the system microphone. The agent then listens continuously for “小憨批”, keeps a conversational window after wake-up, composes robot behavior, and understands the current camera frame. This mode does not require Codex to be installed or running:

```bash
uv run hanppie agent login

# With no OPENAI_API_KEY, auto uses the Codex OAuth stored by Hanppie
uv run hanppie agent run

# Execute text without audio or a wake word; repeated -p shares context and connection
uv run hanppie agent run --auth codex \
  -p "Describe what is nearby." -p "What stands out in that image?"

# Optional low-latency planner with a separate vision model
uv run hanppie agent run --auth codex \
  --codex-model gpt-5.3-codex-spark --codex-vision-model gpt-5.6-sol \
  --reasoning-effort low --model-timeout 30 -p "Set the armor lights to solid blue."

# Pin the target when multiple robots are present
uv run hanppie agent run \
  --auth codex \
  --robot-ip "$S1_IP" \
  --appid "$S1_APPID"
```

Hanppie performs the device-code OAuth flow and token refresh itself, then sends the Bearer token directly to ChatGPT's streaming Codex Responses backend. No Codex CLI, `codex exec`, or App Server subprocess participates. Credentials are atomically stored in `~/.hanppie/auth.json` with mode `0600` on POSIX; `HANPPIE_HOME` changes the directory, and `hanppie agent logout` deletes only this local credential. Requests use `store=false`, Hanppie replays the conversation from memory, and the default model is `gpt-5.6-sol`, overridable with `--codex-model`. This consumer backend is not the public OpenAI Platform API, so compatibility follows the current Codex OAuth protocol.

Local `faster-whisper` performs transcription (the default `small` model is downloaded on first use), and the operating system performs speech synthesis. When `OPENAI_API_KEY` is set, `auto` preserves the OpenAI transcription, Responses, and TTS path. Use `--auth api-key` or `--auth codex` to choose explicitly.

For example, say “小憨批，观察一下附近有些什么东西？” to capture and describe the S1's current forward camera view. Follow-ups do not need the wake phrase during the default 45-second active window. “退下” returns to sleep; “停止”, “停下”, and “别动” use a local stop path against an existing connection without waiting for the model. Repeat `--wake-phrase` for aliases, use `--active-timeout` to change the conversation window, select the microphone with `--audio-device`, select a local Whisper model with `--local-transcription-model`, or disable speech playback with `--no-tts`.

`--prompt` bypasses wake-word matching and disables audio input and playback. Execution failures return a nonzero exit code and stop subsequent prompts. Conversation and tool records are written to `.hanppie/agent/` only after a wake-up or prompt execution. See the [technical architecture](./docs/architecture.md#735-唤醒词连续对话与-langgraph-智能体) for data boundaries, LangGraph state, generated-code policy, and verification limits.

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

Hanppie's own code uses the [MIT License](./LICENSE). DJI-derived code under `src/robomaster` uses the directory's [Apache License 2.0](./src/robomaster/LICENSE.txt); see [`UPSTREAM.md`](./src/robomaster/UPSTREAM.md) for provenance.
