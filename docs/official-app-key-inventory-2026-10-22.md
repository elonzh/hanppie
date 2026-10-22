# 官方 App 343 个桥接 key 对照清单 · 2026-10-22

这是[差距调查报告](./official-app-gap-analysis-2026-10-22.md)的逐项证据附表，固定对应本地 RoboMaster macOS 1.1.5/build 239。它是服务层 key 清单，不是 343 条 DUSS 指令，也不是 343 项用户功能。

## 读法与边界

- `id_hex` 是桥接层属性标识，不能拆成 cmdset/cmdid。R/W/Action 与请求、推送和任务并非一一对应。
- “接入”只说明当前主干有相应语义路径，不代表复刻官方 API、全部访问方向或新增实机验证。“部分”“不同路径”“固定初始化”均不能算完全覆盖。
- “未接入”只针对 KMP 当前应用主链路。Lab 机载 API、Python 直控和 EP SDK 参考必须按主报告的分层边界解释，不能据此宣称机器人没有这种能力。
- “官方上层引用”是在反编译 Assembly-CSharp 中，排除 Base.CONTROL 后出现的具名 key 引用；它可能来自内部工具或条件分支，不自动证明当前平台公开界面可达。235 项找到引用，108 项未找到此类引用。
- 部分 key 只在 SDK 存在，甚至指向占位实现；不能把未接入项目全部当成应补齐需求。

证据根目录为本机忽略目录 `.hanppie/app-analysis/2026-09-08/decompiled/`。`Keys:N` 指 `RobotService/DJI/DJIKeys.cs` 第 N 行，其余短路径相对 `Assembly-CSharp/`。原始反编译文件与厂商二进制不纳入版本控制。

## 机械核对范围

| 分组 | key 数 |
| --- | ---: |
| 产品 | 2 |
| 相机 | 31 |
| 主控与底盘 | 46 |
| 遥控器连接 | 1 |
| 云台 | 19 |
| 系统、Lab 与对战 | 93 |
| 视觉 | 19 |
| 空中链路通用项 | 4 |
| Wi-Fi | 13 |
| 装甲 | 11 |
| 水弹发射器 | 5 |
| 感知 | 3 |
| 电调 | 8 |
| 电池 | 7 |
| 官方手柄 | 24 |
| 机械爪 | 6 |
| SDR | 7 |
| 机械臂 | 10 |
| 红外发射器 | 4 |
| ToF | 9 |
| 舵机 | 10 |
| 传感器转接板 | 11 |

下列分类只用于定位实现方式，不计算官方 App 的功能覆盖率：不同路径 20、固定初始化 12、接入 7、接入-离线 5、未接入 275、部分 24。

## 00 · 产品（2）

| key | 桥接 ID / 访问 | Hanppie 主链路 | 当前差异 | 官方代码定位 |
| --- | --- | --- | --- | --- |
| `DJIProductTest` | `0x1` · Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:7；`Module.InnerTools.View/ViewCamera.cs:372` |
| `DJIProductType` | `0x2` · Read | 接入 | RobotProductProtocol.model：原生产品类型解析 | Keys:9；`DJI/DJIActivationController.cs:37` |

## 01 · 相机（31）

| key | 桥接 ID / 访问 | Hanppie 主链路 | 当前差异 | 官方代码定位 |
| --- | --- | --- | --- | --- |
| `DJICameraConnection` | `0x1000001` · Read | 不同路径 | AppSession 连通状态 / workingDevices；没有这些逐模块连接 key 的独立状态流 | Keys:11；`COMMON/CommonMediaPanel.cs:131` |
| `DJICameraFirmwareVersion` | `0x1000002` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:13；`Module.InnerTools.View/ViewPandect.cs:25` |
| `DJICameraStartShootPhoto` | `0x1000003` · Action | 不同路径 | 桌面/移动 UI 保存解码帧；Python Robot.capture 原生 02:01；Lab media_ctrl.take_photos | Keys:15；`Module.InnerTools.View/ViewCamera.cs:293` |
| `DJICameraIsShootingPhoto` | `0x1000004` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:17；`COMMON/CommonMediaPanel.cs:136` |
| `DJICameraPhotoSize` | `0x1000005` · Read/Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:19；`Module.InnerTools.View/ViewCamera.cs:129` |
| `DJICameraStartRecordVideo` | `0x1000006` · Action | 不同路径 | UI 是主机录制；Lab media_ctrl 有机内录像；无 KMP SD 相机录像控制 | Keys:21；`Module.InnerTools.View/ViewCamera.cs:287` |
| `DJICameraStopRecordVideo` | `0x1000007` · Action | 不同路径 | UI 是主机录制；Lab media_ctrl 有机内录像；无 KMP SD 相机录像控制 | Keys:23；`Module.InnerTools.View/ViewCamera.cs:282` |
| `DJICameraIsRecording` | `0x1000008` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:25；`Module.Setting.View/ViewSettingSystem.cs:65` |
| `DJICameraCurrentRecordingTimeInSeconds` | `0x1000009` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:27；`Module.InnerTools.View/ViewCamera.cs:95` |
| `DJICameraVideoFormat` | `0x100000a` · Read/Write | 部分 | AppSession.media 设置 720p/1080p；未实现官方格式配置回读与完整相机状态 | Keys:29；`Module.Setting.View/ViewSettingDisplay.cs:37` |
| `DJICameraMode` | `0x100000b` · Read/Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:31；`Module.InnerTools.View/ViewCamera.cs:61` |
| `DJICameraDigitalZoomFactor` | `0x100000c` · Read/Write | 固定初始化 | RemoteCommands 发送 02:34 默认载荷；无交互缩放、倍率状态；Lab 可 zoom | Keys:33；`Module.Setting.View/ReticleSettingView.cs:48` |
| `DJICameraAntiFlicker` | `0x100000d` · Read/Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:35；`Module.Setting.View/ViewSettingDisplay.cs:38` |
| `DJICameraSwitch` | `0x100000e` · Action | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:37；`Module.InnerTools.View/ViewCamera.cs:239` |
| `DJICameraCurrentCameraIndex` | `0x100000f` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:39；`Module.InnerTools.View/ViewCamera.cs:184` |
| `DJICameraHasMainCamera` | `0x1000010` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:41；未见非 Base.CONTROL 的显式引用 |
| `DJICameraHasSecondaryCamera` | `0x1000011` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:43；未见非 Base.CONTROL 的显式引用 |
| `DJICameraFormatSDCard` | `0x1000012` · Action | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:45；未见非 Base.CONTROL 的显式引用 |
| `DJICameraSDCardIsFormatting` | `0x1000013` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:47；`Module.Setting.View/ViewSettingSystem.cs:63` |
| `DJICameraSDCardIsFull` | `0x1000014` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:49；未见非 Base.CONTROL 的显式引用 |
| `DJICameraSDCardHasError` | `0x1000015` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:51；`COMMON/CommonMediaPanel.cs:133` |
| `DJICameraSDCardIsInserted` | `0x1000016` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:53；`Module.Setting.View/ViewSettingSystem.cs:64` |
| `DJICameraSDCardTotalSpaceInMB` | `0x1000017` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:55；未见非 Base.CONTROL 的显式引用 |
| `DJICameraSDCardRemaingSpaceInMB` | `0x1000018` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:57；`Module.Setting.View/ViewSettingSystem.cs:62` |
| `DJICameraSDCardAvailablePhotoCount` | `0x1000019` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:59；`COMMON/CommonMediaPanel.cs:134` |
| `DJICameraSDCardAvailableRecordingTimeInSeconds` | `0x100001a` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:61；`COMMON/CommonMediaPanel.cs:135` |
| `DJICameraIsTimeSynced` | `0x100001b` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:63；`Module.Setting.System/SystemSetting.cs:64` |
| `DJICameraDate` | `0x100001c` · Read/Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:65；`Module.Setting.System/SystemSetting.cs:134` |
| `DJICameraVideoTransRate` | `0x100001d` · Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:67；`Module.InnerTools.View/ViewCamera.cs:442` |
| `DJICameraRequestIFrame` | `0x100001e` · Action | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:69；未见非 Base.CONTROL 的显式引用 |
| `DJICameraAntiLarsenAlgorithmEnable` | `0x100001f` · Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:71；`Module.InnerTools.View/ViewAudioRecord.cs:72` |

