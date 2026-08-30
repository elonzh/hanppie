# S1 AppEnvelope 直控联调记录（2026-08-31）

> 本文只保存本次实现与实机联调证据，不维护当前能力结论。当前架构、后端选择、能力状态和安全边界以 [`architecture.md`](./architecture.md) 为唯一权威来源。

## 1. 范围与环境

- 目标：在不上传 Lab DSP 的情况下，经 S1 原厂移动端网络入口直接发送 DUSS/control，并把该路径接入唯一的 `hanppie diag`。
- 机器人：RoboMaster S1，固件 `00.06.0521`。
- 主机：macOS arm64，Python `3.10.20`。
- 连接：Station 模式局域网；所有命令显式提供 S1 IP 与 AppID；未使用 USB。
- 安全条件：机器人位于地面安全区域，弹仓为空；机械测试使用 `0.15 m/s`、`15°/s` 和短租约。

协议字段参考了 `tatsuyai713/RoboMaster-S1-WiFi-SDK` 的固定提交 `fce5ff284a1ca910b7ca93c499b95eea4813152e`，用于核对 App 模式初始化、control 编码和 DUSS 命令。Hanppie 没有把该仓库作为依赖，也没有复制其包结构；本次在已有 `AppConnection`、`AppEnvelope` 和固定报文测试上独立实现并逐项取得本机证据。

## 2. 实现记录

本次增加的主机路径包括：

1. `AppConnection` 支持同序号 DUSS ACK 等待、动态 control payload、周期 DUSS 和命令租约；
2. `DirectRobot` 维护原生控制模式、显式 arm/disarm、主动归零和模式退出；
3. 底盘速度使用 App control channel，云台速度使用 DUSS `0x04/0x69`；
4. 装甲灯与枪口灯使用 DUSS `0x3F/0x33`，内置音效使用 `0x3F/0x1A`；
5. 视频、麦克风和 Host PCM 继续复用已有 `AppConnection` 媒体实现；
6. `DeviceSession` 互斥管理 Direct 与 Lab 后端，切换前归零并关闭旧会话；
7. `diag failsafe` 用独立进程验证异常退出，避免同一进程的 `finally` 伪造停车证据。

相关单元测试在实机前通过：

```text
32 passed
```

覆盖范围包括控制编码边界、ACK 匹配、租约到期归零、模式生命周期、arm 门、原生遥测解码和诊断后端切换。

## 3. 无机械动作验证

执行：

```bash
uv run hanppie diag \
  --no-interactive \
  --robot-ip "$S1_IP" \
  --appid "$S1_APPID" \
  --check app,direct,led,muzzle,speaker \
  --timeout 12
```

本地证据目录：`.hanppie/diagnosis/20260831-000948/`。

| 项目 | 结果 | 本次证据 |
| --- | --- | --- |
| App | PASS | 电量 `87%`，UDP 会话建立 |
| Direct | PASS | 未上传 Lab 程序，未 arm；收到 62 字节底盘和 11 字节云台 `0x48/0x08` 遥测 |
| 内置音效 | PASS | `0x107`、`0x102` 均收到同序号成功 ACK；麦克风最大 RMS 相对基线 `14.95` 倍 |
| Host PCM | PASS | 1 秒 440 Hz 测试音，2 个传输块；目标频率幅度相对基线 `8.27` 倍 |
| 装甲灯 | PASS | 红、绿、蓝、白、关闭均收到 `0x3F/0x33` 成功 ACK |
| 枪口灯 | PASS | 常亮与开火灯效的开/关均收到成功 ACK |
| 清理 | PASS | neutral、灯光关闭、会话关闭 |

灯光项目只取得协议 ACK，未使用外部摄像机保存视觉证据；报告没有把 ACK 写成物理视觉确认。

## 4. 低速执行器验证

执行：

```bash
uv run hanppie diag \
  --no-interactive \
  --robot-ip "$S1_IP" \
  --appid "$S1_APPID" \
  --check chassis,gimbal \
  --allow-motion \
  --timeout 12
```

本地证据目录：`.hanppie/diagnosis/20260831-001016/`；补充的动作中 raw 采样位于 `.hanppie/diagnosis/20260831-001147/`。

- 底盘依次执行前、后、左、右、逆时针、顺时针；每步租约 `250 ms`，之后由发送循环改发 neutral，再显式 stop；六步完成后推定平面净位移约 `0.003 m`（第一次）与 `0.012 m`（补充采样）。
- 云台依次执行 pitch/yaw 正负方向；每步租约 `250 ms`，之后移除周期命令并发送零速；四项 raw 遥测随动作发生变化。
- `0x48/0x08` 中后续三个 float 在动作中和归零后可能保持不变，因此本次撤销了“速度字段”命名，只在报告中记录为 raw values。
- 本次没有外部距离计或角度计，不能据此标定底盘实际速度、坐标轴比例、云台角度或物理误差。

