# EP 扩展硬件模块参考文档 (ep-extensions)

本文档是 `lab-python` 技能的附属参考文档，详细说明 RoboMaster EP / S1 EDU 机型的专有扩展硬件接口规范。

> [!IMPORTANT]
> **硬件机型边界与兼容性约束**：
> 以下扩展控制器依赖实体硬件外设（机械爪、机械臂、总线舵机、传感器转接板、ToF 测距板、用户串口）。在
> RoboMaster S1 标准版固件中，机内 `ModulesStatusCtrl` 会拦截调用并报告 `FAT_DEVICE_NOT_SUPPORT`。只有当设备为
> S1 EDU 或 RoboMaster EP，且物理连接并识别到对应扩展模块时方可使用。

所有常量均属于 `rm_define` 模块，使用时需带完整前缀。

---

## 1. 机械爪控制 (`robotic_gripper_ctrl`)

- `robotic_gripper_ctrl.open(level=1)`：张开机械爪，力量等级 1..4（默认为已设定的 power level）。
- `robotic_gripper_ctrl.close(level=1)`：闭合机械爪，力量等级 1..4。
- `robotic_gripper_ctrl.stop()`：停止机械爪当前运动。
- `robotic_gripper_ctrl.update_power_level(level)`：修改默认夹紧力量等级（1..4）。
- `robotic_gripper_ctrl.get_status()`：向硬件查询当前状态代码。
- `robotic_gripper_ctrl.is_open()`：机械爪是否处于完全张开状态（返回 `True`/`False`）。
- `robotic_gripper_ctrl.is_closed()`：机械爪是否处于闭合状态（返回 `True`/`False`）。

```python
# 示例：闭合机械爪抓取物体
def start():
    try:
        log_ctrl.print_msg("张开机械爪")
        robotic_gripper_ctrl.open(2)
        time.sleep(1)
        log_ctrl.print_msg("闭合抓取")
        robotic_gripper_ctrl.close(3)
        time.sleep(1)
    finally:
        robotic_gripper_ctrl.stop()
```

---

## 2. 机械臂控制 (`robotic_arm_ctrl`)

用于控制双自由度机械臂末端在垂直平面内的空间坐标（毫米）。

- `robotic_arm_ctrl.moveto(x, y, wait_for_complete=False)`：将机械臂末端移动到绝对坐标 `(x, y)`。若
  `wait_for_complete=True` 则阻塞等待动作完成。
- `robotic_arm_ctrl.move(x, y, wait_for_complete=False)`：在当前末端位置基础上进行相对坐标平移
  `(Δx, Δy)`。
- `robotic_arm_ctrl.recenter(wait_for_complete=False)`：机械臂复位回中。
- `robotic_arm_ctrl.get_position()`：向硬件读取当前末端实际绝对坐标，返回 `[x, y]`。
- `robotic_arm_ctrl.stop()`：停止机械臂当前运动。

```python
# 示例：机械臂抬起并前伸
def start():
    try:
        log_ctrl.print_msg("机械臂回中")
        robotic_arm_ctrl.recenter(wait_for_complete=True)
        log_ctrl.print_msg("移动到指定位置")
        robotic_arm_ctrl.moveto(150, 100, wait_for_complete=True)
        pos = robotic_arm_ctrl.get_position()
        log_ctrl.print_msg("当前坐标:", pos)
    finally:
        robotic_arm_ctrl.stop()
```

---

## 3. 总线舵机控制 (`servo_ctrl`)

- `servo_ctrl.set_angle(servo_id, angle, wait_for_complete=False)`：控制指定总线舵机的目标绝对角度，
  `servo_id` 为 `rm_define.servo1_id` 到 `rm_define.servo4_id`（或整数 1..4），`angle` 范围 -180..180°。
