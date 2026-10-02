# 更新日志

版本号以 [version.xcconfig](./version.xcconfig) 为准。尚未发布的修改记录在 `Unreleased`，每次正式分发由流水线提取对应版本条目。

## [Unreleased]

## [1.0.0] - 2026-10-03

首个 Alpha 版本，提供 RoboMaster 机器人连接、横屏操控、脚本工作台与 AI 对话。

### 新增

- Android、macOS、Windows 客户端，以及 iPhone / iPad 的 iOS 源码与未签名设备归档。
- 共享三维主页、驾驶舱、底盘与云台摇杆、五档速度、遥测、按键控制与失联归零。
- 直连及路由器配网、手动诊断、匿名 FTP 文件管理，以及唯一 Lab 槽位的脚本上传、启动、停止与日志。
- 本机脚本库、预置脚本、编辑、导入/导出与自定义音频资源。
- 兼容模型配置、流式 AI 对话、会话持久化和执行前的源码审批。
- iOS 的 VideoToolbox H.264、Apple Opus 编解码、照片/录像保存、按住录制松开发送的短片对讲与系统语音输入。
- GitHub 多平台发布、SHA-256 校验文件与 iPhone/iPad 测试。

### 分发与验证边界

- iOS 首版仅提供源码与未签名归档。归档不能直接安装，需要 Xcode 和 Apple 签名配置；当前不提供商店分发。
- iOS 要求 iOS 18.5 或更高版本；自动发现还需要 Apple 批准的 multicast entitlement。局域网权限、机器人连接与物理动作的实机结论见 [架构能力矩阵](https://github.com/elonzh/hanppie/blob/v1.0.0/docs/architecture.md#110-当前能力矩阵)。
- 已有机器人实机证据来自 RoboMaster S1；其他型号按协议和能力建模，仍需逐项验证。
- 桌面包不包含 FFmpeg；音视频与对讲需要另行配置。GitHub 首版桌面包未进行商店签名或公证，Android 使用开发签名。