## 02 · 主控与底盘（46）

| key | 桥接 ID / 访问 | Hanppie 主链路 | 当前差异 | 官方代码定位 |
| --- | --- | --- | --- | --- |
| `DJIMainControllerConnection` | `0x2000001` · Read | 不同路径 | AppSession 连通状态 / workingDevices；没有这些逐模块连接 key 的独立状态流 | Keys:73；未见非 Base.CONTROL 的显式引用 |
| `DJIMainControllerFirmwareVersion` | `0x2000002` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:75；`Module.InnerTools.View/ViewPandect.cs:18` |
| `DJIMainControllerLoaderVersion` | `0x2000003` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:77；未见非 Base.CONTROL 的显式引用 |
| `DJIMainControllerVirtualStick` | `0x2000004` · Action | 不同路径 | 遥控授权 + 3f:21/3f:20 与 04:0c；不是完整官方虚拟杆模式 API | Keys:79；未见非 Base.CONTROL 的显式引用 |
| `DJIMainControllerVirtualStickEnabled` | `0x2000005` · Read/Write | 不同路径 | 遥控授权 + 3f:21/3f:20 与 04:0c；不是完整官方虚拟杆模式 API | Keys:81；未见非 Base.CONTROL 的显式引用 |
| `DJIMainControllerChassisSpeedMode` | `0x2000006` · Write | 部分 | 主机速度直控；进入遥控固定速度模式，无完整模式配置与回读 | Keys:83；`Module.InnerTools.View/ViewChassis.cs:225` |
| `DJIMainControllerChassisFollowMode` | `0x2000007` · Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:85；`Module.InnerTools.View/ViewChassis.cs:233` |
| `DJIMainControllerChassisCarControlMode` | `0x2000008` · Write | 固定初始化 | 进入遥控固定 work mode；无用户模式选择或当前模式解码 | Keys:87；`Module.InnerTools.View/ViewChassis.cs:168` |
| `DJIMainControllerRecordState` | `0x2000009` · Read/Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:89；`Module.InnerTools.View/ViewCamera.cs:317` |
| `DJIMainControllerGetRecordSetting` | `0x200000a` · Read/Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:91；`Module.InnerTools.View/ViewCamera.cs:299` |
| `DJIMainControllerSetRecordSetting` | `0x200000b` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:93；`Module.InnerTools.View/ViewCamera.cs:351` |
| `DJIMainControllerPlayRecordAttr` | `0x200000c` · Read/Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:95；`Module.InnerTools.View/ViewCamera.cs:333` |
| `DJIMainControllerGetPlayRecordSetting` | `0x200000d` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:97；`Module.InnerTools.View/ViewCamera.cs:306` |
| `DJIMainControllerSetPlayRecordSetting` | `0x200000e` · Read/Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:99；`Module.InnerTools.View/ViewCamera.cs:341` |
| `DJIMainControllerMaxSpeedForward` | `0x200000f` · Read/Write | 不同路径 | ControlSettings 主机档位限速；不读写官方机内前/后/横独立参数 | Keys:101；`Module.Setting.View/ViewSettingControl.cs:441` |
| `DJIMainControllerMaxSpeedBackward` | `0x2000010` · Read/Write | 不同路径 | ControlSettings 主机档位限速；不读写官方机内前/后/横独立参数 | Keys:103；`Module.Setting.View/ViewSettingControl.cs:442` |
| `DJIMainControllerMaxSpeedLateral` | `0x2000011` · Read/Write | 不同路径 | ControlSettings 主机档位限速；不读写官方机内前/后/横独立参数 | Keys:105；`Module.Setting.View/ViewSettingControl.cs:445` |
| `DJIMainControllerSlopeY` | `0x2000012` · Read/Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:107；`Module.Setting.View/ViewSettingControl.cs:443` |
| `DJIMainControllerSlopeX` | `0x2000013` · Read/Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:109；`Module.Setting.View/ViewSettingControl.cs:446` |
| `DJIMainControllerSlopeBreakY` | `0x2000014` · Read/Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:111；`Module.Setting.View/ViewSettingControl.cs:444` |
| `DJIMainControllerSlopeBreakX` | `0x2000015` · Read/Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:113；`Module.Setting.View/ViewSettingControl.cs:447` |
| `DJIMainControllerMaxSpeedForwardConfig` | `0x2000016` · Read/Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:115；`Module.Setting.View/ViewSettingControl.cs:301` |
| `DJIMainControllerMaxSpeedBackwardConfig` | `0x2000017` · Read/Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:117；`Module.Setting.View/ViewSettingControl.cs:302` |
| `DJIMainControllerMaxSpeedLateralConfig` | `0x2000018` · Read/Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:119；`Module.Setting.View/ViewSettingControl.cs:303` |
| `DJIMainControllerSlopSpeedYConfig` | `0x2000019` · Read/Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:121；`Module.Setting.View/ViewSettingControl.cs:304` |
| `DJIMainControllerSlopSpeedXConfig` | `0x200001a` · Read/Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:123；`Module.Setting.View/ViewSettingControl.cs:305` |
| `DJIMainControllerSlopBreakYConfig` | `0x200001b` · Read/Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:125；`Module.Setting.View/ViewSettingControl.cs:306` |
| `DJIMainControllerSlopBreakXConfig` | `0x200001c` · Read/Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:127；`Module.Setting.View/ViewSettingControl.cs:307` |
| `DJIMainControllerChassisPosition` | `0x200001d` · Action | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:129；未见非 Base.CONTROL 的显式引用 |
| `DJIMainControllerWheelSpeed` | `0x200001e` · Write | 部分 | KMP 仅四轮全零制动；Python Chassis.drive_wheels 可独立轮速；Lab 可编程 | Keys:131；未见非 Base.CONTROL 的显式引用 |
| `DJIRobomasterMainControllerEscEncodingStatus` | `0x200001f` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:133；`Module.MotorAddress.System/SystemMotorAddress.cs:30` |
| `DJIRobomasterMainControllerEscEncodeFlag` | `0x2000020` · Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:135；未见非 Base.CONTROL 的显式引用 |
| `DJIRobomasterMainControllerStartIMUCalibration` | `0x2000021` · Action | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:137；未见非 Base.CONTROL 的显式引用 |
| `DJIRobomasterMainControllerIMUCalibrationState` | `0x2000022` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:139；`Module.GimbalAndChassisCalibrate.View/ViewGimbalAndChassisCalibrate.cs:104` |
| `DJIRobomasterMainControllerIMUCalibrationCurrSide` | `0x2000023` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:141；`Module.GimbalAndChassisCalibrate.View/ViewGimbalAndChassisCalibrate.cs:94` |
| `DJIRobomasterMainControllerIMUCalibrationProgress` | `0x2000024` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:143；`Module.GimbalAndChassisCalibrate.View/ViewGimbalAndChassisCalibrate.cs:106` |
| `DJIRobomasterMainControllerIMUCalibrationFailCode` | `0x2000025` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:145；`Module.GimbalAndChassisCalibrate.View/ViewGimbalAndChassisCalibrate.cs:107` |
| `DJIRobomasterMainControllerIMUCalibrationFinishFlag` | `0x2000026` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:147；`Module.GimbalAndChassisCalibrate.View/ViewGimbalAndChassisCalibrate.cs:108` |
| `DJIRobomasterMainControllerStopIMUCalibration` | `0x2000027` · Action | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:149；未见非 Base.CONTROL 的显式引用 |
| `DJIRobomasterChassisMode` | `0x2000028` · Read | 固定初始化 | 进入遥控固定 work mode；无用户模式选择或当前模式解码 | Keys:151；`Module.LAB.View/StatusPanel.cs:65` |
| `DJIRobomasterChassisSpeed` | `0x2000029` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:153；`Module.LAB.View/StatusPanel.cs:66` |
| `DJIRobomasterOpenChassisSpeedUpdates` | `0x200002a` · Action | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:155；未见非 Base.CONTROL 的显式引用 |
| `DJIRobomasterCloseChassisSpeedUpdates` | `0x200002b` · Action | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:157；未见非 Base.CONTROL 的显式引用 |
| `DJIRobomasterMainControllerRelativePosition` | `0x200002c` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:159；未见非 Base.CONTROL 的显式引用 |
| `DJIMainControllerArmServoID` | `0x200002d` · Read/Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:161；`Module.Setting.View/ViewSettingModules.cs:42` |
| `DJIMainControllerServoAddressing` | `0x200002e` · Action | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:163；未见非 Base.CONTROL 的显式引用 |

