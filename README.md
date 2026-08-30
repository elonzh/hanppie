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
| [初次恢复记录](./docs/s1-live-debug-2026-08-29.md)、[App/Lab 回归记录](./docs/s1-live-regression-2026-08-30.md) | 特定日期的命令、输出、故障和测量证据，不维护当前结论 |
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

# 标准非机械诊断：发现、App、视频、麦克风、扬声器、Lab、装甲灯、枪口灯、ADB 和机内信息
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
