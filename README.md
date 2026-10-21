<img src="./assets/brand/graphite-orange/source/app-icon-master.png" width="72" align="right" alt="Hanppie 图标" />

# Hanppie（憨皮）

[![English](https://img.shields.io/badge/English-DF6B38?style=flat&logo=googletranslate&logoColor=white)](./README.en.md)
[![License: Apache-2.0](https://img.shields.io/badge/License-Apache--2.0-DF6B38?style=flat)](./LICENSE)
[![macOS、Windows、Android、iOS](https://img.shields.io/badge/Platforms-macOS%20%C2%B7%20Windows%20%C2%B7%20Android%20%C2%B7%20iOS-DF6B38?style=flat)](#安装)
[![产品官网](https://img.shields.io/badge/%E5%AE%98%E7%BD%91-Hanppie-DF6B38?style=flat)](https://elonzh.cn/toys/hanppie)
[![下载 Hanppie](https://img.shields.io/badge/%E4%B8%8B%E8%BD%BD-Hanppie-DF6B38?style=flat)](https://github.com/elonzh/hanppie/releases/latest)
[![更新记录](https://img.shields.io/badge/%E6%9B%B4%E6%96%B0%E8%AE%B0%E5%BD%95-DF6B38?style=flat)](./CHANGELOG.md)

RoboMaster 开源工作台。在电脑、手机和平板上驾驶机器人、编写 Python 脚本，也可以让 AI 帮你编程。

![Hanppie](./assets/brand/graphite-orange/github/social-preview.png)

## 安装

从 [GitHub Releases](https://github.com/elonzh/hanppie/releases/latest) 下载对应文件，可用同页的 `SHA256SUMS.txt` 校验。

| 平台 | 安装方式 |
| --- | --- |
| macOS | 打开 DMG，将 Hanppie 拖入“应用程序”；发布包为 Apple Silicon（arm64）版本 |
| Windows | 运行 x64 MSI 安装包 |
| Android | 安装 APK，最低 Android 8.0 |
| iPhone / iPad | 最低 iOS 18.5；提供源码与未签名归档，需要使用 Xcode 签名安装，见[开发指南](./docs/development.md#ios) |

## 连接机器人

1. 打开 Hanppie，进入“添加或更换机器人”。
2. 选择直连或路由器模式：直连时加入机器人热点；路由器模式在 Hanppie 中填写 Wi-Fi 信息，让机器人扫描生成的二维码。
3. 按系统提示允许本地网络访问，连接成功后即可开始使用。再次打开时，应用会尝试恢复上一次连接。

不需要 RoboMaster 官方 App。手动 IPv4/AppID 配置位于“诊断”页，供连接排障使用。

## 使用

| 任务 | 操作 |
| --- | --- |
| 驾驶 | 连接后进入驾驶舱，使用双摇杆，或用 WASD 移动、Q/E 转向、方向键控制云台；Esc 返回工作台。完整按键见“设置 → 快捷键” |
| 编写脚本 | 在“脚本”页新建、导入 `.py`，或从预置脚本创建副本；保存到本机脚本库，再点击“运行脚本”。Lab 脚本使用机内 API 和 Python 3.6 语法，见 [Lab 编程指南](./shared/src/commonMain/composeResources/files/skills/lab-python/SKILL.md) |
| AI 编程 | 在“设置 → 模型服务”选择供应商，填写 API Key、模型并测试配置；在“对话”页描述需求，检查完整脚本后确认执行 |
| 停止脚本 | 使用“停止脚本”。退出编辑器或取消 AI 生成不会停止已经启动的机内脚本 |

运行脚本会覆盖机器人的当前 Lab 运行槽位。执行动作前检查源码，并为机器人留出安全空间。文本对话会发送给你选择的模型供应商；密钥保存在应用私有数据中。

## Python 与开发

Python 工具链使用 Python 3.10 与 [uv](https://docs.astral.sh/uv/)：

```bash
git clone https://github.com/elonzh/hanppie.git
cd hanppie
uv sync
```

使用 `from hanppie import Robot` 访问原生直控，也可使用仓库内的 `robomaster` SDK fork。源码运行、构建、测试和 Python 示例见[开发指南](./docs/development.md)。

## 参考

- [技术架构](./docs/architecture.md)：实现、设备能力与安全边界。
- [RoboMaster 架构与协议](./docs/architecture-robomaster.md)：原机机制、协议与上游研究。
- [设计规范](./DESIGN.md)：产品体验、布局与视觉语言。

实机验证范围见技术架构的[能力矩阵](./docs/architecture.md#110-当前能力矩阵)。Hanppie 是独立项目，不隶属于 DJI。

自有代码采用 [Apache License 2.0](./LICENSE)；内置 FFmpeg 的来源与许可证见[第三方说明](./third-party/ffmpeg/NOTICE.md)，DJI SDK 派生代码见 [UPSTREAM.md](./src/robomaster/UPSTREAM.md)。
