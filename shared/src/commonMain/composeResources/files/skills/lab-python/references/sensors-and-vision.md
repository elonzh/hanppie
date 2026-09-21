# 感知、机器视觉与闭环控制参考文档 (sensors-and-vision)

本文档是 `lab-python` 技能的附属参考文档，详细说明 RoboMaster S1 / EP
在高级感知、传感器交互、机器视觉自瞄、巡线控制、拍手识别、移动端遥测及闭环算法方面的机内 API。

所有常量均属于 `rm_define` 模块，使用时需带完整前缀。

---

## 1. 装甲板与受击检测 (`armor_ctrl`)

用于检测车体和云台受到的物理撞击（弹丸或碰撞）以及红外光束照射命中。

### 灵敏度控制

- `armor_ctrl.set_hit_sensitivity(level)`：设置撞击检测灵敏度，`level` 为 0..10 的整数（默认
  5，数值越小越灵敏；机内换算系数为 `1.5 - level/10`）。
- `armor_ctrl.get_hit_sensitivity()`：获取当前设定的灵敏度数值。
- `armor_ctrl.reset_hit_sensitivity()`：重置灵敏度为默认值 5。

### 条件判定与阻塞等待

- `armor_ctrl.check_condition(condition_enum)`：非阻塞查询指定装甲板在最近 1 秒内是否受击，返回布尔值
  `True` 或 `False`。触发后内部状态自动复位。
- `armor_ctrl.cond_wait(condition_enum)`：阻塞当前执行流，直到指定的受击事件发生。
- **支持的条件枚举 (`rm_define`)**：
    - `rm_define.cond_armor_hit`：全车任意装甲板被撞击。
    - `rm_define.cond_armor_bottom_front_hit`：底盘前装甲受击。
    - `rm_define.cond_armor_bottom_back_hit`：底盘后装甲受击。
    - `rm_define.cond_armor_bottom_left_hit`：底盘左装甲受击。
    - `rm_define.cond_armor_bottom_right_hit`：底盘右装甲受击。
    - `rm_define.cond_armor_top_left_hit`：云台左装甲受击。
    - `rm_define.cond_armor_top_right_hit`：云台右装甲受击。
    - `rm_define.cond_ir_hit_detection`：任意红外传感器检测到红外光束命中。
    - `rm_define.cond_ir_top_left_hit`：云台左侧红外传感器命中。
    - `rm_define.cond_ir_top_right_hit`：云台右侧红外传感器命中。

### 击打历史查询

- `armor_ctrl.get_last_hit_armor()`：返回最后一次发生受击的装甲板标识（字符串名称或枚举）。
- `armor_ctrl.get_last_hit_index()`：向硬件查询最后受击的装甲板索引编号（1..6）；失败返回 -1。
- `armor_ctrl.get_last_hit_time()`：查询最后一次受击的绝对 UNIX 时间戳（秒）；失败返回 -1。
- `armor_ctrl.get_last_hit_info()`：返回元组 `(last_hit_index, last_hit_time)`。
- `armor_ctrl.stop()`：停止受击监听。

```python
# 示例：等待撞击受击并发出警报
def start():
    armor_ctrl.set_hit_sensitivity(3)
    try:
        log_ctrl.print_msg("等待受击...")
        armor_ctrl.cond_wait(rm_define.cond_armor_hit)
        log_ctrl.print_msg("检测到受击！部位:", armor_ctrl.get_last_hit_armor())
        media_ctrl.play_sound(rm_define.media_sound_attacked)
    finally:
        armor_ctrl.stop()
```

---

## 2. 机器视觉与巡线自瞄 (`vision_ctrl`)

通过视觉算法识别视野中的人、姿态手势、官方视觉标签、地面轨迹线及其他机器人。

### 功能开关

- `vision_ctrl.enable_detection(vision_func)`：开启指定视觉算法模块。
- `vision_ctrl.disable_detection(vision_func)`：关闭指定视觉算法模块。
- **支持的算法功能枚举 (`rm_define`)**：
    - `rm_define.vision_detection_people`：行人检测。
    - `rm_define.vision_detection_pose`：人体姿势与手势识别。
    - `rm_define.vision_detection_marker`：RoboMaster 官方视觉标签识别。
    - `rm_define.vision_detection_line`：地面轨迹引导线识别（巡线）。
    - `rm_define.vision_detection_car`：机器人车体检测。

### 属性过滤与参数配置

- `vision_ctrl.set_marker_detection_distance(distance)`：视觉标签有效检测距离，范围 0..3 米。
- `vision_ctrl.marker_detection_color_set(color)`：目标标签背景颜色，可选
  `rm_define.marker_detection_color_red`、`rm_define.marker_detection_color_blue`、
  `rm_define.marker_detection_color_green`。
