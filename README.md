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
