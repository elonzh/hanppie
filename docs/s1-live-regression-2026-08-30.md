# RoboMaster S1 内置 App/Lab 后端真机回归记录

> 日期：2026-08-30
> 设备：RoboMaster S1，固件 `00.06.0521`
> 主机：macOS / Apple Silicon / Python 3.10
> 测试边界：机器人在地面，底盘和云台只执行短时低速动作；只测试红外发射，不执行水弹发射。
> 隐私：设备 IP、AppID、MAC 和序列号均使用占位符，不写入仓库。

本文是 `src/hanppie/lab` 替换外部 LAB-SDK 后首次完整真机回归的冻结证据。它不随代码和能力状态更新，也不维护“当前结论”；长期结论只查看 [`architecture.md`](./architecture.md)。外部参考仍参与时的初次恢复过程见 [`s1-live-debug-2026-08-29.md`](./s1-live-debug-2026-08-29.md)。

## 1. 执行范围与证据索引

本次运行依次留下以下证据：第 2～3 节记录发现、机内环境、视频、DSP 和 ADB；第 4～5 节记录 Bridge 就绪与旧解释器问题的定位；第 6 节保留底盘和云台的原始量化测量；第 7 节记录 LED 与红外 controller 返回；第 8 节记录退出后的设备状态。由这些证据支持的当前能力等级统一在 [`architecture.md` 的能力矩阵](./architecture.md#111-当前能力矩阵) 中维护。

## 2. 设备发现与只读基线

ADB 在冷启动后没有直接出现，因此先在主机 UDP 45678 被动监听 S1 广播，通过 `parse_robot_broadcast()` 解码设备身份。实际标识只在本地命令中使用，以下统一写作：

```bash
export S1_IP="<S1_IP>"
export S1_APPID="<S1_APPID>"
```

ADB bootstrap 后记录到：

```text
uid=0(root) gid=0(root)
Android 4.4.4 / API 19
build eng.jenkins.20221027.033121
/data/python_files/bin/python: Python 3.6.6
GCC 4.8.3 20140320 (prerelease)
```

root ADB 直接运行该解释器时，默认 `site` 初始化会因为 Android root UID 没有 passwd 记录而在 `getpwuid()` 失败。使用 `python -S` 跳过 `site` 后，`sys`、`socket`、`json`、`select`、`threading` 和 `_thread` 均可导入。解释器路径包含 `/data/dji_scratch/lib`、`/data/dji_scratch/src` 和 `/data/dji_scratch/src/robomaster`。

这次没有重新修改或启用官方 SDK proxy；本报告只验证内置 App/Lab 后端。

## 3. 视频、DSP 与 ADB 回归

视频探针：

```bash
uv run hanppie probe-video \
  --robot-ip "$S1_IP" \
  --appid "$S1_APPID" \
  --timeout 12 \
  --frame-timeout 12
```

关键结果：

```text
battery_percent=None
camera_frame=1280x720 format=yuv420p pts=None
stopped video stream
closed App-compatible connection
```

电量仍未从当前 App 数据中解析出来；视频与停流都已通过。

ADB bootstrap：

```bash
uv run hanppie adb-enable \
  --robot-ip "$S1_IP" \
  --appid "$S1_APPID" \
  --timeout 12 \
  --settle-seconds 6
```

该命令使用内置 DSP 生成、FTP 上传和 Lab 生命周期，成功开启 TCP 5555 root ADB。测试结束后通过重启关闭该无认证入口。

## 4. 首次 Bridge 超时与修复

第一次运行内置 `probe-lab` 时：

1. App 建连、进入 Lab、DSP 上传和程序启动均成功；
2. 主机发送了一次 stop/session probe 和一次 telemetry request；
3. 5 秒内没有收到匹配 session 的遥测，最终抛出 `TimeoutError`；
4. `finally` 仍正确停止程序并关闭连接。

root ADB 同时观察到 S1 已监听 UDP 40923。保持程序运行后，主机手工发送只含 stop、session ID 和命令序号的 JSON，S1 立即从随机源端口向 Host UDP 40924 返回位置和姿态。这证明机内载荷正常，丢失的是程序 UDP socket 尚未打开前的首个无连接 UDP probe。

修复后 `start_lab_bridge()` 在截止时间内周期发送幂等 stop/session probe，收到当前 session 遥测后继续重复 arm + neutral stop，直到遥测同时确认：

- `rx_command_seq` 不小于最后的 neutral stop 序号；
- `armed=true`。

完整 `probe-lab` 随后通过，取得 5 个连续姿态样本，并完成 disarm、Host Bridge 停止、Lab 程序停止和 App 关闭。

## 5. 机内控制分支兼容问题

第一轮低速动作中，S1 遥测的 `rx_command_seq` 已增加，但底盘和云台没有动作，载荷也没有更新最后命令字段。恢复代码确认 `move_with_speed()` 与 `rotate_with_speed()` 的方法名和参数顺序正确。

当时的载荷使用生成器 `any(...)` 判断运动字段，并以嵌套 `max/min` 做限幅；整个接收处理又会吞掉异常。项目做了两项修改：

1. 改为显式 `for` 循环和普通大小比较，减少固件旧解释器/执行器差异；
2. 遥测增加 `armed`、chassis/gimbal active、最后命令、controller 结果和错误字段。

修改后同一组 controller 方法立即产生真实动作。由于最初版本没有保留异常，不能严格断言是哪个内建函数不可用；目前只确认“Python 3.6 AST 可解析”不足以替代固件真机执行测试。

## 6. 底盘、watchdog 与云台量化结果

### 6.1 底盘

测试只发送一次、不由主机续租的前进命令：

```python
s1.bridge.send(x=0.20, y=0.0, z=0.0)
```

结果：

| 时刻 | `motion_active` | x / m | y / m |
| --- | --- | ---: | ---: |
| 基线 | false | -0.00088 | 0.00037 |
| 命令约 160 ms | true | -0.00061 | 0.00044 |
| 命令约 560 ms | false | 0.06375 | -0.01752 |
| 显式 stop 后 | false | 0.07563 | -0.02035 |

默认 300 ms watchdog 已触发，`motion_active` 自动变为 false。停止后的额外位移来自机器人响应延迟和惯性，因此安全控制不能把理论速度乘 watchdog 时间当作严格制动距离。

### 6.2 云台

最终停止回归使用 yaw `-15°/s` 的短动作：

| 阶段 | `gimbal_active` | `motion_active` | gimbal yaw |
| --- | --- | --- | ---: |
| 初始 | false | false | 约 0° |
| 速度控制中 | true | true | 约 -0.1° |
| stop 后 | false | false | 约 -2.1° |
| 再等待 350 ms | false | false | 约 -2.1° |

原实现中 `gimbal.stop()` 会发送停止，但主机续租线程仍保留旧速度；真机测试前已修正为在通用 chassis/gimbal 操作前原子清除对应续租字段。停止后的角度保持不变，证明旧速度没有被重新发送。

## 7. LED、红外和明确未测试项

Lab Bridge 依次发送绿色、蓝色和关闭 LED 命令，Host API 均返回 `True`。当前没有从外部相机观察机器人本体，所以只记为 controller 命令通过，不提升为本轮物理视觉证据。

红外测试只调用：

```python
s1.fire("infrared")
```

遥测确认 `last_command=blaster.fire_ir`、`last_command_ok=true`。没有目标接收器，因此没有验证红外命中效果。没有调用水弹 `gun_ctrl.fire_once()`。

## 8. 回归后的安全状态

每个测试都在 `finally` 中执行 stop/disarm、停止 Lab 程序和关闭 App 连接；ADB 检查确认 Lab 停止后 UDP 40923 不再监听。最后重启 S1，使 TCP 5555 root ADB 和其他运行态改动失效。

本轮同时执行了 `task check`、`task prek`、`task build` 和 Mermaid 渲染；这里只记录执行范围，不把当时结果当作当前分支的质量状态。
