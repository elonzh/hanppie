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
| [Initial recovery log](./docs/s1-live-debug-2026-08-29.md), [App/Lab regression log](./docs/s1-live-regression-2026-08-30.md) | Commands, output, failures, and measurements from a dated run; never the current conclusion |
| [Early research report](./docs/robomaster-s1-revival-report.md) | Historical research into S.BUS, SocketCAN, vcan, ROS 2, and alternative approaches |
| [`src/robomaster/UPSTREAM.md`](./src/robomaster/UPSTREAM.md) | Provenance and maintenance boundary of the bundled DJI SDK fork |

Before using physical hardware, read the architecture's [safety and recovery model](./docs/architecture.md#713-安全与恢复模型). Its [capability matrix](./docs/architecture.md#711-当前能力矩阵) is the sole current status table.

## Installation

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

`diag` is the project's only physical-device validation and debugging entry point. It supports interactive and non-interactive execution through Typer and Rich, and every run creates a redacted Markdown report and JSONL event log:

```bash
# Show every check and its risk class
uv run hanppie diag --list

# Select checks interactively and confirm motion, infrared, and gel firing separately
uv run hanppie diag --interactive

# Standard non-mechanical diagnosis: discovery, App, video, Lab, LED cycle, speaker, ADB, system
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