- `vision_ctrl.line_follow_color_set(color)`：巡线引导线颜色，可选 `rm_define.line_follow_color_red`、
  `rm_define.line_follow_color_blue`、`rm_define.line_follow_color_green`。
- `vision_ctrl.line_follow_line_choice(direction)`：遭遇分叉路口时的选择方向，可选
  `rm_define.line_follow_left`、`rm_define.line_follow_right`、`rm_define.line_follow_forward`。
- `vision_ctrl.set_line_follow_speed(speed)`：巡线行驶线速度（m/s）。

### 识别结果数据结构

机内所有 `get_*_detection_info()` 若未检测到目标或数据超过 1 秒未刷新，均返回首元素为 0 的列表（巡线返回
`[0, 0]`）。检测到目标时返回扁平数组结构：

- `vision_ctrl.get_people_detection_info()`：
    - 返回 `[count, x1, y1, w1, h1, x2, y2, w2, h2, ...]`。
    - `count` 为目标数量；`x, y, w, h` 为视野归一化边界框（0.0..1.0），中心点为 `(x, y)`。
- `vision_ctrl.get_pose_detection_info()`：
    - 返回 `[count, pose_id1, x1, y1, w1, h1, ...]`。
    - `pose_id` 枚举：`rm_define.pose_victory` (4, 剪刀手)、`rm_define.pose_give_in` (5, 投降举双手)、
      `rm_define.pose_capture` (6, 拍照手势)、`rm_define.pose_left_hand_up` (2, 举左手)、
      `rm_define.pose_right_hand_up` (3, 举右手)。
- `vision_ctrl.get_marker_detection_info()`：
    - 返回 `[count, marker_id1, x1, y1, w1, h1, ...]`。
    - `marker_id` 常量对应：
        - 特殊标识：`rm_define.marker_trans_stop` (1, 停止)、`marker_trans_dice` (2, 骰子)、
          `marker_trans_target` (3, 靶心)、`marker_trans_left` (4)、`marker_trans_right` (5)、
          `marker_trans_forward` (6)、`marker_trans_backward` (7)、`marker_trans_red_heart` (8, 红心)、
          `marker_trans_sword` (9, 宝剑)。
        - 数字标签：`rm_define.marker_number_zero` (10) 到 `rm_define.marker_number_nine` (19)。
        - 字母标签：`rm_define.marker_letter_A` (20) 到 `rm_define.marker_letter_Z` (45)。
- `vision_ctrl.get_line_detection_info()`：
    - 返回 `[count, info, x1, y1, w1, h1, ...]`，返回连续轨迹离散采样点信息。
- `vision_ctrl.get_car_detection_info()`：
    - 返回 `[count, x1, y1, w1, h1, ...]`。
- `vision_ctrl.get_env_brightness()`：
    - 返回当前视野环境测光平均亮度整数（0..255）。

### 自瞄与巡线控制

- `vision_ctrl.detect_marker_and_aim(marker_id)`：自动驱动云台对准指定 ID（0..45）的视觉标签。
- `vision_ctrl.start_line_follow_until_exception()`：启动底盘自主巡线，直到脱轨或遇断线异常。
- `vision_ctrl.stop_line_follow()`：停止自主巡线。
- `vision_ctrl.get_line_detection_deviation()`：获取当前巡线偏差量（供闭环 PID 控制使用）。
- `vision_ctrl.stop()`：关闭所有视觉检测算法。

### 条件判定与等待

- `vision_ctrl.check_condition(cond_enum)` / `cond_wait(cond_enum)`：
    - 行人：`rm_define.cond_recognized_people`。
    - 手势：`rm_define.cond_recognized_pose_victory`、`cond_recognized_pose_give_in`、
      `cond_recognized_pose_capture`、`cond_recognized_pose_left_hand_up`、
      `cond_recognized_pose_right_hand_up`。
    - 机器人：`rm_define.cond_recognized_car`。
    - 标签：`rm_define.cond_recognized_marker_trans_*`、`rm_define.cond_recognized_marker_number_*`、
      `rm_define.cond_recognized_marker_letter_*`。

```python
# 示例：检测红心视觉标签并播放成功音效
def start():
    vision_ctrl.enable_detection(rm_define.vision_detection_marker)
    vision_ctrl.set_marker_detection_distance(2)
    try:
        for _ in range(20):
            info = vision_ctrl.get_marker_detection_info()
            if info[0] > 0 and info[1] == rm_define.marker_trans_red_heart:
                log_ctrl.print_msg("识别到红心标签！")
                media_ctrl.play_sound(rm_define.media_sound_recognize_success)
                break
            time.sleep(0.5)
    finally:
        vision_ctrl.disable_detection(rm_define.vision_detection_marker)
```

---

## 3. 拍手声音识别与相机控制 (`media_ctrl`)

### 拍手声音识别