## 03 · 遥控器连接（1）

| key | 桥接 ID / 访问 | Hanppie 主链路 | 当前差异 | 官方代码定位 |
| --- | --- | --- | --- | --- |
| `DJIRemoteControllerConnection` | `0x3000001` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:165；未见非 Base.CONTROL 的显式引用 |

## 04 · 云台（19）

| key | 桥接 ID / 访问 | Hanppie 主链路 | 当前差异 | 官方代码定位 |
| --- | --- | --- | --- | --- |
| `DJIGimbalConnection` | `0x4000001` · Read | 不同路径 | AppSession 连通状态 / workingDevices；没有这些逐模块连接 key 的独立状态流 | Keys:167；`Module.SinglePlayer.System/SystemSinglePlayer.cs:140` |
| `DJIGimbalESCFirmwareVersion` | `0x4000002` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:169；`Module.InnerTools.View/ViewPandect.cs:151` |
| `DJIGimbalFirmwareVersion` | `0x4000003` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:171；`Module.InnerTools.View/ViewPandect.cs:32` |
| `DJIGimbalWorkMode` | `0x4000004` · Read/Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:173；`Module.InnerTools.View/ViewGimbal.cs:39` |
| `DJIGimbalControlMode` | `0x4000005` · Read/Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:175；`Module.InnerTools.View/ViewGimbal.cs:66` |
| `DJIGimbalResetPosition` | `0x4000006` · Action | 不同路径 | GimbalCentering 速度反馈回中；未接原生 04:4c 回中任务 | Keys:177；`Module.InnerTools.View/ViewGimbal.cs:72` |
| `DJIGimbalResetPositionState` | `0x4000007` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:179；`Virtual/VisualGimbalAbstraction.cs:13` |
| `DJIGimbalCalibration` | `0x4000008` · Action | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:181；`Module.InnerTools.View/ViewGimbal.cs:78` |
| `DJIGimbalSpeedRotation` | `0x4000009` · Action | 部分 | AppSession 04:0c 俯仰/偏航速度；并非官方全部轴与命令变体 | Keys:183；未见非 Base.CONTROL 的显式引用 |
| `DJIGimbalSpeedRotationEnabled` | `0x400000a` · Write | 不同路径 | 由 remote 授权及短输入租约控制；无这个独立配置项 | Keys:185；未见非 Base.CONTROL 的显式引用 |
| `DJIGimbalAngleIncrementRotation` | `0x400000b` · Action | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:187；未见非 Base.CONTROL 的显式引用 |
| `DJIGimbalAngleFrontYawRotation` | `0x400000c` · Action | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:189；未见非 Base.CONTROL 的显式引用 |
| `DJIGimbalAngleFrontPitchRotation` | `0x400000d` · Action | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:191；未见非 Base.CONTROL 的显式引用 |
| `DJIGimbalAttitude` | `0x400000e` · Read | 接入 | GimbalSubscription / Telemetry.gimbal；固定订阅与会话生命周期关闭 | Keys:193；`Element.S1/S1_Fire.cs:415` |
| `DJIGimbalAutoCalibrate` | `0x400000f` · Action | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:195；`Module.InnerTools.View/ViewGimbal.cs:163` |
| `DJIGimbalCalibrationStatus` | `0x4000010` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:197；`Module.GimbalAndChassisCalibrate.View/ViewGimbalAndChassisCalibrate.cs:109` |
| `DJIGimbalCalibrationProgress` | `0x4000011` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:199；`Module.GimbalAndChassisCalibrate.View/ViewGimbalAndChassisCalibrate.cs:110` |
| `DJIGimbalOpenAttitudeUpdates` | `0x4000012` · Action | 接入 | GimbalSubscription / Telemetry.gimbal；固定订阅与会话生命周期关闭 | Keys:201；未见非 Base.CONTROL 的显式引用 |
| `DJIGimbalCloseAttitudeUpdates` | `0x4000013` · Action | 不同路径 | 遥测随会话持续，断线释放；未提供独立取消订阅 API | Keys:203；未见非 Base.CONTROL 的显式引用 |

## 05 · 系统、Lab 与对战（93）

