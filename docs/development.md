# Hanppie 开发指南

这里提供源码运行、构建和测试入口。实现与能力边界见[技术架构](./architecture.md)，工程约定见 [AGENTS.md](../AGENTS.md)。

## 环境

```bash
git clone https://github.com/elonzh/hanppie.git
cd hanppie
git lfs pull
```

- Kotlin 客户端需要 JDK 25；仓库自带 Gradle Wrapper。
- Android SDK 安装 API 37 与 Build Tools 36.1.0，在不提交的 `local.properties` 中设置 `sdk.dir`。
- iOS 需要 macOS、完整 Xcode 与 Git LFS。
- Python 工具链使用 Python 3.10 与 [uv](https://docs.astral.sh/uv/)；[Task](https://taskfile.dev/) 提供快捷命令。

## 桌面

```bash
./gradlew :desktopApp:run
./gradlew :desktopApp:createDistributable
```

Windows 使用 `gradlew.bat`。分发程序位于 `desktopApp/build/compose/binaries/main/app/`，包含 Java 运行时，不需要 Python。桌面音视频的 FFmpeg 配置见 [README](../README.md#安装)。

## Android

```bash
./gradlew :androidApp:assembleDebug :androidApp:lintDebug
adb -s <设备ID> install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

模拟器保留默认 NAT/DHCP 配置。遇到 `No route to host` 时，检查 macOS 的本地网络权限，并用现有 SDK 冷启动模拟器，保留应用数据：

```bash
"$ANDROID_HOME/emulator/emulator" -list-avds
"$ANDROID_HOME/emulator/emulator" -avd <设备名称> -no-snapshot-load
```

`ANDROID_HOME` 应指向项目使用的同一套 SDK。手动指定机器人 IPv4/AppID 的入口位于应用“诊断”页。

## iOS

打开 `iosApp/Hanppie.xcodeproj`，选择 `Hanppie` scheme 和目标设备。设置自己的 Apple Team、Bundle ID，以及包含 multicast 能力的描述文件后运行。最低 iOS 18.5，使用横屏布局。

生成未签名归档：

```bash
xcodebuild -project iosApp/Hanppie.xcodeproj -scheme Hanppie \
  -destination 'generic/platform=iOS' -archivePath build/Hanppie.xcarchive \
  CODE_SIGNING_ALLOWED=NO archive
```

未签名归档不能直接安装。连接时允许“本地网络”；直连模式先在系统 Wi-Fi 设置中加入机器人热点。麦克风、语音识别与照片权限按对应操作申请。

## Python

```bash
uv sync
```

原生直控通过 `hanppie.Robot` 使用 App 协议。机械动作要求显式进入控制模式、arm 和短租约，结束时归零并关闭连接：

```python
import time

from hanppie import Robot

robot = Robot(robot_ip="192.168.1.100", appid="0123abcd")
try:
    robot.initialize()
    robot.enter_control_mode()
    robot.set_led(red=0, green=255, blue=0)

    # 确认地面与射界安全后才允许机械动作。
    robot.arm()
    robot.chassis.drive_speed(x=0.1, lease_seconds=0.25)
    time.sleep(0.4)
finally:
    robot.disarm()
    robot.close()
```

仓库内的 DJI SDK fork 保留 `from robomaster import robot` 导入接口，来源见 [UPSTREAM.md](../src/robomaster/UPSTREAM.md)。依赖约束以 [`pyproject.toml`](../pyproject.toml) 与 [`uv.lock`](../uv.lock) 为准。

## 验证

按修改影响面选择检查，不必每次运行全部平台：

| 范围 | 命令 |
| --- | --- |
| Python 格式与静态检查 | `task lint` |
| Python 离线测试 | `task test` |
| Kotlin 协议与共享界面 | `task kotlin:test` |
| 桌面 GPU 场景 | `task scene:test`，需要图形桌面 |
| Android 构建与 lint | `task android:check` |
| Android UI | `task android:test`，需要已连接的设备或模拟器 |
| iOS 原生与 UI | `scripts/release/ios-tests.sh`，需要 iOS 18.5+ 模拟器运行时 |
| Python 分发包 | `task build` |
| 仓库 hooks | `task prek` |

客户端 UI 先完成桌面实现与验证，再按 [AGENTS.md](../AGENTS.md) 的要求开展 Android 验证。

桌面运行可通过 `HANPPIE_LLM_PROVIDER`、`HANPPIE_LLM_ENDPOINT`、`HANPPIE_LLM_MODEL`、`HANPPIE_LLM_API_KEY` 注入配置。真实模型测试使用测试默认供应商，需显式设置 `HANPPIE_LLM_LIVE_TEST=1` 与 `HANPPIE_LLM_API_KEY`，可用 `HANPPIE_LLM_MODEL` 覆盖模型：

```bash
./gradlew :shared:jvmTest --tests '*ChatAgentLiveTest' --rerun-tasks
```

实机测试使用 `HANPPIE_TEST_ROBOT_IP`、`HANPPIE_TEST_APPID`。`HANPPIE_TEST_ALLOW_LAB=1` 允许覆盖机内 Lab 槽位；`HANPPIE_TEST_ALLOW_REMOTE=1` 允许短时机械与红外操作。只在目标和动作范围已明确授权时启用，并关闭其他控制会话：

```bash
./gradlew :packages:robot-core:desktopTest --tests '*HardwareIntegrationTest' --rerun-tasks
./gradlew :shared:jvmTest --tests '*DesktopLiveTest' --rerun-tasks
```

API Key、设备标识与原始实机日志不得提交。离线测试、平台构建、UI 运行、设备响应与物理动作是不同的验证层级，结论与日期化证据按[技术架构](./architecture.md)的规则记录。