- `media_ctrl.enable_sound_recognition(rm_define.sound_detection_applause)`：开启拍手识别。
- `media_ctrl.disable_sound_recognition(rm_define.sound_detection_applause)`：关闭拍手识别。
- `media_ctrl.disable_all_sound_recognition()`：关闭所有声音识别。
- `media_ctrl.check_condition(cond_enum)` / `media_ctrl.cond_wait(cond_enum)`：
    - `rm_define.cond_sound_recognized_applause_twice`：检测是否双击掌。
    - `rm_define.cond_sound_recognized_applause_thrice`：检测是否三击掌。

### 相机拍摄与硬件参数

- `media_ctrl.exposure_value_update(ev)`：设置曝光补偿档位，`ev` 可选：
  `rm_define.exposure_value_default`、`rm_define.exposure_value_small`、
  `rm_define.exposure_value_medium`、`rm_define.exposure_value_large`。
- `media_ctrl.zoom_value_update(zv)`：数码变焦倍数，`zv` 整数范围 1..4。
- `media_ctrl.get_camera_brightness()`：获取相机画面当前亮度。
- `media_ctrl.take_photos()`：触发相机单张拍照并落机内存储。
- `media_ctrl.start_recording()`：开始录制视频。
- `media_ctrl.stop_recording()`：停止录制视频。

```python
# 示例：双击掌触发拍照
def start():
    media_ctrl.enable_sound_recognition(rm_define.sound_detection_applause)
    try:
        log_ctrl.print_msg("请拍手两下拍照...")
        media_ctrl.cond_wait(rm_define.cond_sound_recognized_applause_twice)
        log_ctrl.print_msg("收到击掌，正在拍照！")
        media_ctrl.play_sound(rm_define.media_sound_shoot)
        media_ctrl.take_photos()
    finally:
        media_ctrl.disable_sound_recognition(rm_define.sound_detection_applause)
```

---

## 4. 移动端传感器 (`mobile_ctrl`)

在移动设备（手机/平板/电脑）通过 App 或 Hanppie 与机器人建立会话期间，可读取移动设备本身的传感器姿态数据：

- `mobile_ctrl.get_attitude(axis)`：获取移动设备姿态角（度），`axis` 可选 `rm_define.mobile_atti_pitch`、
  `rm_define.mobile_atti_roll`、`rm_define.mobile_atti_yaw`。
- `mobile_ctrl.get_accel(axis)`：获取移动设备加速度，`axis` 可选 `rm_define.mobile_info_accel_x`、
  `rm_define.mobile_info_accel_y`、`rm_define.mobile_info_accel_z`。
- `mobile_ctrl.get_gyro(axis)`：获取移动设备陀螺仪角速度，`axis` 可选 `rm_define.mobile_info_gyro_x`、
  `rm_define.mobile_info_gyro_y`、`rm_define.mobile_info_gyro_z`。

---

## 5. 闭环控制算法 (`rm_ctrl.PIDCtrl`)

用于在连续追踪控制（如目标对准、高精巡线、自适应避障）中执行闭环调节：

- `pid = rm_ctrl.PIDCtrl()`：实例化 PID 控制器。
- `pid.set_ctrl_params(kp, ki, kd)`：配置比例、积分、微分增益参数。
- `pid.set_error(error)`：输入当前传感器与目标的误差量。
- `pid.get_output()`：获取经内部 PID 计算后的控制输出值（如云台偏航速度）。

```python
# 示例：视觉标签闭环水平追踪
def start():
    pid_yaw = rm_ctrl.PIDCtrl()
    pid_yaw.set_ctrl_params(120, 0, 15)
    vision_ctrl.enable_detection(rm_define.vision_detection_marker)
    try:
        for _ in range(50):
            info = vision_ctrl.get_marker_detection_info()
            if info[0] > 0:
                # 目标中心 x 坐标范围 0..1，中心点为 0.5，计算偏离误差
                target_x = info[2]
                error = target_x - 0.5
                pid_yaw.set_error(error)
                gimbal_ctrl.rotate_with_speed(pid_yaw.get_output(), 0)
            else:
                gimbal_ctrl.rotate_with_speed(0, 0)
            time.sleep(0.05)
    finally:
        gimbal_ctrl.stop()
        vision_ctrl.disable_detection(rm_define.vision_detection_marker)
```

---

## 6. Scratch 兼容辅助工具 (`rm_builtins`)

- `rmround(value, pre=0)`：对浮点数保留 `pre` 位小数进行精确四舍五入。
- `rmexit()`：主动退出脚本执行（机内抛出终止异常），由调度器安全捕获退出。
- `RmList(initlist=None)`：Scratch 适配列表类，**索引从 1 开始**（`list[1]` 取第一项，`index()` 返回从 1
  起始的索引序号）。