| key | 桥接 ID / 访问 | Hanppie 主链路 | 当前差异 | 官方代码定位 |
| --- | --- | --- | --- | --- |
| `DJIRobomasterSystemConnection` | `0x5000001` · Read | 不同路径 | AppSession 连通状态 / workingDevices；没有这些逐模块连接 key 的独立状态流 | Keys:205；`Module.Setting.View/ReticleSettingView.cs:59` |
| `DJIRobomasterSystemFirmwareVersion` | `0x5000002` · Read | 固定初始化 | connectionSetup 发通用版本查询；当前未解析、展示或升级固件版本 | Keys:207；`Module.Setting.View/ViewSettingSystem.cs:61` |
| `DJIRobomasterSystemCANFirmwareVersion` | `0x5000003` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:209；`Module.InnerTools.View/ViewPandect.cs:137` |
| `DJIRobomasterSystemScratchFirmwareVersion` | `0x5000004` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:211；`Module.InnerTools.View/ViewPandect.cs:144` |
| `DJIRobomasterSystemSerialNumber` | `0x5000005` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:213；未见非 Base.CONTROL 的显式引用 |
| `DJIRobomasterSystemAbilitiesAttack` | `0x5000006` · Action | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:215；`Module.Battle.System/NetworkVirtualRobot.cs:87` |
| `DJIRobomasterSystemUnderAbilitiesAttack` | `0x5000007` · Read/Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:217；`Module.SinglePlayer.System/SystemSinglePlayer.cs:132` |
| `DJIRobomasterSystemKill` | `0x5000008` · Action | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:219；`Module.InnerTools.View/ViewJudge.cs:132` |
| `DJIRobomasterSystemRevive` | `0x5000009` · Action | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:221；`Module.InnerTools.View/ViewJudge.cs:127` |
| `DJIRobomasterSystemGet1860LinkAck` | `0x500000a` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:223；`Module.InnerTools.View/ViewChip1860.cs:56` |
| `DJIMainControllerGetLinkAck` | `0x500000b` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:225；`Module.InnerTools.View/ViewChip1860.cs:76` |
| `DJIGimbalGetLinkAck` | `0x500000c` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:227；`Module.InnerTools.View/ViewChip1860.cs:66` |
| `DJIRobomasterSystemGameRoleConfig` | `0x500000d` · Read/Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:229；`Module.InnerTools.View/ViewJudge.cs:78` |
| `DJIRobomasterSystemGameColorConfig` | `0x500000e` · Read/Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:231；`Module.Setting.View/ViewSettingDisplay.cs:39` |
| `DJIRobomasterSystemGameStart` | `0x500000f` · Action | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:233；未见非 Base.CONTROL 的显式引用 |
| `DJIRobomasterSystemGameEnd` | `0x5000010` · Action | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:235；未见非 Base.CONTROL 的显式引用 |
| `DJIRobomasterSystemDebugLog` | `0x5000011` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:237；`Module.InnerTools.View/ViewChip1860.cs:137` |
| `DJIRobomasterSystemSoundEnabled` | `0x5000012` · Read/Write | 部分 | 可开启机载麦克风下行；不回读原生开关、无完整音频状态模型 | Keys:239；`COMMON/CommonMediaPanel.cs:138` |
| `DJIRobomasterSystemLeftHeadlightBrightness` | `0x5000013` · Read/Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:241；`Module.Setting.View/ViewSettingDisplay.cs:40` |
| `DJIRobomasterSystemRightHeadlightBrightness` | `0x5000014` · Read/Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:243；未见非 Base.CONTROL 的显式引用 |
| `DJIRobomasterSystemLEDColor` | `0x5000015` · Write | 部分 | RemoteControl.led 与状态色；Python 支持分区；未接官方完整灯效配置 | Keys:245；未见非 Base.CONTROL 的显式引用 |
| `DJIRobomasterSystemUploadScratch` | `0x5000016` · Write | 不同路径 | 当前应用使用 FTP + DSP 上传路径，而非该 key 的全部上传后端 | Keys:247；未见非 Base.CONTROL 的显式引用 |
| `DJIRobomasterSystemUploadScratchByFTP` | `0x5000017` · Write | 接入 | LabController 单槽 DSP 上传、启动与停止；不包含自定义技能/自主程序安装 | Keys:249；`Module.LAB.System/SystemUploadDsp.cs:223` |
| `DJIRobomasterSystemUninstallScratchSkill` | `0x5000018` · Action | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:251；`Module.LAB.System/SystemUploadDsp.cs:318` |
| `DJIRobomasterSystemInstallScratchSkill` | `0x5000019` · Action | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:253；`Module.LAB.System/SystemUploadDsp.cs:263` |
| `DJIRobomasterSystemInquiryDspMd5` | `0x500001a` · Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:255；`Module.LAB.System/SystemUploadDsp.cs:267` |
| `DJIRobomasterSystemInquiryDspMd5Ack` | `0x500001b` · Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:257；未见非 Base.CONTROL 的显式引用 |
| `DJIRobomasterSystemInquiryDspResourceMd5` | `0x500001c` · Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:259；`Module.LAB.System/SystemUploadDsp.cs:236` |
| `DJIRobomasterSystemInquiryDspResourceMd5Ack` | `0x500001d` · Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:261；未见非 Base.CONTROL 的显式引用 |
| `DJIRobomasterSystemLaunchSinglePlayerCustomSkill` | `0x500001e` · Action | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:263；未见非 Base.CONTROL 的显式引用 |
| `DJIRobomasterSystemStopSinglePlayerCustomSkill` | `0x500001f` · Action | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:265；未见非 Base.CONTROL 的显式引用 |
| `DJIRobomasterSystemControlScratch` | `0x5000020` · Action | 接入 | LabController 单槽 DSP 上传、启动与停止；不包含自定义技能/自主程序安装 | Keys:267；`Module.LAB.System/SystemLAB.cs:54` |
| `DJIRobomasterSystemScratchState` | `0x5000021` · Read | 部分 | 当前 runId 的启动/完成/异常与控制台；未实现官方所有技能与回调事件类型 | Keys:269；`Module.SinglePlayer.System/SystemSinglePlayer.cs:137` |
| `DJIRobomasterSystemScratchCallback` | `0x5000022` · Read | 部分 | 当前 runId 的启动/完成/异常与控制台；未实现官方所有技能与回调事件类型 | Keys:271；`Module.LAB.System/SystemUploadDsp.cs:33` |
| `DJIRobomasterSystemForesightPosition` | `0x5000023` · Read/Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:273；未见非 Base.CONTROL 的显式引用 |
| `DJIRobomasterSystemPullLogFiles` | `0x5000024` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:275；`Module.LAB.Virtual/BaseWebViewController.cs:1144` |
| `DJIRobomasterSystemCurrentHP` | `0x5000025` · Read/Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:277；`Module.SinglePlayer.System/SystemSinglePlayer.cs:133` |
| `DJIRobomasterSystemTotalHP` | `0x5000026` · Read/Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:279；`Module.SinglePlayer.System/SystemSinglePlayer.cs:134` |
| `DJIRobomasterSystemCurrentBullets` | `0x5000027` · Read/Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:281；未见非 Base.CONTROL 的显式引用 |
| `DJIRobomasterSystemTotalBullets` | `0x5000028` · Read/Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:283；未见非 Base.CONTROL 的显式引用 |
| `DJIRobomasterSystemEquipments` | `0x5000029` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:285；未见非 Base.CONTROL 的显式引用 |
| `DJIRobomasterSystemBuffs` | `0x500002a` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:287；`Module.SinglePlayer.System/SystemSinglePlayer.cs:142` |
| `DJIRobomasterSystemSkillStatus` | `0x500002b` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:289；`Module.Battle.System/BattlePlayer.cs:393` |
| `DJIRobomasterSystemGunCoolDown` | `0x500002c` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:291；`UI/FireOverHeat.cs:163` |
| `DJIRobomasterSystemGameConfigList` | `0x500002d` · Write | 固定初始化 | RemoteCommands 固定裁判/安全/页面模式载荷；不构成完整用户配置与状态管理 | Keys:293；未见非 Base.CONTROL 的显式引用 |
| `DJIRobomasterSystemCarAndSkillID` | `0x500002e` · Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:295；未见非 Base.CONTROL 的显式引用 |
| `DJIRobomasterSystemAppStatus` | `0x500002f` · Write | 固定初始化 | RemoteCommands 固定裁判/安全/页面模式载荷；不构成完整用户配置与状态管理 | Keys:297；未见非 Base.CONTROL 的显式引用 |
| `DJIRobomasterSystemLaunchMultiPlayerSkill` | `0x5000030` · Action | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:299；未见非 Base.CONTROL 的显式引用 |
| `DJIRobomasterSystemStopMultiPlayerSkill` | `0x5000031` · Action | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:301；未见非 Base.CONTROL 的显式引用 |
| `DJIRobomasterSystemConfigSkillTable` | `0x5000032` · Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:303；未见非 Base.CONTROL 的显式引用 |
| `DJIRobomasterSystemWorkingDevices` | `0x5000033` · Read | 接入 | RobotProductProtocol.capabilities：模块及 detail 清单 | Keys:305；`Module.Setting.View/ViewSettingDevice.cs:69` |
| `DJIRobomasterSystemExceptions` | `0x5000034` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:307；`Module.Setting.View/ViewSettingDevice.cs:68` |
| `DJIRobomasterSystemTaskStatus` | `0x5000035` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:309；`Virtual/VirtualUnderpanAbstraction.cs:12` |
| `DJIRobomasterSystemReturnEnabled` | `0x5000036` · Read/Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:311；`Module.Setting.View/ViewSettingConnect.cs:50` |
| `DJIRobomasterSystemSafeMode` | `0x5000037` · Read/Write | 固定初始化 | RemoteCommands 固定裁判/安全/页面模式载荷；不构成完整用户配置与状态管理 | Keys:313；未见非 Base.CONTROL 的显式引用 |
| `DJIRobomasterSystemScratchExecuteState` | `0x5000038` · Read | 部分 | 当前 runId 的启动/完成/异常与控制台；未实现官方所有技能与回调事件类型 | Keys:315；`Module.LAB.Virtual/VirtualControlManager.cs:105` |
| `DJIRobomasterSystemAttitudeInfo` | `0x5000039` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:317；未见非 Base.CONTROL 的显式引用 |
| `DJIRobomasterSystemSightBeadPosition` | `0x500003a` · Read/Write | 固定初始化 | LabController 查询 3f:57；未解码或提供准星校准写入流程 | Keys:319；`Module.Setting.View/ReticleSettingView.cs:49` |
| `DJIRobomasterSystemSpeakerLanguage` | `0x500003b` · Read/Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:321；`Module.Setting.View/ViewSettingSystem.cs:115` |
| `DJIRobomasterSystemSpeakerVolumn` | `0x500003c` · Read/Write | 部分 | AppSession.setSpeakerVolume + 设置持久化；仅写入，不回读机器人音量 | Keys:323；`Module.Setting.View/ViewSettingSystem.cs:116` |
| `DJIRobomasterSystemChassisSpeedLevel` | `0x500003d` · Read/Write | 不同路径 | 五档本地速度设置；没有官方 3f:3f 机内速度等级配置 | Keys:325；`Module.Setting.View/ViewSettingControl.cs:300` |
| `DJIRobomasterSystemIsEncryptedFirmware` | `0x500003e` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:327；`Module.FirmwareUpgrade.System/SystemFirmwareUpgrade.cs:1147` |
| `DJIRobomasterSystemScratchErrorInfo` | `0x500003f` · Read | 部分 | 当前 runId 的启动/完成/异常与控制台；未实现官方所有技能与回调事件类型 | Keys:329；`Module.LAB.Virtual/VirtualControlManager.cs:111` |
| `DJIRobomasterSystemScratchOutputInfo` | `0x5000040` · Read | 部分 | 当前 runId 的启动/完成/异常与控制台；未实现官方所有技能与回调事件类型 | Keys:331；`Module.LAB.Virtual/VirtualControlManager.cs:112` |
| `DJIRobomasterSystemBarrelCoolDown` | `0x5000041` · Action | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:333；未见非 Base.CONTROL 的显式引用 |
| `DJIRobomasterSystemResetBarrelOverheat` | `0x5000042` · Action | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:335；未见非 Base.CONTROL 的显式引用 |
| `DJIRobomasterSystemMobileAccelerInfo` | `0x5000043` · Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:337；未见非 Base.CONTROL 的显式引用 |
| `DJIRobomasterSystemMobileGyroAttitudeAngleInfo` | `0x5000044` · Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:339；未见非 Base.CONTROL 的显式引用 |
| `DJIRobomasterSystemMobileGyroRotationRateInfo` | `0x5000045` · Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:341；未见非 Base.CONTROL 的显式引用 |
| `DJIRobomasterSystemEnableAcceleratorSubscribe` | `0x5000046` · Read/Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:343；`DJI/DJISubscribeController.cs:56` |
| `DJIRobomasterSystemEnableGyroRotationRateSubscribe` | `0x5000047` · Read/Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:345；`DJI/DJISubscribeController.cs:58` |
| `DJIRobomasterSystemEnableGyroAttitudeAngleSubscribe` | `0x5000048` · Read/Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:347；`DJI/DJISubscribeController.cs:57` |
| `DJIRobomasterSystemDeactivate` | `0x5000049` · Action | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:349；`Module.InnerTools.View/ViewActivation.cs:34` |
| `DJIRobomasterSystemFunctionEnable` | `0x500004a` · Action | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:351；未见非 Base.CONTROL 的显式引用 |
| `DJIRobomasterSystemIsGameRunning` | `0x500004b` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:353；`Module.Battle/DeviceConnectListener.cs:80` |
| `DJIRobomasterSystemIsActivated` | `0x500004c` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:355；`Module.VersionComparison.System/SystemVersionComparison.cs:83` |
| `DJIRobomasterSystemLowPowerConsumption` | `0x500004d` · Read/Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:357；`Module.Setting.View/ViewSettingSystem.cs:66` |
| `DJIRobomasterSystemEnterLowPowerConsumption` | `0x500004e` · Action | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:359；未见非 Base.CONTROL 的显式引用 |
| `DJIRobomasterSystemExitLowPowerConsumption` | `0x500004f` · Action | 固定初始化 | Lab 上传前 3f:4c 退出低功耗；无待机策略/参数配置 | Keys:361；未见非 Base.CONTROL 的显式引用 |
| `DJIRobomasterSystemIsLowPowerConsumption` | `0x5000050` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:363；`Module.RMConnection.System/SystemRMConnection.cs:43` |
| `DJIRobomasterSystemPushFile` | `0x5000051` · Action | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:365；`RecordAudio/RecordAudioPanel.cs:400` |
| `DJIRobomasterSystemPlaySound` | `0x5000052` · Action | 部分 | AppSession.playSpeaker、Lab 音频与启动音；无官方全部音效/任务控制 | Keys:367；`RecordAudio/RecordAudioPanel.cs:421` |
| `DJIRobomasterSystemPlaySoundStatus` | `0x5000053` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:369；`RecordAudio/RecordAudioPanel.cs:137` |
| `DJIRobomasterSystemCustomUIAttribute` | `0x5000054` · Read | 固定初始化 | Lab 启停中固定回复 3f:ba；不解析或渲染脚本自定义控件 | Keys:371；`Module.LAB/LABCustomUIManger.cs:23` |
| `DJIRobomasterSystemCustomUIFunctionEvent` | `0x5000055` · Action | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:373；未见非 Base.CONTROL 的显式引用 |
| `DJIRobomasterSystemTotalMileage` | `0x5000056` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:375；`Module.Account/DJIUserGrowthManger.cs:71` |
| `DJIRobomasterSystemTotalDrivingTime` | `0x5000057` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:377；`Module.Account/DJIUserGrowthManger.cs:72` |
| `DJIRobomasterSystemSetPlayMode` | `0x5000058` · Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:379；未见非 Base.CONTROL 的显式引用 |
| `DJIRobomasterSystemCustomSkillInfo` | `0x5000059` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:381；未见非 Base.CONTROL 的显式引用 |
| `DJIRobomasterSystemAddressing` | `0x500005a` · Action | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:383；`Module.InnerTools.View/ViewChip1860.cs:256` |
| `DJIRobomasterSystemLEDLightEffect` | `0x500005b` · Action | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:385；未见非 Base.CONTROL 的显式引用 |
| `DJIRobomasterSystemOpenImageTransmission` | `0x500005c` · Action | 不同路径 | AppSession.media 管理视频/音频流；不等于所有原生图传配置 | Keys:387；未见非 Base.CONTROL 的显式引用 |
| `DJIRobomasterSystemCloseImageTransmission` | `0x500005d` · Action | 不同路径 | AppSession.media 管理视频/音频流；不等于所有原生图传配置 | Keys:389；未见非 Base.CONTROL 的显式引用 |

