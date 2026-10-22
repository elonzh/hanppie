# 官方 App 配置对照附表 · 2026-10-22

本表是[差距调查报告](./official-app-gap-analysis-2026-10-22.md)的补充，枚举固定版本 `COMMON/SettingManager.cs` 默认 schema 的 75 个实际字段：Control 50、Display 17、System 6、Battle 2。九个灵敏度字段按输入配置索引 0..3 展开为 36 个；索引并不意味着每个平台均显示四套界面。

这些字段不包含服务器开关、所有 DJIPlayerPrefs 内部调试项，也不包含下文另列的裁判表、原生设备配置与硬件条件配置。因此 75 不能称为官方 App 的全部配置数量。只定义默认值、没有确认消费或公开入口的字段明确标记“未确认可达”。

证据文件位于本机忽略目录 `.hanppie/app-analysis/2026-09-08/decompiled/Assembly-CSharp/COMMON/SettingManager.cs`。对照目标为 `shared/ui/settings/SettingsStore.kt`、`MediaSettings.kt`、`RemotePage.kt`、`RemoteControl.kt` 与 `ConnectionSetup.kt`；对应代码全路径见主报告的证据索引。

## 持久偏好 schema

| 官方配置路径 | 官方默认值 | 当前状态 | Hanppie 差异 | 官方定位 |
| --- | --- | --- | --- | --- |
| `Control.Controller` | `2` | 未对齐 | 当前无官方四套输入配置；桌面键盘与共享双摇杆是主路径 | SettingManager:24 |
| `Control.ChassisMoveFSpeed` | `1.5f` | 不同路径 | 主机五档平移限速；无对应机内前/后/横独立参数配置 | SettingManager:29 |
| `Control.ChassisMoveBSpeed` | `1.5f` | 不同路径 | 主机五档平移限速；无对应机内前/后/横独立参数配置 | SettingManager:30 |
| `Control.ChassisMoveACCSpeed` | `50f` | 缺失 | 未接入独立加速/制动参数 | SettingManager:31 |
| `Control.ChassisMoveBrakeACCSpeed` | `50f` | 缺失 | 未接入独立加速/制动参数 | SettingManager:32 |
| `Control.ChassisLandMoveSpeed` | `1.5f` | 不同路径 | 主机五档平移限速；无对应机内前/后/横独立参数配置 | SettingManager:33 |
| `Control.ChassisLandMoveACCSpeed` | `50f` | 缺失 | 未接入独立加速/制动参数 | SettingManager:34 |
| `Control.ChassisLandMoveBrakeACCSpeed` | `50f` | 缺失 | 未接入独立加速/制动参数 | SettingManager:35 |
| `Control.SafeMode` | `false` | 缺失 | 无官方 S1_Fire 的水弹俯仰角安全锁及对应偏好 | SettingManager:36 |
| `Control.Gyro` | `false` | 缺失 | 没有终端陀螺仪采集/云台输入链路 | SettingManager:37 |
| `Control.GyroSensitivity` | `50` | 缺失 | 没有终端陀螺仪采集/云台输入链路 | SettingManager:38 |
| `Control.ForceTouch` | `false` | 缺失 | 无压力触控及振动偏好；是否适合当前设备需单独判断 | SettingManager:39 |
| `Control.ForceTouchSensitivity` | `3` | 缺失 | 无压力触控及振动偏好；是否适合当前设备需单独判断 | SettingManager:40 |
| `Control.HandheldVibrate` | `false` | 缺失 | 无压力触控及振动偏好；是否适合当前设备需单独判断 | SettingManager:41 |
| `Display.APPQuatily` | `0` | 缺失 | 固定 Filament 场景配置，无用户质量档位 | SettingManager:43 |
| `Display.FPVEffect` | `true` | 未确认可达 | 可见默认配置定义，未确认其独立公开设置入口 | SettingManager:44 |
| `Display.ReticleType` | `1` | 缺失 | 当前只有随弹药变化的固定准星；没有官方准星类型选择和校准 | SettingManager:45 |
| `Display.RobotColor` | `"#000000"` | 不同路径 | 已有四种遥控状态灯色；不等同官方持久机体/队伍颜色 | SettingManager:46 |
| `Display.VideoResolution` | `1` | 部分 | 可选 720p/1080p 推流；未接完整相机格式与 SD 录像状态 | SettingManager:47 |
| `Display.AntiFlashing` | `true` | 缺失 | 无机内抗闪烁设置/回读 | SettingManager:48 |
| `Display.Audio` | `true` | 不同路径 | 操控页临时监听开关；不是持久化的官方 Display.Audio 设置 | SettingManager:49 |
| `Display.AssistedDriving` | `false` | 未确认可达 | 官方存在设置/事件发送，但本轮未找到事件消费者；不能直接计作缺失功能 | SettingManager:50 |
| `Display.FPVFullDisplay` | `false` | 不同路径 | 当前视频统一 Fit，无独立全屏裁剪偏好 | SettingManager:51 |
| `Display.FPVGimbalScaleEnable` | `false` | 不同路径 | 当前固定显示角度指示器，不提供显示开关 | SettingManager:52 |
| `Display.FPVRoboticArmStatus` | `false` | 缺失 | 无机械臂 HUD；官方有硬件存在条件 | SettingManager:53 |
| `Display.ColorR` | `0.15f` | 不同路径 | 已有四种遥控状态灯色；不等同官方持久机体/队伍颜色 | SettingManager:54 |
| `Display.ColorG` | `0.89f` | 不同路径 | 已有四种遥控状态灯色；不等同官方持久机体/队伍颜色 | SettingManager:55 |
| `Display.ColorB` | `0.5f` | 不同路径 | 已有四种遥控状态灯色；不等同官方持久机体/队伍颜色 | SettingManager:56 |
| `Display.AutoRotationEnable` | `true` | 不同路径 | 项目按设计只支持左右横屏；官方此版界面还主动隐藏该开关 | SettingManager:57 |
| `Display.WindowMode` | `false` | 不同路径 | 已有桌面窗口、比例与位置管理；无相同的窗口模式/分辨率列表 | SettingManager:58 |
| `Display.WindowSize` | `"1920 * 1080"` | 不同路径 | 已有桌面窗口、比例与位置管理；无相同的窗口模式/分辨率列表 | SettingManager:59 |
| `System.Language` | 随系统语言选择，其他语言使用英文 | 部分 | 当前系统/简中/英文；没有官方其他语言资源 | SettingManager:63 |
| `System.GPS` | `true` | 未采用 | 官方隐私/统计相关开关；并非机器人操控必需功能，不建议仅为对齐而新增 | SettingManager:101 |
| `System.DJIRobotInfo` | `false` | 未采用 | 官方隐私/统计相关开关；并非机器人操控必需功能，不建议仅为对齐而新增 | SettingManager:102 |
| `System.PrintLog` | `false` | 不同路径 | 当前应用本身有诊断日志；不等同官方全局调试日志开关 | SettingManager:103 |
| `System.ProductImprove` | `false` | 未采用 | 官方隐私/统计相关开关；并非机器人操控必需功能，不建议仅为对齐而新增 | SettingManager:104 |
| `System.MasteryTracker` | `false` | 未采用 | 官方隐私/统计相关开关；并非机器人操控必需功能，不建议仅为对齐而新增 | SettingManager:105 |
| `Battle.BattleSkillA` | `1101` | 缺失 | 没有对战技能槽和绑定 | SettingManager:107 |
| `Battle.BattleSkillB` | `1102` | 缺失 | 没有对战技能槽和绑定 | SettingManager:108 |
| `Control.SensitivityMode0` | `0` | 缺失 | 没有四套官方输入配置的灵敏度预设切换 | SettingManager:113 |
| `Control.SensitivityMode1` | `0` | 缺失 | 没有四套官方输入配置的灵敏度预设切换 | SettingManager:113 |
| `Control.SensitivityMode2` | `0` | 缺失 | 没有四套官方输入配置的灵敏度预设切换 | SettingManager:113 |
| `Control.SensitivityMode3` | `0` | 缺失 | 没有四套官方输入配置的灵敏度预设切换 | SettingManager:113 |
| `Control.MoveFBSensitivityCustom0` | `35` | 不同路径 | 当前统一云台角速度/五档移动速度；没有按官方输入配置分别保存的轴增益及自定义预设 | SettingManager:114 |
| `Control.MoveFBSensitivityCustom1` | `35` | 不同路径 | 当前统一云台角速度/五档移动速度；没有按官方输入配置分别保存的轴增益及自定义预设 | SettingManager:114 |
| `Control.MoveFBSensitivityCustom2` | `35` | 不同路径 | 当前统一云台角速度/五档移动速度；没有按官方输入配置分别保存的轴增益及自定义预设 | SettingManager:114 |
| `Control.MoveFBSensitivityCustom3` | `35` | 不同路径 | 当前统一云台角速度/五档移动速度；没有按官方输入配置分别保存的轴增益及自定义预设 | SettingManager:114 |
| `Control.MoveLRSensitivityCustom0` | `35` | 不同路径 | 当前统一云台角速度/五档移动速度；没有按官方输入配置分别保存的轴增益及自定义预设 | SettingManager:115 |
| `Control.MoveLRSensitivityCustom1` | `35` | 不同路径 | 当前统一云台角速度/五档移动速度；没有按官方输入配置分别保存的轴增益及自定义预设 | SettingManager:115 |
| `Control.MoveLRSensitivityCustom2` | `35` | 不同路径 | 当前统一云台角速度/五档移动速度；没有按官方输入配置分别保存的轴增益及自定义预设 | SettingManager:115 |
| `Control.MoveLRSensitivityCustom3` | `35` | 不同路径 | 当前统一云台角速度/五档移动速度；没有按官方输入配置分别保存的轴增益及自定义预设 | SettingManager:115 |
| `Control.GimbalYawSensitivityCustom0` | `50` | 不同路径 | 当前统一云台角速度/五档移动速度；没有按官方输入配置分别保存的轴增益及自定义预设 | SettingManager:116 |
| `Control.GimbalYawSensitivityCustom1` | `50` | 不同路径 | 当前统一云台角速度/五档移动速度；没有按官方输入配置分别保存的轴增益及自定义预设 | SettingManager:116 |
| `Control.GimbalYawSensitivityCustom2` | `50` | 不同路径 | 当前统一云台角速度/五档移动速度；没有按官方输入配置分别保存的轴增益及自定义预设 | SettingManager:116 |
| `Control.GimbalYawSensitivityCustom3` | `50` | 不同路径 | 当前统一云台角速度/五档移动速度；没有按官方输入配置分别保存的轴增益及自定义预设 | SettingManager:116 |
| `Control.GimbalPitchSensitivityCustom0` | `40` | 不同路径 | 当前统一云台角速度/五档移动速度；没有按官方输入配置分别保存的轴增益及自定义预设 | SettingManager:117 |
| `Control.GimbalPitchSensitivityCustom1` | `40` | 不同路径 | 当前统一云台角速度/五档移动速度；没有按官方输入配置分别保存的轴增益及自定义预设 | SettingManager:117 |
| `Control.GimbalPitchSensitivityCustom2` | `40` | 不同路径 | 当前统一云台角速度/五档移动速度；没有按官方输入配置分别保存的轴增益及自定义预设 | SettingManager:117 |
| `Control.GimbalPitchSensitivityCustom3` | `40` | 不同路径 | 当前统一云台角速度/五档移动速度；没有按官方输入配置分别保存的轴增益及自定义预设 | SettingManager:117 |
| `Control.MoveFBSensitivity0` | `35` | 不同路径 | 当前统一云台角速度/五档移动速度；没有按官方输入配置分别保存的轴增益及自定义预设 | SettingManager:118 |
| `Control.MoveFBSensitivity1` | `35` | 不同路径 | 当前统一云台角速度/五档移动速度；没有按官方输入配置分别保存的轴增益及自定义预设 | SettingManager:118 |
| `Control.MoveFBSensitivity2` | `35` | 不同路径 | 当前统一云台角速度/五档移动速度；没有按官方输入配置分别保存的轴增益及自定义预设 | SettingManager:118 |
| `Control.MoveFBSensitivity3` | `35` | 不同路径 | 当前统一云台角速度/五档移动速度；没有按官方输入配置分别保存的轴增益及自定义预设 | SettingManager:118 |
| `Control.MoveLRSensitivity0` | `35` | 不同路径 | 当前统一云台角速度/五档移动速度；没有按官方输入配置分别保存的轴增益及自定义预设 | SettingManager:119 |
| `Control.MoveLRSensitivity1` | `35` | 不同路径 | 当前统一云台角速度/五档移动速度；没有按官方输入配置分别保存的轴增益及自定义预设 | SettingManager:119 |
| `Control.MoveLRSensitivity2` | `35` | 不同路径 | 当前统一云台角速度/五档移动速度；没有按官方输入配置分别保存的轴增益及自定义预设 | SettingManager:119 |
| `Control.MoveLRSensitivity3` | `35` | 不同路径 | 当前统一云台角速度/五档移动速度；没有按官方输入配置分别保存的轴增益及自定义预设 | SettingManager:119 |
| `Control.GimbalYawSensitivity0` | `50` | 不同路径 | 当前统一云台角速度/五档移动速度；没有按官方输入配置分别保存的轴增益及自定义预设 | SettingManager:120 |
| `Control.GimbalYawSensitivity1` | `50` | 不同路径 | 当前统一云台角速度/五档移动速度；没有按官方输入配置分别保存的轴增益及自定义预设 | SettingManager:120 |
| `Control.GimbalYawSensitivity2` | `50` | 不同路径 | 当前统一云台角速度/五档移动速度；没有按官方输入配置分别保存的轴增益及自定义预设 | SettingManager:120 |
| `Control.GimbalYawSensitivity3` | `50` | 不同路径 | 当前统一云台角速度/五档移动速度；没有按官方输入配置分别保存的轴增益及自定义预设 | SettingManager:120 |
| `Control.GimbalPitchSensitivity0` | `40` | 不同路径 | 当前统一云台角速度/五档移动速度；没有按官方输入配置分别保存的轴增益及自定义预设 | SettingManager:121 |
| `Control.GimbalPitchSensitivity1` | `40` | 不同路径 | 当前统一云台角速度/五档移动速度；没有按官方输入配置分别保存的轴增益及自定义预设 | SettingManager:121 |
| `Control.GimbalPitchSensitivity2` | `40` | 不同路径 | 当前统一云台角速度/五档移动速度；没有按官方输入配置分别保存的轴增益及自定义预设 | SettingManager:121 |
| `Control.GimbalPitchSensitivity3` | `40` | 不同路径 | 当前统一云台角速度/五档移动速度；没有按官方输入配置分别保存的轴增益及自定义预设 | SettingManager:121 |

