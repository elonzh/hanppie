# iOS 首版交付记录：2026-10-02

本记录保存此次验证证据；当前实现和能力边界以 [架构文档](./architecture.md#1312-ios-平台能力与分发) 为准，安装步骤见 [README](../README.md#安装)。产品版本为 `1.0.0`，iOS build 为 `1`。

## 本地验证

环境：Apple Silicon、Xcode 27.0、iOS / Simulator SDK 27.0、JDK 25。最低部署版本为 iOS 18.5。

| 层级 | 操作 | 结果 |
| --- | --- | --- |
| 机器人共享契约 | `:packages:robot-core:desktopTest` | 63 项，58 通过、5 项需机器人环境而跳过；0 失败 |
| 共享 UI / 持久化回归 | `:shared:jvmTest` | 271 项，261 通过、10 项环境限定而跳过；0 失败 |
| Python | Ruff 格式、静态检查、pytest | 34 项通过，10 月 3 日复核覆盖率 80.25% |
| 桌面分发 | `:desktopApp:packageDmg` | 通过 |
| Android 分发 | `:androidApp:assembleRelease :androidApp:lintRelease` | 10 月 3 日通过；APK 版本 `1.0.0`、build `1`，lint 0 错误、12 警告 |
| iOS 原生契约 | `:packages:robot-core:iosSimulatorArm64Test` | 10 月 3 日在 iOS 27.0 模拟器运行，32 项通过、0 失败、0 跳过 |
| iOS 应用、媒体单元测试及 UI 测试 | Xcode `test`，arm64 Simulator | iPhone 16、iPad Pro 11-inch (M4) 各 6 项通过、0 失败、0 跳过 |
| iOS 设备归档 | Xcode Release `archive`，关闭代码签名 | 通过；归档含共享资源、模型及隐私清单 |
| Apple Opus 编解码 | 本机 AVAudioConverter 往返 1 秒测试音频 | 51 个 Opus 包、48,840 个解码采样帧；属于 macOS 编解码证据 |
| 发布定义 | Actionlint、ShellCheck、Bash 语法及版本/CHANGELOG 一致性检查 | 通过 |

最终测试脚本正常退出，iPhone/iPad 的 xcresult 均为 Passed。10 月 3 日最终源码的日志保存在本机 `/tmp/hanppie-ios-local-tests-final-2026-10-03.log`、`/tmp/hanppie-ios-release-final-2026-10-03.log`、`/tmp/hanppie-final-android-2026-10-03.log`、`/tmp/hanppie-desktop-package-final-2026-10-03.log`、`/tmp/hanppie-final-kotlin-2026-10-03.log` 和 `/tmp/hanppie-final-python-2026-10-03.log`。测试记录及临时日志不随仓库提交。

版本统一为 `1.0.0` 后，重新完成 iOS Release 归档、桌面编译与 DMG 打包及 Python wheel 构建。归档与桌面应用的 `CFBundleShortVersionString` 均为 `1.0.0`，Python 导入版本与 wheel 元数据也为 `1.0.0`；发布脚本校验 Python 包与客户端版本一致。此次日志为 `/tmp/hanppie-version-1.0.0-ios.log`、`/tmp/hanppie-version-1.0.0-desktop.log` 和 `/tmp/hanppie-version-1.0.0-python.log`。

## 运行验证与发布

10 月 2 日本机 CoreSimulator 没有已安装的 iOS 运行时，Apple 目录请求失败。10 月 3 日重试 `xcodebuild -downloadPlatform iOS` 成功，已安装官方 iOS 27.0（24A434）arm64 运行时，下载日志为 `/tmp/hanppie-ios-runtime-download-2026-10-03.log`。

实际启动发现并修复了缺失的 `CADisableMinimumFrameDurationOnPhone` 配置和 3D UIKit 表面覆盖首页控制的问题。快速输入暴露共享草稿同步时读取旧收集状态的问题，改为读取当前草稿；iOS 语言目录使用临时参数域，避免启动参数盖过应用语言选择。测试等待导航、弹出菜单与键盘的稳定状态，再校验原有契约。

两种模拟器均验证 Apple Opus 往返与机器人包格式、短音频、坏包拒绝、VideoToolbox H.264 解码，以及离线导航、完整文本输入、软键盘布局、中英文切换和重启后的语言保存。首页 Metal 场景及其共享控制已进行人工视觉检查。型号只限定本轮验证环境，不限制支持设备列表。

Xcode 27 的结果还包含线程优先级诊断和 iPad 横屏配置的未来策略提醒；这些不是测试失败，也不构成真机性能或未来系统兼容性结论。工作流保留 xcresult 的日志、录屏与截图，关闭耗时的设备全量 sysdiagnose 收集。

GitHub iOS 工作流使用 runner 已安装的模拟器运行时执行同一测试脚本。首版发布必须等待平台检查成功；本记录将在实际发布后补充流水线链接与结果。

归档使用 `macos-26-intel`，其标准 runner 提供 14 GB 内存，默认 Xcode 26.6。工具链和容量依据 [GitHub runner 文档](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax) 与 [macOS 26 镜像清单](https://github.com/actions/runner-images/blob/main/images/macos/macos-26-Readme.md)。未在本机改变 Xcode 选择。

用户已确认功能完整并授权 Android 验证、通过后提交推送与首版发布。Android Release 构建与 lint 已通过；lint 的目标 SDK、固定横屏、备份配置、启动图标与 Commons Net 内置 TLS 信任器警告已保留，未通过隐藏检查消除。机器人 FTP 当前使用普通 FTP，不调用该 TLS 信任器；其余警告不构成未来系统兼容性结论。网站中英文产品介绍已在本地更新并完成静态检查、生产构建与响应式浏览器检查，网站未部署。

## 尚未验证的边界

- 没有执行 iOS 真机本地网络、广播 entitlement、机器人连接、音视频、麦克风/相册权限或机械动作验证。
- 用户尚未加入 Apple Developer Program；本次仅交付未签名归档，未执行设备签名安装或商店上传。
- GitHub iOS 归档未签名，不能直接安装；已验证的其他平台 S1 实机记录不能转移为 iOS 实机证据。