## 06 · 视觉（19）

| key | 桥接 ID / 访问 | Hanppie 主链路 | 当前差异 | 官方代码定位 |
| --- | --- | --- | --- | --- |
| `DJIVisionFirmwareVersion` | `0x6000001` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:391；`Module.InnerTools.View/ViewPandect.cs:88` |
| `DJIVisionTrackingAutoLockTarget` | `0x6000002` · Read/Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:393；未见非 Base.CONTROL 的显式引用 |
| `DJIVisionARParameters` | `0x6000003` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:395；未见非 Base.CONTROL 的显式引用 |
| `DJIVisionARTagEnabled` | `0x6000004` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:397；未见非 Base.CONTROL 的显式引用 |
| `DJIVisionDebugRect` | `0x6000005` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:399；`Module.InnerTools.View/ViewDrawRect.cs:35` |
| `DJIVisionLaserPosition` | `0x6000006` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:401；`Module.SinglePlayer.System/SystemSinglePlayer.cs:136` |
| `DJIVisionDetectionEnable` | `0x6000007` · Read/Write | 部分 | 新跟随只启用 HumanTracking 位；无通用视觉开关/资源优先级/回读 | Keys:403；`Module.InnerTools.View/ViewVrsion.cs:49` |
| `DJIVisionMarkerRunningStatus` | `0x6000008` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:405；`Module.SinglePlayer.System/SystemSinglePlayer.cs:141` |
| `DJIVisionTrackingRunningStatus` | `0x6000009` · Read | 接入-离线 | ObjectTracking / TrackingProtocol；仅离线与回环证据，待实机 | Keys:407；`Module.SinglePlayer.System/VisionFollow.cs:63` |
| `DJIVisionAimbotRunningStatus` | `0x600000a` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:409；`Module.Battle.System/BattlePlayer.cs:390` |
| `DJIVisionHeadAndShoulderStatus` | `0x600000b` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:411；未见非 Base.CONTROL 的显式引用 |
| `DJIVisionHumanDetectionRunningStatus` | `0x600000c` · Read | 接入-离线 | ObjectTracking / TrackingProtocol；仅离线与回环证据，待实机 | Keys:413；`Module.SinglePlayer.System/VisionFollow.cs:64` |
| `DJIVisionUserConfirm` | `0x600000d` · Action | 接入-离线 | ObjectTracking / TrackingProtocol；仅离线与回环证据，待实机 | Keys:415；`Module.InnerTools.View/ViewVrsion.cs:201` |
| `DJIVisionUserCancel` | `0x600000e` · Action | 接入-离线 | ObjectTracking / TrackingProtocol；仅离线与回环证据，待实机 | Keys:417；未见非 Base.CONTROL 的显式引用 |
| `DJIVisionUserTrackingRect` | `0x600000f` · Write | 接入-离线 | ObjectTracking / TrackingProtocol；仅离线与回环证据，待实机 | Keys:419；`Module.InnerTools.View/ViewVrsion.cs:236` |
| `DJIVisionTrackingDistance` | `0x6000010` · Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:421；未见非 Base.CONTROL 的显式引用 |
| `DJIVisionLineColor` | `0x6000011` · Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:423；未见非 Base.CONTROL 的显式引用 |
| `DJIVisionMarkerColor` | `0x6000012` · Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:425；未见非 Base.CONTROL 的显式引用 |
| `DJIVisionMarkerAdvanceStatus` | `0x6000013` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:427；未见非 Base.CONTROL 的显式引用 |