## 不属于上述 75 项的配置面

| 官方配置来源 | 具体参数/行为 | 当前差距 |
| --- | --- | --- |
| `ConfigurationManager.CreateSinglePlayerConfiguration` | 裁判 Mode、Gun、Time、MaxHP、Hit、ShootingFrequency、OverheatIncrease、OverheatCool1/2、IrEnable、ArmorEnable、BloodCalcEnable、StickEnable | 初始化有固定 3f:09 参数，但没有裁判领域配置、回读、玩法闭环 |
| `CreateMultiPlayerConfiguration` | 上述裁判项及 MaxSpeed，技能/状态增益配置 | 没有多人房间、队伍、配装或配置管理 |
| `SkillConfiguration` / `buffConfiguration` | Blindness、Vertigo、Invincible、SpeedUp、CustomSkill；cd、buffNum、buffID | 未接入对战技能配置；普通运行 Lab 不是技能安装 |
| `CreateBattleParamConfiguration` | RadomSkillCd、VertigoTime、BlindnessTime、SpeedUpTime、InvincibleTime、DeadToInjured、ReviveGodTime、GunSpeed、ChaosModeSpeed、KillScore、HitScore、DeadScore、OverheatIncrease、OverheatCool1/2、各增益 Enable | 无对战参数模型/结算；部分字段是内部配置，不是都应向用户开放 |
| `CreateLABConfiguration` | Gun、ShootingFrequency、OverheatIncrease、OverheatCool1/2 | 已发送固定 Lab 参数；没有相同的可编辑裁判配置 |
| `ViewSettingControl` + 主控配置 key | 前/后/横速度及设备可用范围；加速、制动；机内速度等级 | 当前只设主机档位，没有设备参数读写与范围协商 |
| `ViewSettingConnect` / `WifiInfoView` | AP SSID/密码、自动/手动信道、信道号、SNR/干扰、断线后退 | 已有 AP/路由器连接和扫码配网；上述原生设置未形成管理流程 |
| `SystemCountryCode` | 国家代码及终端是否支持 5G | 当前只发送固定 JP 字节，无动态来源与能力协商 |
| `ViewSettingSystem` | 机器人语音语言、扬声器音量、低功耗开关/时限、关机、固件版本/升级、SD 卡空间/格式化、装甲/电机/舵机/ToF/适配板编址、云台/底盘/机械臂校准 | 音量已设但不回读；Lab 前退出低功耗；其余缺少完整流程 |
| `ViewSettingDisplay` | 灯色、头灯亮度、SD 拍摄格式、抗闪烁、硬件依赖的机械臂显示 | 有状态灯色与视频分辨率；其他项未完整对齐 |
| `ViewSettingModules` | 模块在线列表，机械臂安装/卸载、控制模式、扩展设备发现/标识 | 有 workingDevices 清单；缺少扩展操控与配置 UI |
| `ViewVisionFollow` / `SetTrackingDistance` | 跟随中左摇杆前后轴调整距离，0a:a7 子操作 2 | 新跟随尚未实现；当前所有非零方向输入都取消跟随 |
| `ReticleSettingView` | 准星 X/Y 偏移、归零、写回机器人 | 只有固定准星；没有标定流程 |
| 官方 GamePad key | 单独的轴增益、yaw/pitch 斜率/死区、激活及控制使能 | 当前没有官方手柄集成；触摸摇杆死区不是手柄配置 |
