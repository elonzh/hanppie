# Hanppie（憨皮）

[![CI](https://github.com/elonzh/hanppie/actions/workflows/ci.yml/badge.svg)](https://github.com/elonzh/hanppie/actions/workflows/ci.yml)
[![Python](https://img.shields.io/badge/Python-3.8%2B-blue)](https://www.python.org/)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](./LICENSE)

Hanppie 是一个面向 DJI RoboMaster S1 的开源保存与电脑编程工具箱。项目目标是在不依赖手机 App 的前提下，恢复可审计、可回滚的连接、编程、遥测和远程控制能力。

项目尚处于 Alpha 阶段，不隶属于 DJI，也未获得 DJI 背书。

## 已在真机验证

测试设备：RoboMaster S1，固件 `00.06.0521`。

- App/Lab 兼容链路：电脑连接、机内 Lab Python、姿态回传；
- App/Lab 视频：`1280×720` H.264 图传解码；
- 从 Lab 临时开启 USB/TCP root ADB；
- 以 bind mount 临时恢复 DJI 官方 SDK 服务，重启即回滚；
- 官方 Python SDK：握手、`Robot.initialize()`、版本、序列号、模式、云台角度和 LED；
- SDK 补丁的状态检查、在线恢复和重新启用完整往返。

已知边界：官方 SDK 的相机请求会被 S1 拒绝；部分 EP DDS 主题在 S1 上只回传 0。推荐用官方 SDK 负责控制，用 App/Lab 后端负责视频与 S1 特有数据。底盘、云台机械运动和发射器尚未完成安全条件下的物理验证。

完整证据见[真机联调记录](./docs/s1-live-debug-2026-08-29.md)，软硬件、S.BUS、SocketCAN、vcan 和 ROS 2 背景见[调研报告](./docs/robomaster-s1-revival-report.md)。

## 安全警告

ADB bootstrap 会在局域网的 TCP 5555 暴露**无认证 root shell**。只应在可信、隔离的网络中短时使用，维护完成后重启 S1。不要为机器人配置公网端口转发。

首次运动测试前必须：

- 让车轮悬空或清空足够大的测试区域；
- 取出水弹，单独禁用发射功能；
- 设置 dead-man、限速和网络超时归零；
- 手边保留物理电源开关。

## 安装

核心包需要 Python 3.8+ 和 [uv](https://docs.astral.sh/uv/)。开发者还可以安装 [Task](https://taskfile.dev/) 作为统一任务入口。

```bash
git clone https://github.com/elonzh/hanppie.git
cd hanppie
uv sync
uv run hanppie --help
```

两个机器人后端都提供名为 `robomaster` 的顶层 Python 包，因此不能同时导入。兼容策略以 DJI 官方发行物为起点，而不是把当前开发机的 Python 版本当作下限：

| 后端 | Python | 安装方式 |
| --- | --- | --- |
| DJI 官方 SDK | 3.8 | Linux x86_64 / Windows x86_64 可安装官方 `robomaster==0.1.1.68` wheel |
| DJI 官方 SDK | 3.8+ | macOS 或 Python 3.9+ 使用固定 commit 的官方源码 checkout |
| S1 App/Lab SDK | 3.10+ | 安装固定 commit 的社区包 |

DJI 的 PyPI 发布只有 CPython 3.6、3.7、3.8 的 Linux/Windows x86_64 wheel，没有源码包和 macOS wheel。Hanppie 选择 3.8 作为项目与现代工具链共同支持的最低版本；本项目保留的 PyAV 媒体接口和 Python 3.13+ `audioop` 兼容依赖，让固定官方源码也能在较新的 Python 和 macOS 上运行。使用 Task 可以安装各后端的主机依赖：

```bash
# DJI 官方 SDK 后端；3.8 的受支持平台会直接安装官方 wheel
task sync:official

# macOS 或 Python 3.9+ 还需要固定的官方源码 checkout
git clone https://github.com/dji-sdk/RoboMaster-SDK.git ../RoboMaster-SDK
git -C ../RoboMaster-SDK checkout ff6646e115ab125af3207a4ed3df42cc76c795b2
export HANPPIE_OFFICIAL_SDK_PATH=../RoboMaster-SDK

# S1 App/Lab 兼容后端（Python 3.10+）
task sync:lab
```

`uv sync` 使用锁文件精确同步环境。`official` 和 `lab` extras 在 uv 中被声明为互斥，避免两个同名包静默覆盖彼此。

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
uv run hanppie adb-enable --help
```

### 从重启状态恢复官方 SDK

重启后 ADB 和 SDK bind mount 都会消失。先选择 Lab 后端并开启 ADB：

```bash
export S1_IP="192.168.x.x"
export S1_APPID="your-8-character-appid"
export S1_SERIAL="your-s1-serial"

task sync:lab
uv run hanppie adb-enable \
  --robot-ip "$S1_IP" \
  --appid "$S1_APPID" \
  --settle-seconds 8
```

确认 `adb devices -l` 后选择官方后端，再使用经过哈希验证的社区补丁目录：

```bash
task sync:official
export HANPPIE_OFFICIAL_SDK_PATH=../RoboMaster-SDK
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
src/hanppie/
├── cli.py              # 统一 CLI
├── sdk_patch.py        # ADB + 临时 SDK 服务管理
├── adb_bootstrap.py    # App/Lab ADB bootstrap
├── media_codec.py      # macOS 上的 DJI 媒体接口兼容层
├── probes/             # 安全、单一目的的真机探针
├── payloads/           # 临时上传到 S1 的最小载荷
├── runtime/            # 恢复的 S1 Lab/DUSS 运行时代码
└── resources/          # 非可执行的设备配置参考
```

`runtime` 用于协议研究和离线测试；主机控制应从 `hanppie` CLI 进入，不依赖历史平铺导入路径。

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

贡献前请阅读 [`CONTRIBUTING.md`](./CONTRIBUTING.md)，安全问题见 [`SECURITY.md`](./SECURITY.md)，来源与商标说明见 [`NOTICE.md`](./NOTICE.md)。

## 路线图

- 在车轮悬空条件下完成底盘低速与云台小角度闭环验证；
- 实现带 dead-man、250 ms watchdog 和限速的手柄控制守护进程；
- 将 App/Lab 视频与官方 SDK 控制合并为统一 capability adapter；
- 为 ROS 2 `cmd_vel`、相机和有效遥测提供安全适配；
- 建立不同 S1 固件版本的可复现实测矩阵。

## License

主机侧 Hanppie 代码使用 [MIT License](./LICENSE)。恢复的设备运行时代码、可选 DJI SDK 和社区后端适用各自来源的许可与条款，详见 [`NOTICE.md`](./NOTICE.md)。