## 07 · 空中链路通用项（4）

| key | 桥接 ID / 访问 | Hanppie 主链路 | 当前差异 | 官方代码定位 |
| --- | --- | --- | --- | --- |
| `DJIAirLinkConnection` | `0x7000001` · Read | 不同路径 | AppSession 连通状态 / workingDevices；没有这些逐模块连接 key 的独立状态流 | Keys:491；`Module.SystemCountryCode.System/SystemCountryCode.cs:19` |
| `DJIAirLinkSignalQuality` | `0x7000002` · Read | 部分 | Telemetry.wifiSignalQuality：07:09 原始质量值；非完整链路健康状态 | Keys:493；`Module.Setting.View/ViewSettingConnect.cs:46` |
| `DJIAirLinkCountryCode` | `0x7000003` · Write | 固定初始化 | ConnectionSetup 固定 JP 字节；未接官方国家代码及终端 5G 能力管理 | Keys:495；`Module.SystemCountryCode.System/SystemCountryCode.cs:54` |
| `DJIAirLinkCountryCodeUpdated` | `0x7000004` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:497；未见非 Base.CONTROL 的显式引用 |

## 08 · Wi-Fi（13）

| key | 桥接 ID / 访问 | Hanppie 主链路 | 当前差异 | 官方代码定位 |
| --- | --- | --- | --- | --- |
| `DJIWiFiLinkFirmwareVersion` | `0x8000001` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:451；未见非 Base.CONTROL 的显式引用 |
| `DJIWiFiLinkDebugInfo` | `0x8000002` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:453；`Module.InnerTools.View/ViewChip1860.cs:130` |
| `DJIWiFiLinkMode` | `0x8000003` · Read | 固定初始化 | 遥控握手查询 07:39；无 AP/Station/SDR 完整状态配置 | Keys:455；`Module.Setting.View/ViewSettingConnect.cs:45` |
| `DJIWiFiLinkSSID` | `0x8000004` · Read/Write | 部分 | 支持路由器扫码配网与本地保存；不等于 AP SSID/密码原生查询及修改 | Keys:457；`Module.Setting.View/ViewSettingConnect.cs:48` |
| `DJIWiFiLinkPassword` | `0x8000005` · Read/Write | 部分 | 支持路由器扫码配网与本地保存；不等于 AP SSID/密码原生查询及修改 | Keys:459；`Module.Setting.View/ViewSettingConnect.cs:49` |
| `DJIWiFiLinkAvailableChannelNumbers` | `0x8000006` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:461；未见非 Base.CONTROL 的显式引用 |
| `DJIWiFiLinkCurrentChannelNumber` | `0x8000007` · Read/Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:463；`Module.Setting.View/ViewSettingConnect.cs:47` |
| `DJIWiFiLinkSNR` | `0x8000008` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:465；`Module.Setting.View/ViewSettingConnect.cs:52` |
| `DJIWiFiLinkSNRPushEnabled` | `0x8000009` · Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:467；`Module.InnerTools.View/ViewWiFi.cs:41` |
| `DJIWiFiLinkReboot` | `0x800000a` · Action | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:469；`Module.InnerTools.View/ViewWiFi.cs:134` |
| `DJIWiFiLinkChannelSelectionMode` | `0x800000b` · Read/Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:471；`Module.Setting.View/ViewSettingConnect.cs:51` |
| `DJIWiFiLinkInterference` | `0x800000c` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:473；`Module.RMConnection.System/SystemRMConnection.cs:42` |
| `DJIWiFiLinkDeleteNetworkConfig` | `0x800000d` · Action | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:475；未见非 Base.CONTROL 的显式引用 |

## 09 · 装甲（11）

| key | 桥接 ID / 访问 | Hanppie 主链路 | 当前差异 | 官方代码定位 |
| --- | --- | --- | --- | --- |
| `DJIArmorFirmwareVersion1` | `0x9000001` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:499；`Module.InnerTools.View/ViewPandect.cs:46` |
| `DJIArmorFirmwareVersion2` | `0x9000002` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:501；`Module.InnerTools.View/ViewPandect.cs:53` |
| `DJIArmorFirmwareVersion3` | `0x9000003` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:503；`Module.InnerTools.View/ViewPandect.cs:60` |
| `DJIArmorFirmwareVersion4` | `0x9000004` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:505；`Module.InnerTools.View/ViewPandect.cs:67` |
| `DJIArmorFirmwareVersion5` | `0x9000005` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:507；`Module.InnerTools.View/ViewPandect.cs:74` |
| `DJIArmorFirmwareVersion6` | `0x9000006` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:509；`Module.InnerTools.View/ViewPandect.cs:81` |
| `DJIArmorUnderAttack` | `0x9000007` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:511；`Module.SinglePlayer.System/SystemSinglePlayer.cs:130` |
| `DJIArmorEnterResetID` | `0x9000008` · Action | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:513；`Module.InnerTools.View/ViewChip1860.cs:224` |
| `DJIArmorCancelResetID` | `0x9000009` · Action | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:515；`Module.InnerTools.View/ViewChip1860.cs:239` |
| `DJIArmorSkipCurrentID` | `0x900000a` · Action | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:517；未见非 Base.CONTROL 的显式引用 |
| `DJIArmorResetStatus` | `0x900000b` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:519；`Module.ArmorAddress.View/ViewArmorAddress.cs:102` |

## 0a · 水弹发射器（5）

| key | 桥接 ID / 访问 | Hanppie 主链路 | 当前差异 | 官方代码定位 |
| --- | --- | --- | --- | --- |
| `DJIRobomasterWaterGunFirmwareVersion` | `0xa000001` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:521；`Module.InnerTools.View/ViewPandect.cs:39` |
| `DJIRobomasterWaterGunWaterGunFire` | `0xa000002` · Action | 部分 | 遥控红外持续触发及水弹单发重复；无官方全部次数/频率/裁判反馈配置 | Keys:523；`Module.Battle.System/NetworkVirtualRobot.cs:86` |
| `DJIRobomasterWaterGunWaterGunFireWithTimes` | `0xa000003` · Action | 部分 | 遥控红外持续触发及水弹单发重复；无官方全部次数/频率/裁判反馈配置 | Keys:525；未见非 Base.CONTROL 的显式引用 |
| `DJIRobomasterWaterGunShootSpeed` | `0xa000004` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:527；`Module.InnerTools.View/ViewJudge.cs:64` |
| `DJIRobomasterWaterGunShootFrequency` | `0xa000005` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:529；`Module.SinglePlayer.System/SystemSinglePlayer.cs:131` |

## 0b · 感知（3）

| key | 桥接 ID / 访问 | Hanppie 主链路 | 当前差异 | 官方代码定位 |
| --- | --- | --- | --- | --- |
| `DJIPerceptionFirmwareVersion` | `0xb000001` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:429；`Module.InnerTools.View/ViewPandect.cs:95` |
| `DJIPerceptionMarkerEnable` | `0xb000002` · Read/Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:431；`Module.InnerTools.View/ViewPerception.cs:30` |
| `DJIPerceptionMarkerResult` | `0xb000003` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:433；`Module.InnerTools.View/ViewDrawMark.cs:22` |

## 0c · 电调（8）