- `servo_ctrl.get_angle(servo_id)`：读取指定舵机的当前实际角度（-180..180°）。
- `servo_ctrl.set_speed(servo_id, speed)`：设置指定舵机的旋转角速度。
- `servo_ctrl.recenter(servo_id, wait_for_complete=False)`：指定舵机回中至 0°。
- `servo_ctrl.stop(servo_id)`：停止指定舵机的运动。

---

## 4. 传感器转接板 (`sensor_adapter_ctrl`)

用于读取接入转接板的各类第三方模拟或数字传感器。

- `sensor_adapter_ctrl.get_sensor_adapter_adc(sensor_adapter_id, port_num)`：读取转接板模拟量输入（ADC），采样值范围
  0..1023；`sensor_adapter_id` 为 1..6，`port_num` 为 1 或 2。
- `sensor_adapter_ctrl.get_sensor_adapter_io_level(sensor_adapter_id, port_num)`：读取 GPIO 端口数字电平，返回
  0 或 1。
- `sensor_adapter_ctrl.get_sensor_adapter_pulse_period(sensor_adapter_id, port_num)`
  ：测量端口输入方波的脉冲周期（微秒）。
- `sensor_adapter_ctrl.check_condition(func_str)` / `sensor_adapter_ctrl.cond_wait(func_str)`
  ：监听指定转接板端口的高/低电平触发事件（如 `rm_define.cond_sensor_adapter1_port1_high_event`、
  `cond_sensor_adapter1_port1_low_event`）。

---

## 5. 红外测距传感器 / ToF (`ir_distance_sensor_ctrl`)

- `ir_distance_sensor_ctrl.enable_measure(port_id)`：开启指定端口（1..4）的 ToF 测距模块。
- `ir_distance_sensor_ctrl.disable_measure(port_id)`：关闭指定端口测距。
- `ir_distance_sensor_ctrl.get_distance_info(port_id)`：读取目标反射距离（毫米）。

```python
# 示例：ToF 避障巡检
def start():
    ir_distance_sensor_ctrl.enable_measure(1)
    try:
        for _ in range(10):
            dist = ir_distance_sensor_ctrl.get_distance_info(1)
            log_ctrl.print_msg("ToF 距离(mm):", dist)
            time.sleep(0.5)
    finally:
        ir_distance_sensor_ctrl.disable_measure(1)
```

---

## 6. 用户串口通信 (`serial_ctrl` / `uart_ctrl`)

用于与机载第三方单片机（如 Arduino、STM32、树莓派）进行有线串口数据通信。

- `serial_ctrl.serial_config(baud_rate, data_bit, odd_even, stop_bit)`：配置串口通信参数：
    - `baud_rate`：波特率（如 9600、115200）。
    - `data_bit`：数据位宽，通常为 `'cs8'` 或 `'cs7'`。
    - `odd_even`：校验方式，可选 `'none'`、`'odd'`、`'even'`。
    - `stop_bit`：停止位，可选 1 或 2。
- `serial_ctrl.write_line(string)`：发送字符串并在末尾自动附加换行符 `\n`。
- `serial_ctrl.write_string(string)`：发送原始字符串（不附加换行符）。
- `serial_ctrl.write_number(value)`：将整型或浮点数值转换为字符串发送。
- `serial_ctrl.write_value(str_key, value)`：发送键值对格式 `"key:value"`。
- `serial_ctrl.write_numbers(*var_list)`：发送逗号分隔的数值序列。
- `serial_ctrl.read_line(timeout=-1)`：阻塞读取一行字符串，直到遇到 `\n`；可指定超时时间（秒），超时返回空字符串
  `""`。
- `serial_ctrl.read_until(char, timeout=-1)`：读取字符流直到遇到指定分隔符（限 `\n`, `$`, `#`, `.`,
  `:`, `;`）。
- `serial_ctrl.clear_recv_buffer()`：清空接收缓存区。
- `serial_ctrl.stop()` / `serial_ctrl.reset()`：停止串口通信或重置为初始配置。
