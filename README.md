# Hanppie（憨皮）

[简体中文](./README.md) | [English](./README_EN.md)

[![CI](https://github.com/elonzh/hanppie/actions/workflows/ci.yml/badge.svg)](https://github.com/elonzh/hanppie/actions/workflows/ci.yml)
[![Python](https://img.shields.io/badge/Python-3.10-blue)](https://www.python.org/)
[![License](https://img.shields.io/badge/License-MIT%20%2B%20Apache--2.0-green.svg)](./NOTICE.md)

Hanppie 是一个面向 DJI RoboMaster S1 的开源保存与电脑编程工具箱。项目目标是在不依赖手机 App 的前提下，恢复可审计、可回滚的连接、编程、遥测和远程控制能力。

项目尚处于 Alpha 阶段，不隶属于 DJI，也未获得 DJI 背书。

## 已在真机验证

测试设备：RoboMaster S1，固件 `00.06.0521`。

- App/Lab 兼容链路：电脑连接、机内 Lab Python、姿态回传；
- App/Lab 视频：`1280×720` H.264 图传解码；
- 从 Lab 临时开启 USB/TCP root ADB；
- 以 bind mount 临时恢复 DJI 官方 SDK 服务，重启即回滚；
- 内置 RoboMaster SDK fork：握手、`Robot.initialize()`、版本、序列号、模式、云台角度和 LED；
- SDK 补丁的状态检查、在线恢复和重新启用完整往返。

已知边界：SDK 相机请求会被 S1 拒绝；部分 EP DDS 主题在 S1 上只回传 0。推荐用内置 SDK fork 负责控制，用 App/Lab 后端负责视频与 S1 特有数据。底盘、云台机械运动和发射器尚未完成安全条件下的物理验证。

项目的长期技术事实源是[《RoboMaster S1 技术架构》](./docs/architecture.md)。完整实测证据见[真机联调记录](./docs/s1-live-debug-2026-08-29.md)，早期软硬件、S.BUS、SocketCAN、vcan 和 ROS 2 调研见[调研报告](./docs/robomaster-s1-revival-report.md)。

## 安全警告

ADB bootstrap 会在局域网的 TCP 5555 暴露**无认证 root shell**。只应在可信、隔离的网络中短时使用，维护完成后重启 S1。不要为机器人配置公网端口转发。

首次运动测试前必须：

- 让车轮悬空或清空足够大的测试区域；
- 取出水弹，单独禁用发射功能；
- 设置 dead-man、限速和网络超时归零；
- 手边保留物理电源开关。

## 安装

项目以 Python 3.10 为开发与测试基线，并使用 [uv](https://docs.astral.sh/uv/) 管理环境。开发者还可以安装 [Task](https://taskfile.dev/) 作为统一任务入口。

```bash
git clone https://github.com/elonzh/hanppie.git
cd hanppie
uv sync
uv run hanppie --help
```

仓库直接维护 `src/robomaster`，它来自 DJI RoboMaster SDK `0.1.1.68`/`ff6646e` 的纯 Python 源码，并保持官方导入路径和主要公共接口：

```python
from robomaster import robot

s1 = robot.Robot()
```

`uv sync` 会同时安装 Hanppie 与内置 SDK fork，不再需要官方 wheel、外部 SDK checkout 或 `HANPPIE_OFFICIAL_SDK_PATH`。固定的 LAB-SDK 发布物还会安装自己的同名 `robomaster` 兼容包，因此 `task sync:lab` 会把它放入独立的 `.venv-lab`，避免覆盖主环境中的内置 fork；Lab 命令使用 `task run:lab -- ...`。当前只验证 Python 3.10，不把更高版本列入测试矩阵。

## 命令行

```text
hanppie sdk                 临时 SDK 补丁的 status / enable / restore
hanppie adb-enable          通过 App/Lab 链路临时开启 root ADB
hanppie probe-connection    官方 SDK 低层握手
hanppie probe-official      官方 SDK 身份与基础遥测
hanppie probe-telemetry     官方 SDK 多主题能力矩阵
hanppie probe-led           无机械运动的 LED 控制验证
hanppie probe-camera        复现官方相机兼容边界
hanppie probe-lab           App/Lab 程序和姿态回传
hanppie probe-video         App/Lab 视频解码
```

每个命令都有独立帮助，例如：

```bash
uv run hanppie sdk --help
task run:lab -- adb-enable --help
```

### 从重启状态恢复 SDK proxy

重启后 ADB 和 SDK bind mount 都会消失。先选择 Lab 后端并开启 ADB：

```bash
export S1_IP="192.168.x.x"
export S1_APPID="your-8-character-appid"
export S1_SERIAL="your-s1-serial"

task sync:lab
task run:lab -- adb-enable \
  --robot-ip "$S1_IP" \
  --appid "$S1_APPID" \
  --settle-seconds 8
```

确认 `adb devices -l` 后，直接使用经过哈希验证的社区补丁目录：

```bash
uv run hanppie sdk \
  --target "$S1_IP:5555" \
  enable \
  --hack-dir /path/to/audited/s1_sdk_hack

uv run hanppie probe-official \
  --robot-ip "$S1_IP" \
  --sn "$S1_SERIAL"
```

在线恢复原厂运行态：

```bash
uv run hanppie sdk \
  --target "$S1_IP:5555" \
  restore
```

`enable` 只接受已审计文件的固定 SHA-256，不覆盖 `/system`，并在设备端再次校验上传结果。

## Python 包结构

```text
src/
├── hanppie/
│   ├── cli.py              # 统一 CLI
│   ├── sdk_patch.py        # ADB + 临时 SDK 服务管理
│   ├── adb_bootstrap.py    # App/Lab ADB bootstrap
│   ├── media_codec.py      # PyAV 媒体接口兼容层
│   ├── probes/             # 安全、单一目的的真机探针
│   ├── payloads/           # 临时上传到 S1 的最小载荷
│   ├── runtime/            # 恢复的 S1 Lab/DUSS 运行时代码
│   └── resources/          # 非可执行的设备配置参考
└── robomaster/             # 内置、保持官方接口的 S1 SDK fork
```

`src/robomaster` 保持 `from robomaster import robot` 等官方接口，并由 Hanppie 针对 S1 逐项维护；`runtime` 是机内代码的恢复参考。普通主机控制仍应优先从 `hanppie` CLI 进入。

## 开发工具链

```bash
task sync       # uv 同步基础开发环境
task format     # Ruff 格式化和安全修复
task lint       # Ruff 格式与 lint 检查
task test       # pytest + coverage
task check      # lint + test
task build      # sdist + wheel
task hooks      # 安装 prek pre-commit/pre-push hooks
task prek       # 对全部文件执行 hooks
```

项目配置集中在 [`pyproject.toml`](./pyproject.toml)、[`Taskfile.yml`](./Taskfile.yml) 和 [`prek.toml`](./prek.toml)，CI 使用相同的 uv 锁文件与命令。

贡献前请阅读 [`CONTRIBUTING.md`](./CONTRIBUTING.md)，技术或能力变化应同步更新 [`docs/architecture.md`](./docs/architecture.md)。安全问题见 [`SECURITY.md`](./SECURITY.md)，来源与商标说明见 [`NOTICE.md`](./NOTICE.md)。

## 路线图

- 在车轮悬空条件下完成底盘低速与云台小角度闭环验证；
- 实现带 dead-man、250 ms watchdog 和限速的手柄控制守护进程；
- 将 App/Lab 视频与内置 SDK 控制合并为统一 capability adapter；
- 为 ROS 2 `cmd_vel`、相机和有效遥测提供安全适配；
- 建立不同 S1 固件版本的可复现实测矩阵。

## 许可证

Hanppie 自有主机代码使用 [MIT License](./LICENSE)；`src/robomaster` 的 DJI 派生代码使用 Apache License 2.0。恢复的设备运行时代码和社区后端适用各自来源的许可与条款，详见 [`NOTICE.md`](./NOTICE.md)。