| key | 桥接 ID / 访问 | Hanppie 主链路 | 当前差异 | 官方代码定位 |
| --- | --- | --- | --- | --- |
| `DJIESCFirmwareVersion1` | `0xc000001` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:435；`Module.InnerTools.View/ViewPandect.cs:102` |
| `DJIESCFirmwareVersion2` | `0xc000002` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:437；`Module.InnerTools.View/ViewPandect.cs:109` |
| `DJIESCFirmwareVersion3` | `0xc000003` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:439；`Module.InnerTools.View/ViewPandect.cs:116` |
| `DJIESCFirmwareVersion4` | `0xc000004` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:441；`Module.InnerTools.View/ViewPandect.cs:123` |
| `DJIESCMotorInfomation1` | `0xc000005` · Read | 部分 | Telemetry.wheels 解码四轮 RPM/编码器；未覆盖每电调完整健康及参数 | Keys:443；`Module.InnerTools.View/ViewChassis.cs:120` |
| `DJIESCMotorInfomation2` | `0xc000006` · Read | 部分 | Telemetry.wheels 解码四轮 RPM/编码器；未覆盖每电调完整健康及参数 | Keys:445；`Module.InnerTools.View/ViewChassis.cs:129` |
| `DJIESCMotorInfomation3` | `0xc000007` · Read | 部分 | Telemetry.wheels 解码四轮 RPM/编码器；未覆盖每电调完整健康及参数 | Keys:447；`Module.InnerTools.View/ViewChassis.cs:138` |
| `DJIESCMotorInfomation4` | `0xc000008` · Read | 部分 | Telemetry.wheels 解码四轮 RPM/编码器；未覆盖每电调完整健康及参数 | Keys:449；`Module.InnerTools.View/ViewChassis.cs:147` |

## 0d · 电池（7）

| key | 桥接 ID / 访问 | Hanppie 主链路 | 当前差异 | 官方代码定位 |
| --- | --- | --- | --- | --- |
| `DJIRobomasterBatteryFirmwareVersion` | `0xd000001` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:539；`Module.InnerTools.View/ViewPandect.cs:158` |
| `DJIRobomasterBatteryPowerPercent` | `0xd000002` · Read | 接入 | Telemetry.motion / ConsoleState 电池百分比；不含电压/电流/温度 | Keys:541；`Module.Setting.View/ViewSettingSystem.cs:67` |
| `DJIRobomasterBatteryVoltage` | `0xd000003` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:543；未见非 Base.CONTROL 的显式引用 |
| `DJIRobomasterBatteryTemperature` | `0xd000004` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:545；未见非 Base.CONTROL 的显式引用 |
| `DJIRobomasterBatteryCurrent` | `0xd000005` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:547；未见非 Base.CONTROL 的显式引用 |
| `DJIRobomasterBatteryShutdown` | `0xd000006` · Action | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:549；未见非 Base.CONTROL 的显式引用 |
| `DJIRobomasterBatteryReboot` | `0xd000007` · Action | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:551；未见非 Base.CONTROL 的显式引用 |

## 0e · 官方手柄（24）

| key | 桥接 ID / 访问 | Hanppie 主链路 | 当前差异 | 官方代码定位 |
| --- | --- | --- | --- | --- |
| `DJIRobomasterGamePadConnection` | `0xe000001` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:553；`Module.Setting.System/SystemSetting.cs:63` |
| `DJIRobomasterGamePadFirmwareVersion` | `0xe000002` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:555；`DJI/DJIGamePadActivation.cs:69` |
| `DJIRobomasterGamePadHasMouse` | `0xe000003` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:557；`Module.GamePadStatus.System/SystemGamePadStatus.cs:40` |
| `DJIRobomasterGamePadHasKeyboard` | `0xe000004` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:559；`Module.GamePadStatus.System/SystemGamePadStatus.cs:41` |
| `DJIRobomasterGamePadCtrlSensitivityX` | `0xe000005` · Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:561；未见非 Base.CONTROL 的显式引用 |
| `DJIRobomasterGamePadCtrlSensitivityY` | `0xe000006` · Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:563；未见非 Base.CONTROL 的显式引用 |
| `DJIRobomasterGamePadCtrlSensitivityYaw` | `0xe000007` · Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:565；`Module.Setting.System/SystemSetting.cs:56` |
| `DJIRobomasterGamePadCtrlSensitivityYawSlop` | `0xe000008` · Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:567；未见非 Base.CONTROL 的显式引用 |
| `DJIRobomasterGamePadCtrlSensitivityYawDeadZone` | `0xe000009` · Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:569；未见非 Base.CONTROL 的显式引用 |
| `DJIRobomasterGamePadCtrlSensitivityPitch` | `0xe00000a` · Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:571；`Module.Setting.System/SystemSetting.cs:62` |
| `DJIRobomasterGamePadCtrlSensitivityPitchSlop` | `0xe00000b` · Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:573；未见非 Base.CONTROL 的显式引用 |
| `DJIRobomasterGamePadCtrlSensitivityPitchDeadZone` | `0xe00000c` · Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:575；未见非 Base.CONTROL 的显式引用 |
| `DJIRobomasterGamePadMouseLeftButton` | `0xe00000d` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:577；`Module.GamePadStatus.System/SystemGamePadStatus.cs:50` |
| `DJIRobomasterGamePadMouseRightButton` | `0xe00000e` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:579；`Module.GamePadStatus.System/SystemGamePadStatus.cs:49` |
| `DJIRobomasterGamePadC1` | `0xe00000f` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:581；`Module.GamePadStatus.System/SystemGamePadStatus.cs:51` |
| `DJIRobomasterGamePadC2` | `0xe000010` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:583；`Module.GamePadStatus.System/SystemGamePadStatus.cs:52` |
| `DJIRobomasterGamePadFire` | `0xe000011` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:585；`Module.GamePadStatus.System/SystemGamePadStatus.cs:53` |
| `DJIRobomasterGamePadFn` | `0xe000012` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:587；`Module.GamePadStatus.System/SystemGamePadStatus.cs:54` |
| `DJIRobomasterGamePadNoCalibrate` | `0xe000013` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:589；`Module.GamePadStatus.System/SystemGamePadStatus.cs:43` |
| `DJIRobomasterGamePadNotAtMiddle` | `0xe000014` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:591；`Module.GamePadStatus.System/SystemGamePadStatus.cs:44` |
| `DJIRobomasterGamePadBatteryWarning` | `0xe000015` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:593；`Module.GamePadStatus.System/SystemGamePadStatus.cs:42` |
| `DJIRobomasterGamePadBatteryPercent` | `0xe000016` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:595；`Module.GamePadStatus.System/SystemGamePadStatus.cs:45` |
| `DJIRobomasterGamePadActivationSettings` | `0xe000017` · Read/Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:597；`DJI/DJIGamePadActivation.cs:57` |
| `DJIRobomasterGamePadControlEnabled` | `0xe000018` · Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:599；未见非 Base.CONTROL 的显式引用 |

## 0f · 机械爪（6）

| key | 桥接 ID / 访问 | Hanppie 主链路 | 当前差异 | 官方代码定位 |
| --- | --- | --- | --- | --- |
| `DJIRobomasterClawConnection` | `0xf000001` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:601；`Module.Setting.View/ViewSettingModules.cs:44` |
| `DJIRobomasterClawFirmwareVersion` | `0xf000002` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:603；`Module.Setting.View/ViewSettingModules.cs:529` |
| `DJIRobomasterClawCtrl` | `0xf000003` · Action | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:605；未见非 Base.CONTROL 的显式引用 |
| `DJIRobomasterClawStatus` | `0xf000004` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:607；未见非 Base.CONTROL 的显式引用 |
| `DJIRobomasterClawInfoSubscribe` | `0xf000005` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:609；`Module.Setting.View/ViewSettingModules.cs:50` |
| `DJIRobomasterEnableClawInfoSubscribe` | `0xf000006` · Action | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:611；`Module.Setting.View/ViewSettingModules.cs:518` |

## 10 · SDR（7）

| key | 桥接 ID / 访问 | Hanppie 主链路 | 当前差异 | 官方代码定位 |
| --- | --- | --- | --- | --- |
| `DJISDRLinkSNR` | `0x10000001` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:477；`Module.InnerTools.View/ViewSDR.cs:117` |
| `DJISDRLinkBandwidth` | `0x10000002` · Read/Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:479；`Module.InnerTools.View/ViewSDR.cs:76` |
| `DJISDRLinkChannelSelectionMode` | `0x10000003` · Read/Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:481；`Module.InnerTools.View/ViewSDR.cs:103` |
| `DJISDRLinkCurrentFreqPoint` | `0x10000004` · Read/Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:483；`Module.InnerTools.View/ViewSDR.cs:18` |
| `DJISDRLinkCurrentFreqBand` | `0x10000005` · Read/Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:485；未见非 Base.CONTROL 的显式引用 |
| `DJISDRLinkIsDualFreqSupported` | `0x10000006` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:487；`Module.InnerTools.View/ViewSDR.cs:96` |
| `DJISDRLinkUpdateConfigs` | `0x10000007` · Action | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:489；`Module.InnerTools.View/ViewSDR.cs:131` |

