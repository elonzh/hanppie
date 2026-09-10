# RoboMaster macOS 安装包静态分析记录（2026-09-08）

本文保存本次输入、证据定位、落地范围和验证结果；当前协议结论统一见 [架构文档 5.3.5](architecture.md#535-macos-客户端静态分析边界)，客户端行为见 [7.3.0](architecture.md#730-单体仓库与-kotlin-多平台客户端)。

## 输入与工具

- 输入文件：`1598859239029-RoboMaster.pkg`。
- SHA-256：`914feb56d8904eed1cea9c64c99fe99a23009f387ad6161a8d834b35167a7d81`。
- 包内 `Info.plist`：版本 `1.1.5`、build `239`、Unity `2019.2.3f1`。
- 使用 `pkgutil --expand-full` 解包、UnityPy `1.25.3` 导出资源、ILSpy `9.1.0.7988` 反编译程序集、Xcode `llvm-objdump` 反汇编原生库；没有运行厂商应用或连接机器人。
- 本地目录：`.hanppie/app-analysis/2026-09-08/`。厂商文件、完整源码导出、模型、截图及原始分析输出未加入版本控制。

## 证据定位

原生地址均属于本包 `Contents/Plugins/unitybridge.bundle/Contents/MacOS/unitybridge` 的 x86-64 Mach-O 虚拟地址，不是其他版本或加载后的运行时地址。

| 核查项 | 本次使用的证据 |
| --- | --- |
| 云台主题大小 | `SubscribeManager::GetStructureSize`，`0x7ec5d0..0x7ec710`；匹配分支位于 `0x7ec6a8`，返回值指令位于 `0x7ec6b7` |
| 云台角度读取 | `ActionOpenAttitudeUpdates` 的回调，`0x762170..0x7625d6`；UID 比较位于 `0x76218d`；角度乘数的 double 常量位于 `0x8b65f0` |
| 周期订阅头与四个角度 | 仓库上游 `ProtoDelMsg`、`ProtoAddSubMsg`、`ProtoPushPeriodMsg`、`GimbalPosSubject`；现有 App 初始化向量和 11 字节推送 |
| 客户端传感器来源 | `Assembly-CSharp/DJI/DJISubscribeController.cs` 的 `UpdateGyroAttitudeAngle` 与 `UpdateGyroRotationRate` |
| 内部调试控制入口 | `Assembly-CSharp/Module.InnerTools.View/ViewChassis.cs` 的 `sendSpeedAndFollow`；`RobotService/DJI/DJIKeys.cs` |
| 原生速度/跟随分支 | `DJIRobomasterChassisControlProcessor::OnTimerTicked`，`0x12250` 起；请求标识写入分别位于 `0x12375`、`0x124a4`；云台调用位于 `0x125a9` |

本地新增 `protocol/native-subscription-disassembly.txt`、`protocol/native-gimbal-attitude-disassembly.txt` 和含局部符号的 `protocol/unitybridge-all-symbols-demangled.txt`。取局部符号时使用 `nm -U`；仅使用 `nm -gU` 会漏掉云台订阅的 lambda 回调。

复现反汇编可使用：

```sh
xcrun llvm-objdump --disassemble --demangle \
  --start-address=0x762170 --stop-address=0x762720 \
  .hanppie/app-analysis/2026-09-08/package/Payload/RoboMaster.app/Contents/Plugins/unitybridge.bundle/Contents/MacOS/unitybridge
```

其他地址区段替换起止参数即可。常量读取需通过 Mach-O `LC_SEGMENT_64` 的 `vmaddr/fileoff` 映射；不能对所有 Mach-O 文件假定虚拟地址就是文件偏移。

## 本次应用范围

- Python / Kotlin 的现有云台订阅改为按字段构造，保留原始发送字节和序列位置；固定向量回归检查其一致性。
- 增加四个角度的具名解析，保留原始状态位；Python 解码入口补充无效帧拒绝。
- Python direct 诊断和桌面/Android 共享遥测界面接入解析结果；界面把云台与底盘数据分别保存，清除连接状态时同时清除云台快照。
- 新增或扩展 signed 值、订阅头、截断、保留状态位、控制 yaw 范围、连接失效和实际界面渲染测试。
- 未改变底盘/云台运动模式、停止策略、发送频率，也未删减其他固定初始化报文；未将未经验证的原生跟随分支投入控制。

## 验证记录

- `task check`：通过；Python 132 项通过，整体覆盖率 78%；Kotlin 协议、回环和桌面测试共 64 项，59 项通过、5 项按条件跳过，零失败。
- 检查时移除实机目标与云端测试启用参数，硬件测试保持跳过；本记录不构成实机验收。
- 已查看 `apps/desktop/build/reports/ui/gimbal-telemetry.png`，确认新增角度、状态字节和原始底盘字段同时显示。
- Python 定向测试 15 项通过，但单独运行时未满足全项目 70% 覆盖率门槛；以上完整 `task check` 已通过该门槛。

- `task prek`：全部适用检查通过。
- `task android:check`：Android Debug APK 构建和 lint 通过；未执行 Android 实机验收。
- 检查日志及界面截图保存在本地 `.hanppie/app-analysis/2026-09-08/validation/`。

## EP 打印模型复核（2026-09-11）

安装包的 `resources.assets` 中另有一套 EP 工程形态显示层级。其 `EP_UCX` 根节点（Transform `20605`）包含 20 个非 `UCX_*`、非描边的显示网格；组装外形约为 `223.079 × 256.435 × 388.712 mm`。该层级表现的是整套工程形态机械臂与底盘外观，不是单独的扩展平台 CAD；合并网格存在 958 条开放边，继续只作为结构和外形参照。

本次同时复核 `jeguzzi/robomaster_ros` 提交 `c05a39d7f0fa8b3b277aa74826aa92e202efc987` 的 MIT 许可 DAE/URDF。`endpoint_bracket.dae` 在合并 1 微米内的重合顶点后成为封闭网格，按 DAE 的米单位转为毫米后，外形约为 `39.984 × 52.418 × 40.969 mm`。`extension_base.dae` 原始网格有 108 条开放边：其中 8 对为约 3 mm 通孔的上下孔口，另外 3 个是四边面缺口；只补回对应孔壁和缺面后成为封闭网格，外形约为 `279.693 × 231.779 × 21.073 mm`。`extension_support.dae` 仍有无法无歧义消除的开放和重叠边界，只保留为 CAD 重建参照。

可切片候选、未修复对照、来源文件、MIT 许可证、SHA-256、网格统计、预览和复现脚本保存在 `.hanppie/ep-print-candidates/2026-09-11/`，不加入版本控制。两份封闭 STL 只通过离线网格检查，尚未按实物孔距、板厚、公差、受力和打印材料完成试装验收；不能标注为 DJI 官方制造文件或实机适配完成。
