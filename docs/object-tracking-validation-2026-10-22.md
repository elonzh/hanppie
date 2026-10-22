# 对象追踪跟随离线实现记录 · 2026-10-22

## 范围与证据

用户确认当前没有可用机器人，要求先完成离线实现。本轮没有连接设备、执行物理动作、构建或验证 Android，也没有提交或推送。

当前实现、状态归属、控制权及安全策略由 [architecture.md](./architecture.md#134-遥控操纵镜头坐标系与安全联动) 维护；官方原生指令与目标能力边界由 [architecture-robomaster.md](./architecture-robomaster.md#对象追踪跟随) 维护。

本轮输入为此前提取的 RoboMaster macOS 1.1.5/build 239，原生 `unitybridge` SHA-256：

```text
66e5ff67a64931ca492af448216cac3ee34323c3442bc72cd7acdcf19bf96e80
```

C# 证据位于本地忽略目录 `.hanppie/app-analysis/2026-09-08/decompiled/` 的 `Module.SinglePlayer.System/VisionFollow.cs`、`Base.CONTROL/RealController.cs` 和 `RobotService/DJI/` 枚举。原生发送函数地址：

| 函数 | 地址与直接证据 |
| --- | --- |
| `SetDetectionEnable` | `0x7c45f0`；`0x7c46b8` 写入 `0a:a3`，载荷长度 2 |
| `SetTrackingRect` | `0x7c5410`；`0x7c5483` 写入功能位，`0x7c5493` 复制四项 float，`0x7c54e8` 写入 `0a:a8`，载荷长度 19 |
| `ActionUserConfirm` | `0x7c6dd0`；`0x7c6e53` 写入启动位 1，`0x7c6ea4` 写入 `0a:a7`，载荷长度 7 |
| `ActionUserCancel` | `0x7c73f0`；`0x7c7473` 写入启动位 0，`0x7c74c4` 写入相同指令 |
| `OnRecvRunningStatusPush` | `0x7c3880`；读取偏移 1 状态、偏移 6 异常码、偏移 8 数量，普通目标步长 20；跳转表 `0x7c4204` 的类型 6 指向 TrackingRunningStatus 分支 |

使用 Xcode 的 `llvm-objdump --disassemble --demangle` 提取，反汇编结果留在 `.hanppie/validation/2026-10-22-tracking/vision-disassembly.txt`。单元测试固定原生构造函数的载荷布局，UDP 回环对端由测试模拟；二者均不是实机抓包。

## 验证方法

```sh
./gradlew :packages:robot-core:desktopTest --tests '*ObjectTracking*' --tests '*AppSessionIntegrationTest' :shared:jvmTest --tests '*ObjectTracking*Test' --tests '*Remote*UiTest' :desktopApp:classes
```

最终命令执行成功：协议与 UDP 回环 12 项、共享 UI 与遥控回归 21 项，共 33 项测试全部通过，无跳过；桌面 `classes` 编译成功。终端证据保留在 `.hanppie/validation/2026-10-22-tracking/acceptance.log`。

覆盖内容：

- 原生载荷字节布局、来源节点及帧属性、截断数据、非有限坐标和零面积框。
- 命令序号匹配，确认响应和运行状态推送的顺序，停止后晚到报文不恢复跟随。
- 停止重试、拒绝响应、视觉超时、目标丢失及操控租约过期。
- 真实 `AppSession` 与本机 UDP 对端往返，验证跟随期间不发送竞争的底盘/云台速度、手动接管先取消、禁止发射以及输入中断后的停止包。
- Compose 桌面交互完成选目标、确认、停止及画面过期取消；视频 Fit 变换忽略黑边。
- 844×390、1024×600、1280×720 三种实际桌面测试画布，另检查英文 1.4 倍字体；截图使用模拟目标框与空背景，不代表实机画面。

截图位于 `shared/build/reports/ui/tracking-*-zh.png` 和 `tracking-844-390-en.png`。首轮发现 `requiredSize` 在超出测试窗口时裁剪截图，已改用指定实际画布尺寸的桌面测试 API；交互回归发现子控件焦点更替误取消跟随，已去除该错误关联，保留原生窗口前后台和租约保护。

Impeccable 检测器对本轮操控 UI 返回空问题列表。人工已检查目标框、启停动作、遥测及摇杆在上述横屏尺寸与大字体下的可见性。

## 尚未建立的证据

尚未证明真实固件接受这些命令，也未验证目标识别范围、机体跟随、距离保持、遮挡恢复、极限工况或物理停止效果。后续需在安全空间中逐层验证协议响应、状态推送、执行器遥测和实际运动，不能以本轮测试替代。