## 11 · 机械臂（10）

| key | 桥接 ID / 访问 | Hanppie 主链路 | 当前差异 | 官方代码定位 |
| --- | --- | --- | --- | --- |
| `DJIRobomasterArmConnection` | `0x11000001` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:613；`Module.Setting.View/ViewSettingModules.cs:43` |
| `DJIRobomasterArmCtrl` | `0x11000002` · Action | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:615；未见非 Base.CONTROL 的显式引用 |
| `DJIRobomasterArmCtrlMode` | `0x11000003` · Action | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:617；未见非 Base.CONTROL 的显式引用 |
| `DJIRobomasterArmCalibration` | `0x11000004` · Action | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:619；未见非 Base.CONTROL 的显式引用 |
| `DJIRobomasterArmBlockedFlag` | `0x11000005` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:621；未见非 Base.CONTROL 的显式引用 |
| `DJIRobomasterArmPositionSubscribe` | `0x11000006` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:623；`Module.Setting.View/ViewSettingModules.cs:49` |
| `DJIRobomasterArmReachLimitX` | `0x11000007` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:625；`Element.S1Edu/RoboticArmControl.cs:53` |
| `DJIRobomasterArmReachLimitY` | `0x11000008` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:627；`Element.S1Edu/RoboticArmControl.cs:54` |
| `DJIRobomasterEnableArmInfoSubscribe` | `0x11000009` · Action | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:629；`Module.Setting.View/ViewSettingModules.cs:547` |
| `DJIRobomasterArmControlMode` | `0x1100000a` · Read/Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:631；`Module.Setting.View/ViewSettingModules.cs:48` |

## 12 · 红外发射器（4）

| key | 桥接 ID / 访问 | Hanppie 主链路 | 当前差异 | 官方代码定位 |
| --- | --- | --- | --- | --- |
| `DJIRobomasterInfraredGunConnection` | `0x12000001` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:531；未见非 Base.CONTROL 的显式引用 |
| `DJIRobomasterInfraredGunFirmwareVersion` | `0x12000002` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:533；未见非 Base.CONTROL 的显式引用 |
| `DJIRobomasterInfraredGunInfraredGunFire` | `0x12000003` · Action | 部分 | 遥控红外持续触发及水弹单发重复；无官方全部次数/频率/裁判反馈配置 | Keys:535；`Module.InnerTools.View/ViewGun.cs:213` |
| `DJIRobomasterInfraredGunShootFrequency` | `0x12000004` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:537；未见非 Base.CONTROL 的显式引用 |

## 13 · ToF（9）

| key | 桥接 ID / 访问 | Hanppie 主链路 | 当前差异 | 官方代码定位 |
| --- | --- | --- | --- | --- |
| `DJIRobomasterTOFConnection` | `0x13000001` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:633；未见非 Base.CONTROL 的显式引用 |
| `DJIRobomasterTOFLEDColor` | `0x13000002` · Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:635；未见非 Base.CONTROL 的显式引用 |
| `DJIRobomasterTOFOnlineModules` | `0x13000003` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:637；`Module.Setting.View/ViewSettingModules.cs:46` |
| `DJIRobomasterTOFInfoSubscribe` | `0x13000004` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:639；`Module.Setting.View/ViewSettingModules.cs:52` |
| `DJIRobomasterEnableTOFInfoSubscribe` | `0x13000005` · Action | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:641；`Module.Setting.View/ViewSettingModules.cs:410` |
| `DJIRobomasterTOFFirmwareVersion1` | `0x13000006` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:643；`Module.Setting.View/ViewSettingModules.cs:424` |
| `DJIRobomasterTOFFirmwareVersion2` | `0x13000007` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:645；`Module.Setting.View/ViewSettingModules.cs:425` |
| `DJIRobomasterTOFFirmwareVersion3` | `0x13000008` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:647；`Module.Setting.View/ViewSettingModules.cs:426` |
| `DJIRobomasterTOFFirmwareVersion4` | `0x13000009` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:649；`Module.Setting.View/ViewSettingModules.cs:427` |

## 14 · 舵机（10）

| key | 桥接 ID / 访问 | Hanppie 主链路 | 当前差异 | 官方代码定位 |
| --- | --- | --- | --- | --- |
| `DJIRobomasterServoConnection` | `0x14000001` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:651；未见非 Base.CONTROL 的显式引用 |
| `DJIRobomasterServoLEDColor` | `0x14000002` · Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:653；未见非 Base.CONTROL 的显式引用 |
| `DJIRobomasterServoSpeed` | `0x14000003` · Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:655；未见非 Base.CONTROL 的显式引用 |
| `DJIRobomasterServoOnlineModules` | `0x14000004` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:657；`Module.Setting.View/ViewSettingModules.cs:45` |
| `DJIRobomasterServoInfoSubscribe` | `0x14000005` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:659；`Module.Setting.View/ViewSettingModules.cs:51` |
| `DJIRobomasterEnableServoInfoSubscribe` | `0x14000006` · Action | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:661；`Module.Setting.View/ViewSettingModules.cs:461` |
| `DJIRobomasterServoFirmwareVersion1` | `0x14000007` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:663；`Module.Setting.View/ViewSettingModules.cs:476` |
| `DJIRobomasterServoFirmwareVersion2` | `0x14000008` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:665；`Module.Setting.View/ViewSettingModules.cs:477` |
| `DJIRobomasterServoFirmwareVersion3` | `0x14000009` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:667；`Module.Setting.View/ViewSettingModules.cs:478` |
| `DJIRobomasterServoFirmwareVersion4` | `0x1400000a` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:669；未见非 Base.CONTROL 的显式引用 |

## 15 · 传感器转接板（11）

| key | 桥接 ID / 访问 | Hanppie 主链路 | 当前差异 | 官方代码定位 |
| --- | --- | --- | --- | --- |
| `DJIRobomasterSensorAdapterConnection` | `0x15000001` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:671；未见非 Base.CONTROL 的显式引用 |
| `DJIRobomasterSensorAdapterOnlineModules` | `0x15000002` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:673；`Module.Setting.View/ViewSettingModules.cs:47` |
| `DJIRobomasterSensorAdapterInfoSubscribe` | `0x15000003` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:675；`Module.Setting.View/ViewSettingModules.cs:53` |
| `DJIRobomasterEnableSensorAdapterInfoSubscribe` | `0x15000004` · Action | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:677；`Module.Setting.View/ViewSettingModules.cs:347` |
| `DJIRobomasterSensorAdapterFirmwareVersion1` | `0x15000005` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:679；`Module.Setting.View/ViewSettingModules.cs:361` |
| `DJIRobomasterSensorAdapterFirmwareVersion2` | `0x15000006` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:681；`Module.Setting.View/ViewSettingModules.cs:362` |
| `DJIRobomasterSensorAdapterFirmwareVersion3` | `0x15000007` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:683；`Module.Setting.View/ViewSettingModules.cs:363` |
| `DJIRobomasterSensorAdapterFirmwareVersion4` | `0x15000008` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:685；`Module.Setting.View/ViewSettingModules.cs:364` |
| `DJIRobomasterSensorAdapterFirmwareVersion5` | `0x15000009` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:687；`Module.Setting.View/ViewSettingModules.cs:365` |
| `DJIRobomasterSensorAdapterFirmwareVersion6` | `0x1500000a` · Read | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:689；`Module.Setting.View/ViewSettingModules.cs:366` |
| `DJIRobomasterSensorAdapterLEDColor` | `0x1500000b` · Write | 未接入 | 当前 KMP UI/状态/原生通道未见对应的具名能力；原始 send/request 不计作已实现 | Keys:691；未见非 Base.CONTROL 的显式引用 |
