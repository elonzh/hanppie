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

Android 17 / API 37 的 Medium Phone 模拟器（2400×1080 横屏）复核发现，键盘压缩高度后，页面标题与对话记录工具栏会挤占输入栏。共享布局现按可用高度压缩标题，并将对话记录入口移到输入行，保留输入框及操作按钮的 48 dp 触控高度。新增短横屏回归，53 项 Console 测试全部通过；Android Release 重建、lint 及实际输入截图复核通过，完整保留 `Review before sending` 草稿，未提交模型请求。日志为 `/tmp/hanppie-short-chat-platforms-2026-10-03.log`。本轮模拟器采用无窗口、软件 GPU 且关闭音频的模式，属于界面证据。

首次 GitHub CI（[37044244790](https://github.com/elonzh/hanppie/actions/runs/37044244790)）在检出阶段发现远端缺少 35 个当前 LFS 素材对象，尚未执行构建与测试。已补传素材，并用全新缓存从 GitHub 下载当前全部 36 个对象、逐项核对 SHA-256。本机恢复 Git LFS 与 prek 串联的推送钩子。

后续 CI（[37047159432](https://github.com/elonzh/hanppie/actions/runs/37047159432)）的 Android、Python 和 macOS 桌面检查通过，Windows 预置脚本解析和 Linux 外观切换各有一项失败。测试进程输入改为显式 UTF-8，流水线固定 Python 3.10；外观测试等待上一个菜单退出后再打开下一个，保留原有状态、持久化和编辑器断言。本地两项定向回归通过，流水线开始保存桌面测试报告。

共享键盘布局修改后的 iPhone 完整复核为 6 项通过；iPad 首次复核的重启语言测试因导航过渡时点击不稳定失败。UI 测试改为等待可点击元素的坐标稳定，再操作同一控件，未放宽原有语言和重启断言，并增加输入框至少 48 pt 的检查及失败截图、辅助功能树。本地 iPad 该项定向复核通过，日志为 `/tmp/hanppie-ipad-settings-stable-2026-10-03.log`。最终云端结果仍须以修复后的提交为准。

修复后的 CI（[37050182333](https://github.com/elonzh/hanppie/actions/runs/37050182333)）中 Windows、Android 和 Python 检查通过；Linux 首次外观选择及 macOS 模型菜单出现仍有点击时机失败。JVM 界面测试进一步用测试时钟推进导航和菜单动画、等待绘制及选项出现，仍执行真实鼠标点击。全部 53 项 Console 本地复核通过，日志为 `/tmp/hanppie-popup-frames-local-2026-10-03.log`；CI 同时保存已有 UI 截图。本机 iPhone 的输入高度新断言复核通过，日志为 `/tmp/hanppie-iphone-keyboard-touch-size-2026-10-03.log`。

继续复核发现共享下拉项的标签外框宽 200 px，但实际可点击行仅宽 104 px。点击整行右侧的新增回归在修复前稳定失败；外框向行传递最小约束并保持 48 dp 高度后，该回归及全部 53 项 Console 测试通过，Android Release 与 lint 通过。日志为 `/tmp/hanppie-dropdown-edge-before-2026-10-03.log` 和 `/tmp/hanppie-dropdown-row-final-2026-10-03.log`。菜单点击仍使用鼠标事件，没有替换为直接调用状态或辅助功能动作。

首轮云端 iOS 原生契约构建成功，但 iOS 26.5 上应用以 SIGABRT 提前退出，媒体测试未能启动，UI 测试未找到导航。日志显示 Filament 启动后 SimMetalHost 连接中断，尚不能仅凭日志确定根因。模拟器测试改用与本机相同的 Xcode 27 / iOS 27 工具链，并在失败时保存应用和 SimMetalHost 崩溃报告及定向运行日志；实际渲染与原有媒体、界面断言均保留。[GitHub 官方 Xcode 27 镜像](https://github.com/actions/runner-images/blob/main/images/macos/xcode-27-arm64-Readme.md) 当前为公开预览。Actionlint 当前版本尚未识别官方 `xcode-27` 标签；仅排除此标签提示后，其余检查、ShellCheck 及 Bash 语法检查通过。旧提交的剩余重复 CI 已停止，新提交仍须完成全部平台检查。

整行修复后的 CI（[37052425839](https://github.com/elonzh/hanppie/actions/runs/37052425839)）已通过 Windows、Linux、macOS、Android 和 Python。iOS 模拟器任务在下载 Gradle 时连接重置，未进入编译或应用测试。通过官方 `wrapper` 任务启用三次下载重试、初始 1 秒退避和 30 秒网络超时，仍使用 Gradle 9.6.0；生成的 Wrapper JAR 与官方 SHA-256 一致，`--version` 检查通过。本机 Android Release 包同时复核右侧点击能切换外观、强制停止重启后设置保持，随后恢复系统默认设置并停止模拟器。

Xcode 27 的结果还包含线程优先级诊断和 iPad 横屏配置的未来策略提醒；这些不是测试失败，也不构成真机性能或未来系统兼容性结论。工作流保留 xcresult 的日志、录屏与截图，关闭耗时的设备全量 sysdiagnose 收集。

GitHub iOS 工作流使用 runner 已安装的模拟器运行时执行同一测试脚本。首版发布必须等待平台检查成功；本记录将在实际发布后补充流水线链接与结果。

归档使用 `macos-26-intel`，其标准 runner 提供 14 GB 内存，默认 Xcode 26.6。工具链和容量依据 [GitHub runner 文档](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax) 与 [macOS 26 镜像清单](https://github.com/actions/runner-images/blob/main/images/macos/macos-26-Readme.md)。未在本机改变 Xcode 选择。

用户已确认功能完整并授权 Android 验证、通过后提交推送与首版发布。Android Release 构建与 lint 已通过；lint 的目标 SDK、固定横屏、备份配置、启动图标与 Commons Net 内置 TLS 信任器警告已保留，未通过隐藏检查消除。机器人 FTP 当前使用普通 FTP，不调用该 TLS 信任器；其余警告不构成未来系统兼容性结论。网站中英文产品介绍已在本地更新并完成静态检查、生产构建与响应式浏览器检查，网站未部署。

## 尚未验证的边界

- 没有执行 iOS 真机本地网络、广播 entitlement、机器人连接、音视频、麦克风/相册权限或机械动作验证。
- 用户尚未加入 Apple Developer Program；本次仅交付未签名归档，未执行设备签名安装或商店上传。
- GitHub iOS 归档未签名，不能直接安装；已验证的其他平台 S1 实机记录不能转移为 iOS 实机证据。
