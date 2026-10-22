# Hanppie 与官方 RoboMaster App 差距调查 · 2026-10-22

## 结论

Hanppie 已覆盖“连接已就绪的机器人 → 遥控与音视频 → 编写/运行机载 Python → AI 辅助创作”的主要路径，但还不是官方 App 的完整替代品。最大的整块缺口是首用与维护、完整相机/SD 卡管理、官方输入与设备参数配置、视觉交互的其余部分、对战/单机挑战，以及 Scratch/课程/技能安装体系。

本轮新增对象跟随已经有原生状态机与界面，但仍缺官方跟随中的距离调节，且只有离线验证，不能列为“与官方完全一致”。普通 Python 脚本能运行不等于已经支持官方自主程序、自定义技能、脚本自定义 UI 或终端传感器数据。

需要优先关注的三个具体差异是：官方水弹客户端安全模式有俯仰角发射锁，当前没有等价约束；连接初始化仍写入固定 JP 国家代码；跟随中的前后输入在官方用于调整距离，在当前实现中会取消跟随。这些是代码层已定位的事实，本轮没有改动控制代码。

## 调查范围和可信度

- **Hanppie 基线**：工作目录 `/Users/elonzh/workspace/hanppie`，HEAD `6e1da73cb0b801ee5377d58eb1aec10ea71b5eed`，包含本轮调查开始时尚未提交的对象跟随代码。没有切换、提交、推送或覆盖这些改动。
- **官方二进制基线**：此前提取的 macOS RoboMaster **1.1.5/build 239**，C# 业务层、RobotService、原生 `unitybridge` 与安装包资源。安装包及原生库指纹沿用[静态提取记录](./s1-app-static-analysis-2026-09-08.md)和[跟随离线记录](./object-tracking-validation-2026-10-22.md)。这是固定版本调查，**不声称覆盖当前 Android/iOS 最新二进制的全部差异**。
- **外部交叉核对**：本次读取 [DJI S1 官方下载中心](https://www.dji.com/downloads/products/robomaster-s1)、[官方支持页](https://www.dji.com/robomaster-s1?from=nav&site=brandsite)与[官方编程指南](https://www.dji.com/robomaster-s1/programming-guide)。功能和协议结论以本地版本代码为主；没有完成官方用户手册 PDF 的全文读取。下载中心页面没有提供本次可可靠提取的各端最新版本号，不据此推断版本一致。
- **未做**：连接机器人、启动厂商 App、修改固件、实机动作、Android 构建、账户登录或官方云服务可用性验证。本轮是代码/文档调查，没有重新运行构建或机械测试；先前 33 项离线测试属于跟随实现记录，不能替代本轮缺失功能的验证。
- **状态定义**：“已接入”只代表代码路径存在；“部分”表示同一任务只覆盖一段；“不同路径”表示已有可用替代，但协议或体验不同；“固定初始化”表示存在发送片段而没有用户设置/回读；“缺失”指当前应用主链路没有完整实现；“未确认可达”指只有 key/默认字段/内部代码，尚未证明公开入口可用。

### 全量清单与计数

| 检查面 | 本轮核对结果 | 含义 |
| --- | --- | --- |
| 官方 RobotService 桥接 key | **343 个，逐项列出** | [完整 key 附表](./official-app-key-inventory-2026-10-22.md)；不是 343 条线上指令 |
| key 的非 Base.CONTROL C# 具名引用 | 235 个存在、108 个未找到 | 含内部工具/条件分支，不能视为 235 个公开功能 |
| 官方持久偏好默认 schema | **75 个实际字段** | [配置附表](./official-app-config-inventory-2026-10-22.md)；另列裁判表、设备参数和硬件条件配置 |
| 原生 SDK 符号检查 | 21 类、615 条方法入口记录 | 包含辅助方法及 cold 入口，不作为指令数量或覆盖率 |
| 当前 robot-core `CMD_*` 常量 | 50 个，其中 47 个在声明之外被引用 | 另有跟随局部常量；“引用”包括初始化和接收解码，不等于 47 个完整功能 |
| Python 原生 `CMD_*` 声明 | 53 个 | 常量声明不代表已接入客户端 |
| 内置 EP SDK `Proto*` 类 | 64 个 | 参考库库存，不是当前原生 App 会话能力 |

不计算“Hanppie 已覆盖官方百分之多少”：一个 key 可同时有读、写、推送和任务状态，一个玩法又可能依赖几十个 key；用两边的数量相除会误导。

## 已有实现不能忽略的部分

| 用户任务 | 当前已有 | 与官方的边界 |
| --- | --- | --- |
| 日常连接 | AP/路由器 IP 会话、自动发现/重连、扫码配网、连接诊断 | 不等于新机激活、AP 信息维护、无线参数管理 |
| 基础驾驶 | 键盘、触摸/鼠标拖动虚拟摇杆、五档限速、镜头坐标移动、云台角度、反馈回中、失焦/租约停止 | 没有完整官方输入设备与机内参数模型 |
| 发射 | 红外、水弹单发与按住重复、发射灯与 UI 反馈 | 缺少官方客户端水弹安全锁及完整裁判/过热反馈 |
| 视频与声音 | 720p/1080p 视频、机载收音、本机拍照/录制、短片对讲、音量设置 | 主机媒体与 SD 卡相机任务分属不同路径 |
| 机载 Python | 编辑、预置脚本、DSP/FTP 上传、运行/停止/完成与异常、音频资源 | 单槽运行，不是官方技能安装与脱机程序发布体系 |
| 文件 | 通用 FTP 目录和文件管理 | 不等于官方 SD 媒体索引、缩略图、相册与格式化 |
| 智能创作 | 模型配置、AI 工具、审批、会话、通用 skill 文档 | Hanppie 自身能力，不能抵扣机器人维护/协议缺口 |
| 对象跟随 | 选点、锁定、确认启动、状态框、取消与手动接管 | 离线实现；距离输入、原生状态细节及实机结果尚未对齐 |

## 按真实使用路径对照

下表的 H/O 证据编号见文末索引。API 存在但需实体扩展硬件的项目，不推定所有机器人都具备。

### 连接、首用、设备状态

| 项目 | 官方证据 | Hanppie 当前状态 / 缺口 |
| --- | --- | --- |
| 首次激活、DJI 账户与设备激活状态 | O10、O14；`DJIRobomasterSystemIsActivated` | 缺少激活查询/激活流程。不能承诺从未激活新机仅用 Hanppie 完成首用；与已激活设备免官方 App 日常使用分开 |
| AP 直连 / 路由器扫码 | O1、O7 | 已有原生发现和扫码配网；属于已覆盖主路径 H1/H6 |
| AP 名称、密码读取和修改、无线重启 | O7；WiFi SSID/Password/Reboot key | 路由器凭据用于生成 QR；没有 AP 本身信息查询、修改、重启闭环，不能把两类密码配置混为一谈 |
| 信道列表、自动/手动信道、SNR 与干扰 | O7；WiFi AvailableChannel/ChannelSelection/SNR/Interference | 只有质量值显示及基本模式查询，缺少频谱/干扰展示与信道管理 |
| SDR 频段、带宽、频点、双频能力 | RobotService SDR 7 个 key；O7 条件界面 | 当前没有对应管理；是否对目标硬件公开可用需另验，不能按存在 key 宣称通用能力 |
| 国家代码、客户端 5G 能力 | O11 | H5 仍发送 `4a50...4a50...` 固定 JP；官方监听国家代码变化并考虑终端能力。缺少动态来源、能力协商和状态核对 |
| 断线自动后退 | O7、`DJIRobomasterSystemReturnEnabled` | 当前采用停止策略，没有该选项。它与当前产品的“不自动恢复运动”原则存在取舍，不能为对齐直接开启 |
| 完整模块在线与故障健康 | O2、O6；WorkingDevices/Exceptions/TaskStatus | 已解析模块清单与 detail；没有官方异常码、故障层级、任务失败、离线模块的完整解释与恢复流程 |
| 机器人/各子模块固件版本、Loader、序列信息 | O6、RobotService 多个 FirmwareVersion key | 只有通用版本查询报文与原始日志；没有完整解析/展示。序列号不是必须公开到日常 UI 的信息 |
| 电池电压、电流、温度与关机/重启 | Battery 7 个 key；原生 Battery abstraction | UI 仅电量百分比，缺少温度等健康信息和受控电源管理 |
| 低功耗开关与进入时限 | O6；LowPowerConsumption 等 key | Lab 上传前退出低功耗不等于提供待机策略、时限或状态回读 |
| 总里程、总驾驶时间 | System TotalMileage/TotalDrivingTime | 无对应统计与持久状态 |

### 操控、发射与视觉

| 项目 | 官方证据 | Hanppie 当前状态 / 缺口 |
| --- | --- | --- |
| 原生前/后/横向速度、加速与制动参数 | O4；MainController ConfigParam | 当前为主机档位限速，不读取/写入机内独立参数与设备允许范围 |
| 灵敏度、预设、自定义、分轴与多输入配置 | O4、O12 | 当前统一云台角速度、五档平移/旋转与触摸死区；没有官方四套输入配置和独立轴增益 |
| 物理官方 GamePad、鼠标/键盘挂载状态、C1/C2/Fn | GamePad 24 个 key，Module.GamePadStatus | 没有手柄协议、激活、状态或按键绑定；虚拟摇杆并非硬件手柄支持 |
| PC 鼠标控制 / 手机陀螺仪 / 压力触控 / 振动 | O4、终端传感器 key | 键盘与拖动虚拟杆已有；没有官方鼠标云台输入模式、陀螺仪输入与灵敏度、3D Touch/振动。旧设备专属输入不一定值得补 |
| 底盘控制模式、原生云台跟随、位置动作 | O1；ChassisFollowMode/ChassisPosition | 有固定遥控 work mode 与软件镜头坐标变换；没有可选原生模式与位置任务。现有“云台到限位时底盘辅助转动”是另一项软件行为 |
| 四轮独立轮速 | O1；WheelSpeed | KMP 只用四轮全零制动；Python `Chassis.drive_wheels` 与 Lab 已有入口。属于客户端未接入，不能说整个项目完全没有 |
| 云台原生回中、增量角度、绝对角度任务、工作模式 | O1；Gimbal key | 软件反馈回中与速度控制已有；原生任务/模式、任务完成状态及持久配置未接入。Lab/SDK 有角度动作入口 |
| 准星种类、落点偏移校准、归零与回写 | O4、O6、`ReticleSettingView` | 目前固定准星；Lab 初始化查询 `3f:57` 不构成校准流程 |
| 水弹安全模式 | O9 | 官方启用该模式且为水弹时，以 `_GimbalPitch > 10f` 锁住发射并提示；H10/H1 没有等价俯仰条件。阈值使用的坐标与正方向需验证，不能直接套用现有遥测字段 |
| 次数、频率、枪口温度/过热、冷却、剩余弹量 | O1/O3、GunCoolDown/Bullets/BarrelCoolDown | 当前是单发/重复触发与本地反馈；无这些原生状态、配置及对应受限/恢复交互 |
| 人体目标跟随 | O8 | 新状态机和选点已有，仍为离线实现；不意味着支持通用物体跟踪或已验证运动效果 |
| 跟随中的距离调节 | O8、O3 `SendVisionTrackingDistance` | **缺失**。官方只保留左摇杆前后轴发距离调整；当前所有非零方向输入取消跟随 |
| 跟随候选框、原生 Info、状态细节与失目标恢复 | O8；TrackingRunningStatus/Rects/Info | 当前只解码框几何和少量状态/异常；忽略原生 Info，没有官方全部状态细节。候选框合并/重叠消除、失目标时返回搜索等体验不同；当前选择保守结束本轮，不自动重启 |
| 跟随自动锁定目标 | VisionTrackingAutoLockTarget key | 未接入；本轮仅确认 key，未确认该固定版本公开 UI 可达，不列为必补按钮 |
| 机器人/标签辅助瞄准、激光点反馈 | O2、Vision Aimbot/LaserPosition/ARTag | 当前没有 FPV 辅助瞄准和结果叠加；普通十字准星不等于辅助识别瞄准 |
| 标签、人体、头肩、姿态、巡线、颜色/识别资源 | Vision 19 个 key、O15 | 部分机载 Lab API 与 SDK 源码存在；App 主链路只接跟随子集，缺少通用检测配置、结果与优先级管理 |
| 拍手与姿态触发等交互 | O15、Lab API | 能通过机载脚本表达；没有官方相应交互/教学流程，当前也未补本轮实机证据 |

### 相机、音频、媒体与灯效

| 项目 | 官方证据 | Hanppie 当前状态 / 缺口 |
| --- | --- | --- |
| SD 卡原生拍照与开始/停止录像 | Camera StartShoot/Record key，O3 | App 按钮保存本机帧/码流。Python `Robot.capture` 和 Lab 有机内入口，但 KMP 没有 SD 相机任务、进行中/结果状态闭环 |
| SD 插入、容量、剩余张数/时长、满卡/故障、格式化 | Camera SD 系列，O6 | 缺少卡状态 UI 与格式化流程；不能从本机可录制推断机器人 SD 正常 |
| 官方媒体库、机内下载、缩略图、播放、分享 | O13 | 有通用 FTP 文件管理和本机导出；没有官方媒体索引/相册。当前 FTP 是否可访问特定固件的 SD 内容，不能单凭通用文件接口保证 |
| 原生视频格式与拍摄格式 | Camera VideoFormat/PhotoSize/Mode | 有视频流分辨率选择；没有完整格式回读、拍照尺寸、模式与 SD 录像状态协商 |
| 1–4 倍数码变焦 | O1/O3；CameraDigitalZoomFactor | 初始化已发送默认缩放，Lab 可 zoom；App 无倍数入口/回读。首页三维镜头缩放只改变展示，与机器人镜头无关 |
| 抗闪烁、相机日期、时钟同步 | Camera AntiFlicker/Date/IsTimeSynced | 无完整设置和状态；Lab 有其他曝光 API 不等于抗闪烁已对齐 |
| 码率、主动关键帧请求、相机切换与副相机能力 | Camera VideoTransRate/RequestIFrame/Switch 系列 | 当前无上述原生管理；解码器等待 I 帧不等于主动请求。多相机 key 不代表当前硬件具有副相机 |
| 机器人扬声器音量回读与语音语言 | O6；SpeakerVolumn/SpeakerLanguage | 已持久保存并下发音量，未回读；缺少机器人语音语言设置；App 中英文界面不能替代机器人语音语言 |
| 原生回声/啸叫抑制 | CameraAntiLarsenAlgorithmEnable | 当前无对应配置与状态；不能由“对讲可播”推定音频策略一致 |
| 对讲与自定义声音 | O3、O15；H1/H7 | 已有短录音编码上传播放、音频库和 Lab 音频；未确认与官方连续对讲的时延/全双工语义一致，本轮不把短片上传说成全双工实时通话 |
| 头灯亮度、分模块灯效、队伍/机体色 | O5，LEDLightEffect/Headlight/GameColor key | 有四种遥控状态色及 Python 分区 LED；无完整用户灯效、头灯亮度、持久队伍色与原生灯效任务 |
| FPV 血条、机械臂 HUD、画面裁剪与角度显示开关 | O5 | 当前固定遥测与角度指示器，视频 Fit；无血条/机械臂 HUD 或同样的开关集合 |
| 三维质量、窗口/画面偏好 | O5 | 当前有窗口比例和位置管理与固定场景配置；没有官方质量档位、分辨率列表，属于不同产品策略与一部分缺口 |

### 编程、玩法、扩展与服务

| 项目 | 官方证据 | Hanppie 当前状态 / 缺口 |
| --- | --- | --- |
| 图形化 Scratch / Blockly | O15；安装包 visual_programming | 当前 Python 编辑/AI 创作，没有积木编辑、注释、积木↔生成代码工作流 |
| Road to Mastery、RoboAcademy、课程进度、官方课程素材 | O14/O15、Module.Tutorial/LAB | 有预置脚本与 skill 参考，但不是课程、练习、进度与内容分发体系 |
| 自主程序、机器人按钮脱机启动 | O15；InstallScratchSkill/SkillID | 只有当前运行槽位上传；未实现指定自主程序、安装/卸载、按钮绑定和脱机启动管理。不能以脚本能上传认定已覆盖 |
| 自定义技能与 FPV 一键触发 | O1/O15；SinglePlayerCustomSkill/MultiPlayerSkill | 没有技能安装、技能槽、运行/冷却状态和 FPV 触发流程 |
| 机内文件/资源 MD5 查询与确认 | InquiryDspMd5/ResourceMd5 系列 | 当前本机计算 DSP/音频摘要、维护当前会话的音频槽缓存；没有官方远端资源摘要查询协议。不能称为跨会话机内缓存验证 |
| 脚本自定义 UI 与事件 | CustomUIAttribute/FunctionEvent | Lab 启停有固定回复，但没有自定义控件解析、展示或按钮/滑条事件回传 |
| `mobile_ctrl` 实时终端传感器 | O1、O15；MobileAcceler/Gyro 系列 | **端到端缺失**。机内 API/文档存在，但没有主机采样与上行发送；不能保证脚本读到 Hanppie 终端姿态/加速度/陀螺仪 |
| 单机挑战：靶场、靶标竞速等 | Module.Challenger、O14 | 没有挑战规则、倒计时、靶标计分、难度/关卡与结果页 |
| 多人对战：房间、角色、队伍、裁判与技能 | Module.Battle，System Game 系列 | 无完整链路；固定 `3f:09` 配置和 `3f:0a` 片段不等于已有对战 |
| 血量、伤害、装甲事件、增益、复活、胜负 | O2/ConfigurationManager | 当前未形成裁判领域状态；缺少战斗 HUD、规则配置、同步和结算 |
| 固件查询/下载/校验/分发/升级/恢复 | Module.FirmwareUpgrade、O6 | 没有升级状态机。仓库归档原厂固件、ADB/恢复资料与用户可执行升级功能分开 |
| 电机/装甲编址、云台/底盘校准 | O6，Module.MotorAddress/ArmorAddress/GimbalAndChassisCalibrate | 没有维护流程、进度、取消和失败恢复。驱动速度包或软件回中不能替代硬件校准 |
| 机械臂安装/卸载/校准、爪/臂直接操控 | O6，ViewSettingModules、Arm/Claw key | 当前有能力清单与机载 Lab/SDK 参考；缺少原生操控、安装与校准 UI |
| 舵机/ToF/适配板编址、指示灯、在线列表与数据订阅 | 各扩展模块 key、O6 | KMP 没有完整设备管理；Lab/SDK 可编程入口依硬件而定，尚未做本轮设备验证 |
| DJI 账户、项目云同步/共享、公告、客服/日志上传 | Module.Account/LAB/MessageCenter/FeedBack/Support | 当前本地脚本/AI 会话/诊断不能替代这些服务；本轮没有验证官方后端现在是否仍可用 |
| 多语言与官方引导/维护知识 | O12/O14 | 当前简中/英文与本地参考；无官方更多语言、完整新手引导、官方支持与内容中心 |
| GPS/设备信息/产品改进统计等偏好 | O12 | 没采用官方统计服务；这是服务选择，不能简单当作应补齐的控制缺陷 |

## 已定位的原生指令差异

下表的 cmdset:id 来自现有 Hanppie 源码或固定版本原生构造函数反汇编，**不是由桥接 key ID 拆出来的**。它们只构成静态协议证据，本轮没有发送这些新指令。没有列出完整 payload 的项目仍需补请求/应答、前置模式与设备边界。

| 指令族 / key 语义 | 原生标识或子操作 | 当前主链路与缺口 | 官方原生定位 |
| --- | --- | --- | --- |
| 原生拍照 | `02:01` | Python capture 已有；KMP 拍照为本机帧存储 | Camera `ActionStartTakePhoto` `0x731690` |
| 相机录像启停 | `02:02`，单字节开始/停止 | KMP 未接入 SD 录像任务；本机录像是另一条路径 | `ActionStartRecordVideo` `0x731bd0` / Stop `0x732110` |
| 数码变焦 | `02:34` | RemoteCommands 只设置默认值，缺 UI/回读 | `SetDigitalZoomFactor` `0x72d630` |
| 抗闪烁 | `02:46` | 未接 | `SetAntiFlicker` `0x72edd0` |
| 格式化 SD | `02:72` | 未接卡状态/格式化流程 | `ActionFormatSDCard` `0x7328c0` |
| 主动 I 帧请求 | `01:01` | 未接；仍需核对传输节点与完整前置状态 | `ActionRequestIFrame` `0x732a70` |
| 原生云台回中 | `04:4c` | 当前是软件反馈回中 | `ActionResetPosition` `0x75c650` |
| 云台工作模式 | `04:44` | 未提供模式配置/回读 | `SetMode` `0x75ba90` |
| 云台校准路径 | `04:68`、`04:08` | 均未形成校准任务状态机；两条路径不可混为一条 | `ActionCalibration` `0x75cb90` / `ActionCalibrateGimbal` `0x75dc90` |
| 主控参数读/写 | `03:f8` / `03:f9` | 未建参数名、允许范围、回读、写入状态；IMU 校准也复用参数写入路径，非独立 opcode | MC `GetConfigParamValue` `0x7866c0` / Set `0x787000`；IMU Start `0x785150`、Stop `0x7857f0` |
| 电机编码/编址 | `3f:3e` / `3f:66` | 后者已有固定初始化片段；无完整编址流程 | MC `SetEscEncodeFlag` `0x788960` / Addressing `0x78a260`；System `0x6abe40` |
| 断线自动后退 | `3f:13` | 无配置；当前停止策略是明确产品选择 | System `SetReturnEnabled` `0x69e7a0` |
| 机器人语音语言 | `3f:16` | 无设置/回读 | `SetSpeakerLanguage` `0x6a3c90` |
| 扬声器音量 | `3f:1b` / `3f:1c` | 前者已发送；后者目前仅常量，未被主代码引用 | H1/H4，System SetVolume/GetVolume |
| 机内速度等级 | `3f:3f` | 本地五档不等同该设置 | `SetChassisSpeedLevel` `0x6a4aa0` |
| 低功耗配置 | `3f:4a`；现有退出 `3f:4c` | 有 Lab 前退出，无配置/时限/回读 | `SetLowPowerConsumption` `0x6a6180`，H6 |
| 发射安全策略 | `3f:59` | 固定 `02`，退出 `00`；native enum 分别对应 ShootNoRestriction/ShootForbidden。官方正常单人模式也下发 02，再由 S1_Fire 做本地安全锁；不能据此断言原生 02 本身是 Hanppie 独有缺陷 | `SetSafeMode` `0x69f5b0`；O9、SystemSinglePlayer |
| 比赛开始/结束 | `3f:0c` | 未接比赛生命周期 | System Start `0x6a8bf0` / Stop `0x6a9130` |
| 角色死亡/复活 | `3f:0a` | 有固定进入遥控片段，无玩家裁判状态机 | Kill `0x6a7e40` / Revive `0x6a8380` |
| 多人技能 | `3f:0d` | 未接技能配置、启动及反馈 | `ActionLaunchMultiPlayerSkill` `0x6a75b0` |
| 灯效任务 | `3f:e7` | 当前基本装甲灯 `3f:33` 不能替代全部灯效 | `ActionPlayLEDLightEffect` `0x6a6fa0` |
| 视觉检测/选点/确认与取消 | `0a:a3` / `0a:a8` / `0a:a7` | 新跟随已接子集；检测位、资源与回读未完整覆盖 | 见[跟随原生证据](./object-tracking-validation-2026-10-22.md) |
| 跟随距离输入 | `0a:a7`，`40 00 02` + 4 字节数值 | **缺子操作 2**。官方 RealController 将前后轴值乘 100 后传给原生；不应误标为绝对米数滑条 | `SetTrackingDistance` `0x7c5b90`；写入点 `0x7c5c03..0x7c5c10` |
| 巡线/标签颜色 | `0a:ab`，不同子选择 | App 无；Lab 有部分颜色 API，不能替代原生配置 | `SetVisionLineColor` `0x7c6320` / Marker `0x7c6aa0` |
| 电池关机/重启 | `00:0b`，不同参数 | 未形成用户电源管理 | Battery Shutdown `0x713570` / Reboot `0x713060` |

网络信道、激活、固件升级、扩展设备的更多 key 已在附表保留。没有完成逐条原生 payload 解码的，维持“桥接/调用证据”级别，不伪造 cmdid 或任务参数。

### 当前协议层还缺的横向能力

1. **配置读取 → 允许范围 → 写入 → 应答 → 再回读的闭环。** 当前很多固定初始化只发送，不具备可复用的配置状态模型；新跟随的序号匹配和状态机属于局部完成，不能说所有请求都已有该机制。
2. **原生任务管理。** 硬件校准、编址、升级、原生位置/角度动作需要独立的进度、完成、异常、取消，而非“send 成功”即完成。
3. **完整异常与健康订阅。** 当前姿态/电量/轮速/基本模块清单不能替代全部故障、低电压/温度、过热和外设阻塞等状态。
4. **原生参数的领域含义。** `03:f8/f9` 只是参数通道，`3f:09` 只是裁判配置容器，`0a:a7` 有多种子操作；只补一个 ID 常量不足以实现其全部语义。
5. **平台能力上行。** 官方 GamePad 和手机传感器需要真实平台采样，shared 接口、机器人订阅请求和上行协议缺一不可；机内 `mobile_ctrl` 类存在不会自动产生数据。

## Lab、Python、SDK 的三层边界

| 能力 | KMP 应用直接路径 | Python `hanppie` | Lab 机载 / `robomaster` 参考 |
| --- | --- | --- | --- |
| 原生相机拍照 | 本机保存帧 | `Robot.capture` 已有 | `media_ctrl.take_photos`；SDK 相机 API |
| 四轮独立控制 | 仅全零制动 | `Chassis.drive_wheels` 已有 | Lab/SDK 有入口 |
| 指定角度云台动作 | 速度控制 + 软件回中 | 当前主要为速度控制 | Lab/SDK 有角度动作 |
| 视觉识别/标签自瞄/巡线 | 只有新跟随子集 | 无完整对应高层封装 | `vision_ctrl` 与 SDK 有接口，硬件/固件和资源仍需验证 |
| 机械臂/爪/舵机/ToF/适配板 | 清单为主，缺操控与维护 | 无完整对应高层封装 | Lab/SDK 有接口，扩展实机未在本轮验证 |
| 编程音效/LED | 状态色、对讲、自定义音频 | 细分 LED/音效已有 | Lab/SDK 有更多能力 |
| 终端陀螺仪数据 | 缺采样及上行 | 无完整主机转发 | 机内读取接口存在，但链路仍缺 |

`src/robomaster` 是 EP SDK proxy 路径参考，不能直接视为当前 App 会话可用能力。仓库当前对该边界的说明见 [architecture.md 1.3.10](./architecture.md#1310-为什么官方-sdk-不是当前控制后端)。向原始 `send/request` 填任意 bytes 的能力也不能计作全部指令已实现。

## 文档与产品表述需要纠正的边界

- [architecture.md 1.3.8](./architecture.md#138-python-直控与工具链) 的“完全复现原生 App 协议栈”容易被读成官方所有功能已覆盖。当前证据支持的是部分原生会话、控制与 Lab 通道，不能由协议封装完整推导设备功能完整。
- [sensors-and-vision.md 的 mobile_ctrl 段](../shared/src/commonMain/composeResources/files/skills/lab-python/references/sensors-and-vision.md) 把“App 或 Hanppie 建立会话”写成可读终端传感器的充分条件，主机代码没有对应采样/发送证据。应区分机内 API 定义与 Hanppie 端到端支持。
- README 的“不需要官方 App”适合已就绪机器人的日常使用说明；对激活、编址、校准、固件维护不能延伸为全生命周期独立替代。
- 对象跟随当前应继续标注“已离线实现、未实机验收、未对齐跟随距离输入”，不能只用“测试通过”表示官方体验对齐。

以上是本轮调查结论与待修订项，没有借调查修改既有产品行为、文案或安全参数。

## 建议顺序

| 优先级 | 范围 | 完成标准 |
| --- | --- | --- |
| 先处理 | 水弹安全条件、国家代码固定值、原生状态与文档边界 | 明确源字段、模式、用户意图和实机证据；不以新增 UI 代替行为闭环 |
| 日常体验补齐 | 跟随距离/实机、变焦、SD 卡状态与机内拍录、音量回读、健康故障、准星与速度配置 | 按一次真实任务串起界面、协议、状态、取消与恢复 |
| 独立使用闭环 | 激活依赖说明、版本管理、固件升级、编址和校准 | 若目标是彻底免官方 App，这是必需能力；需硬件、抓包与失败恢复验证 |
| 按产品定位决定 | Scratch/课程、挑战/对战、云项目、更多语言、官方统计和旧终端输入方式 | 逐项判断用户价值；不为追求 key 数量引入无收益模块 |

这不是一份“全部立即开发”的清单。编址、校准、升级、断线自主运动需要先明确能力、状态归属和证据，再按完整流程开发与验收。

## 证据索引与复核

官方反编译根目录为本机忽略目录 `.hanppie/app-analysis/2026-09-08/decompiled/`。下表以 `RobotService/` 开头的路径相对该根目录，其余 C# 路径相对其中的 `Assembly-CSharp/`；key 的枚举定义见附表，原始厂商代码不加入仓库。

| 编号 | 官方证据 |
| --- | --- |
| O1 | `Base.CONTROL/IControl.cs`，52–261：官方控制边界与命令入口 |
| O2 | `RobotService/DJI/DJIKeys.cs`，343 项 KeyProperty 定义；详见逐项附表 |
| O3 | `Base.CONTROL/RealController.cs`，各 key 转换和执行；2365 附近为跟随距离缩放 |
| O4 | `Module.Setting.View/ViewSettingControl.cs`，310–669、729–973：发射/准星/机内速度/分轴输入设置 |
| O5 | `Module.Setting.View/ViewSettingDisplay.cs`，37–105、219–376：画面/灯光/原生格式/质量与平台条件 |
| O6 | `Module.Setting.View/ViewSettingSystem.cs`，321–705；`ViewSettingModules.cs`：维护、存储、电源与扩展 |
| O7 | `Module.Setting.View/ViewSettingConnect.cs`，260–303；`WifiInfoView.cs`：AP/信道/自动后退 |
| O8 | `Module.SinglePlayer.View/ViewVisionFollow.cs`，StateFollowTarget 及 OnJoystick；`Module.SinglePlayer.System/VisionFollow.cs`、`SystemSinglePlayer.cs`，242 附近距离事件 |
| O9 | `Element.S1/S1_Fire.cs`，443–453：水弹俯仰角安全锁；`Module.SinglePlayer.System/SystemSinglePlayer.cs`，149/189/332：原生安全模式切换 |
| O10 | `Module.DeviceActivation*`、`Module.FirmwareUpgrade*`、`Module.MotorAddress*`、`Module.ArmorAddress*`、`Module.GimbalAndChassisCalibrate*` |
| O11 | `Module.SystemCountryCode.System/SystemCountryCode.cs`，44–59：国家码与客户端 5G 能力 |
| O12 | `COMMON/SettingManager.cs`，18–120；`COMMON/ConfigurationManager.cs`，默认裁判/技能/Lab 表 |
| O13 | `Module.MediaLibrary*`、`RobotService/DJI.Robot.Advance/DJILocalAlbumController.cs`、`RobotService/DJI.Robot.Advance/DJIVideoController.cs` |
| O14 | `Module.Account*`、`Module.LoginAndRegister*`、`Module.Tutorial*`、`Module.Challenger*`、`Module.Battle*` |
| O15 | `Module.LAB*` 与安装包 `StreamingAssets/visual_programming/`；公开 API 语义交叉核对 DJI 编程指南 |

| 编号 | Hanppie 证据 |
| --- | --- |
| H1 | [AppSession.kt](../packages/robot-core/src/commonMain/kotlin/cn/elonzh/hanppie/robot/session/AppSession.kt)：原生会话、遥控、发射、媒体、对讲与跟随连接 |
| H2 | [RemoteCommands.kt](../packages/robot-core/src/commonMain/kotlin/cn/elonzh/hanppie/robot/remote/RemoteCommands.kt)：固定初始化/退出向量；[RemoteControl.kt](../packages/robot-core/src/commonMain/kotlin/cn/elonzh/hanppie/robot/remote/RemoteControl.kt)：限速/辅助跟随/LED |
| H3 | [Telemetry.kt](../packages/robot-core/src/commonMain/kotlin/cn/elonzh/hanppie/robot/telemetry/Telemetry.kt)、[RobotProduct.kt](../packages/robot-core/src/commonMain/kotlin/cn/elonzh/hanppie/robot/product/RobotProduct.kt)：已解释的遥测与模块清单 |
| H4 | [Protocol.kt](../packages/robot-core/src/commonMain/kotlin/cn/elonzh/hanppie/robot/protocol/Protocol.kt)：常量；不是完整功能库存 |
| H5 | [ConnectionSetup.kt](../packages/robot-core/src/commonMain/kotlin/cn/elonzh/hanppie/robot/protocol/ConnectionSetup.kt)：固定 JP 与订阅初始化 |
| H6 | [RouterProvisioning.kt](../packages/robot-core/src/commonMain/kotlin/cn/elonzh/hanppie/robot/protocol/RouterProvisioning.kt)、[LabController.kt](../packages/robot-core/src/commonMain/kotlin/cn/elonzh/hanppie/robot/lab/LabController.kt)：扫码、DSP、音频槽与低功耗退出 |
| H7 | [SettingsStore.kt](../shared/src/commonMain/kotlin/cn/elonzh/hanppie/ui/settings/SettingsStore.kt)、[MediaSettings.kt](../shared/src/commonMain/kotlin/cn/elonzh/hanppie/ui/settings/MediaSettings.kt)：实际持久偏好 |
| H8 | [RemotePage.kt](../shared/src/commonMain/kotlin/cn/elonzh/hanppie/ui/robot/remote/RemotePage.kt)、[ControlBindings.kt](../shared/src/commonMain/kotlin/cn/elonzh/hanppie/ui/settings/ControlBindings.kt)：遥控界面与输入 |
| H9 | [RobotVideo.desktop.kt](../shared/src/jvmMain/kotlin/cn/elonzh/hanppie/ui/robot/remote/RobotVideo.desktop.kt)、各平台 `RobotVideo` 与录制实现：本机保存路径 |
| H10 | [ConsoleModel.kt](../shared/src/commonMain/kotlin/cn/elonzh/hanppie/ui/app/ConsoleModel.kt)：发射条件与机器人状态消费 |
| H11 | [ObjectTracking.kt](../packages/robot-core/src/commonMain/kotlin/cn/elonzh/hanppie/robot/tracking/ObjectTracking.kt)、[TrackingProtocol.kt](../packages/robot-core/src/commonMain/kotlin/cn/elonzh/hanppie/robot/tracking/TrackingProtocol.kt)：跟随协议子集与未解码的 Info |
| H12 | [robot.py](../src/hanppie/robot.py)，640/657/868/933/956 附近：速度、独立轮速、灯、音效、原生拍照 |
| H13 | [RobotFileSystem.kt](../packages/robot-core/src/commonMain/kotlin/cn/elonzh/hanppie/robot/files/RobotFileSystem.kt)、[RobotFilesController.kt](../shared/src/commonMain/kotlin/cn/elonzh/hanppie/ui/robot/files/RobotFilesController.kt)：通用 FTP 路径 |
| H14 | [Lab Python 参考](../shared/src/commonMain/composeResources/files/skills/lab-python/references/sensors-and-vision.md)、[EP 扩展参考](../shared/src/commonMain/composeResources/files/skills/lab-python/references/ep-extensions.md)、[SDK 来源边界](../src/robomaster/UPSTREAM.md) |

本机详细清单和 36 个原生函数反汇编片段保留在 `.hanppie/validation/2026-10-22-official-gap/`，包含 key 具名引用、分类、原生方法和请求 ID 候选。自动提取候选会包含初始化零值等假阳性，主报告只采用已人工核对的指令；没有把候选数量当作已解码指令数量。
