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
| 界面语言 | 在“设置 → 通用 → 语言”选择简体中文、繁體中文、English、日本語、Deutsch、Español、Français、한국어、Русский 或跟随系统；选择后即时生效并自动保存，与机器人语音语言分别设置 |
| 使用指南 | 从首页“使用指南”或“设置 → 关于 → 使用指南”查看连接、驾驶、跟随、挑战、拍摄、Python、配件和维护步骤；可前后翻页、从头查看并打开对应功能 |
| 驾驶 | 连接后进入驾驶舱，使用双摇杆，或用 WASD 移动、Q/E 转向、方向键控制云台；Esc 返回工作台。完整按键见“设置 → 快捷键” |
| 鼠标与陀螺仪 | 驾驶舱按终端能力显示“鼠标云台”或“陀螺仪云台”。鼠标开启后移动瞄准、左键发射、右键变焦，先按 Esc 释放后再操作界面；陀螺仪可再次点击停止。“设置 → 控制”可保存各自灵敏度与俯仰方向。离页、后台或输入中断后需重新开启；[原生输入与设备待验项](./docs/official-app-capability-implementation.md#i03-鼠标与陀螺仪输入及设备待验) |
| 手柄信息 | 支持 USB 配件的 Android 终端从“设置 → 控制 → 手柄信息”进入独立页面，刷新并连接官方手柄，可查询固件/只读激活状态，查看电量与外接鼠标/键盘。后台会断开，返回后需重新连接；连接后可进入驾驶舱显式启用手柄；回中并松开按键，Escape 或“释放手柄驾驶”可释放，失效后不自动恢复。控制设置可调整本地响应；原生 USB 及设备结果[待验](./docs/official-app-capability-implementation.md#i02-独立配件会话与手柄信息阶段) |
| 控制设置 | 在“设置 → 控制”分别调整键盘/屏幕摇杆响应与应用速度上限；连接后可读取机器人运动参数，明确写入后回读确认。设备行为[待实机验证](docs/official-app-capability-implementation.md#i01-输入响应与运动参数实施进度及设备待验) |
| 画面与准星 | 在“设置 → 画面与准星”选择准星、画面适配、角度/臂位置显示及三维质量；连接后可校准准星或读写头灯亮度。应用偏好通过“保存设置”保存，准星校准与头灯更改需单独写入并回读；[设备待验项](docs/official-app-capability-implementation.md#d01-准星显示偏好与头灯实施进度及设备待验) |
| 对象跟随 | 在驾驶舱点击“对象跟随”，点选画面中的目标，锁定后点击“开始跟随”；使用“停止跟随”或操纵方向接管。当前实机验证边界见[架构说明](./docs/architecture.md#134-遥控操纵镜头坐标系与安全联动) |
| 单机挑战 | 在驾驶舱点击“挑战”，选择靶场或竞速；竞速可选编号目标数量及红外/水弹，也可关闭提示音。开始后按瞄准、命中与进度提示完成目标，可结束、查看本次成绩并重试。当前提供手动挑战，自动竞速与设备待验见[实施台账](./docs/official-app-capability-implementation.md#g01-手动挑战阶段与设备待验) |
| 相机变焦 | 驾驶舱点击倍率切换 1/4 倍；等待相机确认后显示倍率，恢复 1 倍后可启动对象跟随。当前实机边界见[实施台账](./docs/official-app-capability-implementation.md#a02-验证入口与设备待验) |
| 相机设置 | 在“设置 → 音视频 → 机器人相机”调整抗闪烁频率、读取相机日期，或同步本机时间。收到未同步状态时会自动尝试一次校时；[设备验证边界](./docs/official-app-capability-implementation.md#a03-验证入口与设备待验) |
| 画面与拍摄 | 在“设置 → 音视频 → 画面与拍摄”查看设备当前格式、调整照片尺寸与传输码率，或请求恢复视频画面；设备报告多相机时可选择画面来源。[设备待验项](./docs/official-app-capability-implementation.md#a07-验证入口与设备待验) |
| 存储卡维护 | 在“设置 → 机器人维护”查看机器人 SD 卡状态和容量，确认清空全部文件后可发送格式化请求；[设备待验项](./docs/official-app-capability-implementation.md#a04-验证入口与设备待验) |
| 机器人 Wi-Fi | 在“设置 → 机器人维护 → 机器人 Wi-Fi”查询网络信息；AP 模式下可修改名称和密码，再单独确认无线重启。在系统中加入相应网络后，返回页面重新连接并核对配置。[设备待验项](./docs/official-app-capability-implementation.md#n01-验证入口与设备待验) |
| 信道与干扰 | 在机器人 Wi-Fi 页切换至“信道与干扰”，查询当前信道和设备可用列表；AP 模式可确认自动/手动选择并设置手动信道。切换结果未确认时，检查系统网络后在原页重新连接核对。[设备待验项](./docs/official-app-capability-implementation.md#n02-验证入口与设备待验) |
| SDR 配置 | 当前连接明确返回 SDR 模式及有效配置后，“设置 → 机器人维护”显示 SDR 链路。可查询配置、确认自动/手动切换，并在手动模式下按能力调整频段、带宽和频率；结果未确认时在此重新连接核对。[设备待验项](./docs/official-app-capability-implementation.md#n03-验证入口与设备待验) |
| 机器人状态 | 首页/驾驶舱的异常计数可直达维护页，也可从“设置 → 机器人维护”查看模块报告、异常解释和电池原始读数；展开模块列表或提示记录可查看更多信息，模块报告注明距今时间。[设备待验项](./docs/official-app-capability-implementation.md#h01-验证入口与设备待验) |
| 设备信息 | 在“设置 → 机器人维护 → 设备信息”查看机器人里程和驾驶时长；展开后查询激活状态与模块版本，可单项重试或取消等待。[设备待验项](./docs/official-app-capability-implementation.md#h02-验证入口与设备待验) |
| 电源与编程节能 | 在“设置 → 机器人维护 → 电源与待机”查询编程节能设置，选择时限或关闭，并确认应用；也可单独确认立即待机、退出待机、重启或关机。除退出待机外，动作后结束会话，核对机器人状态后再连接。编辑器不会自动切换机器人编程模式，见 [C14 与设备待验项](./docs/official-app-capability-implementation.md#h03-验证入口与设备待验) |
| 机器人 SD 拍录 | 驾驶舱左上方点击“SD 卡拍录”，可拍照、开始或停止机内录像；入口显示机内录像秒数，原主机拍录按钮保持独立。[设备待验项](./docs/official-app-capability-implementation.md#a05-验证入口与设备待验) |
| 机器人媒体 | 在“SD 卡拍录”面板或“设置 → 机器人维护”点击“浏览媒体”，点选文件查看缩略图并下载；“已下载”中可打开、用系统播放器播放视频、导出或删除本地副本。[设备待验项](./docs/official-app-capability-implementation.md#a06-验证入口与设备待验) |
| 机器人音频 | 在“设置 → 音视频”查询机器人音量、应用音量并回读确认，或更改机器人语音语言；与应用界面语言分别设置。当前设备验证边界见[实施台账](./docs/official-app-capability-implementation.md#a01-验证入口与设备待验) |
| 收音与播放 | 在“设置 → 音视频 → 机器人收音与播放”查询收音开关、查看最近播放状态或发送防啸叫配置。驾驶舱“按住对讲”录制短片，松开后上传播放。[设备待验项](./docs/official-app-capability-implementation.md#a08-验证入口与设备待验) |
| 编写脚本 | 在“脚本”页新建、导入 `.py`，或从预置脚本创建副本；保存到本机脚本库，再点击“运行脚本”。Lab 脚本使用机内 API 和 Python 3.6 语法，见 [Lab 编程指南](./shared/src/commonMain/composeResources/files/skills/lab-python/SKILL.md) |
| 程序面板 | 机载程序创建可见控件后，点击脚本页的“程序面板”操作按钮、开关、滑条、选项或输入框；可滚动查看布局、打开监看或返回编辑。关闭面板不会停止程序，停止请使用面板上的停止按钮。[设备待验项](./docs/official-app-capability-implementation.md#p03-程序面板与设备待验) |
| 终端运动读数 | Python 程序通过 `mobile_ctrl` 读取手机/平板的可用运动传感器；桌面不提供替代数据。[只打印读数的示例](./shared/src/commonMain/composeResources/files/skills/lab-python/references/sensors-and-vision.md#4-移动端传感器-mobile_ctrl)；[设备待验项](./docs/official-app-capability-implementation.md#p05-终端运动传感器与设备待验) |
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