## 5. 主机进程异常退出

第一次实现只在子进程终止后被动绑定 UDP `10609`。旧会话只继续发出 1 个位置样本，无法单靠该样本判定机械状态，因此 `.hanppie/diagnosis/20260831-001422/` 正确记录为 FAIL，没有把“遥测停止”当作“机械停车”。

修正后流程为：

1. 子进程进入 Direct 模式并 arm；
2. 发送 `y=0.15 m/s`、租约 `5 s`，观察到推定平面位置变化 `0.0071 m`；
3. 父进程在租约仍有效时发送 `SIGTERM`，子进程不能运行 Python 清理；
4. 父进程被动监听旧会话 `1.5 s`；
5. 恢复 App 会话并读取同一开机周期的推定平面位置。

执行：

```bash
uv run hanppie diag \
  --no-interactive \
  --robot-ip "$S1_IP" \
  --appid "$S1_APPID" \
  --check failsafe \
  --allow-motion \
  --timeout 12
```

本地证据目录：`.hanppie/diagnosis/20260831-001517/`。

结果为 PASS：子进程退出码 `-15`，被动收到 1 个旧会话样本，恢复会话后相对终止前的推定平面位移为 `0.0085 m`，低于诊断上界 `0.05 m`。这是一项“失联后位移有界”的证据，不是精确制动时延；Wi-Fi 断开、选择性丢包和 session 抢占仍需分别测试。

## 6. Direct 与 Lab 后端切换

执行：

```bash
uv run hanppie diag \
  --no-interactive \
  --robot-ip "$S1_IP" \
  --appid "$S1_APPID" \
  --check lab,infrared,gel \
  --allow-infrared \
  --allow-gel \
  --timeout 15
```

本地证据目录：`.hanppie/diagnosis/20260831-001558/`。

- Lab Bridge 上传、启动、arm、双向遥测与清理通过；
- 会话切换到 Direct 后，红外 control 触发已执行，射击声和枪口闪光分别取得 DUSS ACK；现场操作者确认枪口灯产生可见反应，但没有外部红外接收证据；
- 再切回 Lab 后，空仓水弹执行器、射击声和枪口灯三个 Lab Python 调用均返回成功；现场操作者确认出现水弹击发机械动作，弹仓为空，因此没有弹丸射出证据；
- 最终清理确认 Lab 运动归零、灯光关闭和程序停止。

## 7. 最终完整回归

实现与文档收口后执行唯一入口的完整非交互回归：

```bash
uv run hanppie diag \
  --no-interactive \
  --all \
  --robot-ip "$S1_IP" \
  --appid "$S1_APPID" \
  --allow-motion \
  --allow-infrared \
  --allow-gel \
  --timeout 15 \
  --discovery-timeout 8
```

本地证据目录：`.hanppie/diagnosis/20260831-002451/`。

发现、App、Direct、视频、麦克风、扬声器、Lab、装甲灯、枪口灯、底盘、失联停止、云台、红外、空仓水弹、临时 ADB、系统采集以及最终清理全部 PASS。该轮失联后的推定平面位移为 `0.014 m`；系统采集确认原厂 UDP `30030` 仍未监听。最终清理由设备 shell 重启，重新收到 App 广播，并确认 TCP `5555` 保持关闭；主机复查该端口也返回拒绝连接。

运行后的现场观察补充了自动报告无法自行取得的物理证据：枪口灯确有可见反应，空仓水弹测试出现了击发机械动作。这两项观察不改变“没有外部红外接收”和“没有弹丸射出”的证据边界。

## 8. 本次结论边界

本次证据已经回答“是否必须上传 Lab Bridge 才能控制 S1”：对于已经映射的底盘速度、云台速度、灯光、内置声音、红外和媒体，答案是否定的；主机可直接经 `AppEnvelope` 承载 DUSS/control。Lab Bridge 仍用于执行机内 Python、高层控制对象和尚未完成直连映射的能力。

本次没有证明：

- UDP `10607` 是裸 DUSS 端口；实际仍有 App 外层 session/tick/channel；
- control channel 的每帧都有 ACK；底盘验证依赖连续发送、位置变化和安全归零；
- 原生 raw 遥测所有字段的单位与坐标系；
- 直连水弹、云台回中/角度任务、装甲事件或视觉识别；
- 跨公网远程控制的认证、加密、仲裁和失联安全。
