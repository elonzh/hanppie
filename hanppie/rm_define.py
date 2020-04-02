# event client
system_host_id = 0x905
script_host_id = 0x906

# duss_result
DUSS_SUCCESS = 0  # Success return
DUSS_ERR_FAILURE = -1001  # Fail for common reason
DUSS_ERR_TIMEOUT = -1002  # Fail for waiting timeout
DUSS_ERR_PARAM = -1003  # Fail for function param invalid
DUSS_ERR_STATUS = -1004  # Fail for module status invalid
DUSS_ERR_BUSY = -1005  # Fail for module busy
DUSS_ERR_NO_MEM = -1006  # Fail for no enough memory
DUSS_ERR_NO_RSRC = -1007  # Fail for no enough resource
DUSS_ERR_HAREWARE = -1008  # Fail for hardware error
DUSS_ERR_NOFEATURE = -1009  # Fail for feature not support
DUSS_ERR_MISSMATCH = -1010  # Fail for mismatch operations
DUSS_ERR_DATA = -1011  # Fail for invalid data
DUSS_ERR_TRY_LATER = -1012  # try later for resource busy
DUSS_ERR_NOT_IMPLMT = -1013  # The function is not implemented yet
DUSS_ERR_CACELLED = -1014  # Fail due to cancelled by user
DUSS_ERR_NOT_SUPPORT = -1015  # Current Mode is not support

DUSS_TASK_REJECTED = -1101  # task reject by client.
DUSS_TASK_TIMEOUT = -1102  # task timeout
DUSS_TASK_FINISHED = -1103  # task already finished.
DUSS_TASK_INTERRUPT = -1104  # task interrupted by event.

# running error code
BLOCK_RUN_SUCCESS = 0x00
BLOCK_ERR_TIMEOUT = 0x11
BLOCK_ERR_TANKMODE_NOT_SUPPORT = 0x21
BLOCK_ERR_VALUE_TYPE = 0x31
BLOCK_ERR_VALUE_RANGE = 0x32
BLOCK_ERR_NO_SDCARD = 0x41
BLOCK_ERR_FULL_SDCARD = 0x42
BLOCK_ERR_STORAGE_SDCARD = 0x43
BLOCK_ERR_AI_REFUSED = 0x45
BLOCK_ERR_APP_NOT_SUPPORT = 0x46
BLOCK_ERR_TASK_REJECTED = 0x51
BLOCK_ERR_TASK_INTERRUPT = 0x52
BLOCK_ERR_TASK_TIMEOUT = 0x53
BLOCK_ERR_MODULE_INVALID = 0x61
BLOCK_ERR_MODULE_OFFLINE = 0x62

FAT_LIST_OUT_OF_RANGE = 0xE1
FAT_DIV_ZERO = 0xE2
FAT_LIST_FIND_FAILUE = 0xE3
FAT_OTHER = 0xE4
FAT_STACK_OVERFLOW = 0xE5
FAT_DEVICE_NOT_SUPPORT = 0xF1
FAT_UNKNOW = 0xFF

TASK = "task"
NO_TASK = "no_task"

SUCCESS = True
FAILURE = False

# task
task_start = 0
task_cancel = 1
task_fre_1Hz = 0
task_fre_5Hz = 1
task_fre_10Hz = 2

# robot mode
robot_mode_free = 0
robot_mode_chassis_follow = 1
robot_mode_gimbal_follow = 2

# chassis
speed_base_default = 0.5
speed_yaw_default = 30
angle_follow_default = 0
axis_mode_default = 0
time_forever = -1

# define chassis direction
chassis_front = 1
chassis_back = 2
chassis_right = 3
chassis_left = 4
chassis_left_front = 7
chassis_right_front = 8
chassis_left_back = 9
chassis_right_back = 10
chassis_customize_direction = 11
chassis_pos_spd_bagid = 3

# define pwm comp
pwm1 = 0x1
pwm2 = 0x2
pwm3 = 0x4
pwm4 = 0x8
pwm5 = 0x10
pwm6 = 0x20
pwm_all = 0x3F

stick_overlay_and_axes_enable = 2
stick_overlay_enable = 1
stick_overlay_disable = 0

# define clock direction
clockwise = 6
anticlockwise = 5

speed_max = 3.5
speed_min = -3.5

speed_yaw_max = 600
speed_yaw_min = -600

speed_wheel_max = 1000
speed_wheel_min = -1000

move_distance_max = 5
move_distance_min = -5

rotate_degree_max = 1800
rotate_degree_min = -1800

chassis_fpv_mode = 0  # work mode 1
chassis_sdk_mode = 1
chassis_chassis_only_mode = 2
chassis_goggles_mode = 3

chassis_sdk_free_mode = 12  # work mode 2
chassis_sdk_follow_mode = 13  # work mode 3

# get
chassis_wheel_1 = 1
chassis_wheel_2 = 2
chassis_wheel_3 = 3
chassis_wheel_4 = 4

chassis_forward = 1
chassis_translation = 2
chassis_rotate = 3

chassis_pitch = 1
chassis_roll = 2
chassis_yaw = 3

# gimbal
gimbal_free_mode = 0
gimbal_fpv_mode = 1
gimbal_yaw_follow_mode = 2

gimbal_suspend = 0
gimbal_resume = 1

gimbal_up = 1
gimbal_down = 2
gimbal_right = 3
gimbal_left = 4

gimbal_deviation = 0

gimbal_rotate_speed_max = 540
gimbal_rotate_speed_min = 0

gimbal_pitch_accel_min = -5400
gimbal_pitch_accel_max = 5400
gimbal_yaw_accel_min = -5400
gimbal_yaw_accel_max = 5400

gimbal_pitch_degree_max = 35
gimbal_pitch_degree_min = -20
gimbal_yaw_degree_max = 250
gimbal_yaw_degree_min = -250

gimbal_pitch_degree_ctrl_max = 55
gimbal_pitch_degree_ctrl_min = -55
gimbal_yaw_degree_ctrl_max = 500
gimbal_yaw_degree_ctrl_min = -500

gimbal_coodrdinate_ned = 0x00
gimbal_coodrdinate_cur = 0x01
gimbal_coodrdinate_car = 0x02  # pitch offset
gimbal_coodrdinate_3 = 0x03  # pitch ned
gimbal_coodrdinate_4 = 0x04  # yaw car, pitch ned
gimbal_coodrdinate_5 = 0x05  # yaw car, pitch offset

gimbal_axis_pitch = 0
gimbal_axis_yaw = 1

gimbal_axis_yaw_maskbit = 0x01
gimbal_axis_roll_maskbit = 0x02
gimbal_axis_pitch_maskbit = 0x04
gimbal_axis_pitch_yaw_maskbit = 0x05

gimbal_compound_motion_enable = 1
gimbal_compound_motion_disable = 0
gimbal_compound_motion_margin_min = 0
gimbal_compound_motion_margin_max = 2700

# gun
max_fire_count = 8
min_fire_count = 0
single_fire_mode = 1
multip_fire_mode = 2

# armor id
armor_bottom_back = 0x1
armor_bottom_front = 0x2
armor_bottom_left = 0x4
armor_bottom_right = 0x8
armor_top_left = 0x10
armor_top_right = 0x20
armor_top_all = 0x30
armor_bottom_all = 0xF
armor_all = 0x3F

cond_armor_bottom_front_hit = "armor_bottom_front"
cond_armor_bottom_back_hit = "armor_bottom_back"
cond_armor_bottom_left_hit = "armor_bottom_left"
cond_armor_bottom_right_hit = "armor_bottom_right"
cond_armor_top_left_hit = "armor_top_left"
cond_armor_top_right_hit = "armor_top_right"
cond_armor_hit = "armor_all"
cond_ir_top_left_hit = "ir_top_left"
cond_ir_top_right_hit = "ir_top_right"
cond_ir_hit_detection = "ir_all"

# led effect
effect_always_on = 0
effect_always_off = 1
effect_breath = 2
effect_flash = 3
effect_marquee = 4

armor_top_led_index_max = 8
armor_top_led_index_min = 1

# vision define
vision_detection_head_shoulder = 0x1
vision_detection_people = 0x2
vision_detection_pose = 0x4
vision_detection_auto_aim = 0x8
vision_detection_line = 0x10
vision_detection_marker = 0x20
vision_detection_people_follow = 0x40
vision_detection_car = 0x80

vision_detection_head_shoulder_type = 0
vision_detection_people_type = 1
vision_detection_pose_type = 2
vision_detection_auto_aim_type = 3
vision_detection_line_type = 4
vision_detection_marker_type = 5
vision_detection_people_follow_type = 6
vision_detection_car_type = 7

# vision priority
vision_core_num_10 = 10
vision_priority_auto_aim = 1

vision_core_num_8 = 8
vision_priority_people = 1
vision_priority_pose = 1
vision_priority_head_shoulder = 2
vision_priority_marker = 3

vision_core_num_4 = 4
vision_priority_line = 1
vision_priority_people_follow = 2
vision_priority_car = 3

vision_priority_highest = 0
vision_priority_lowest = 255

# index
detection_all_default = 0
people = 0
head_shoulder = 0
line = 0

pose_all = 0
pose_jump = 1
pose_left_hand_up = 2
pose_right_hand_up = 3
pose_victory = 4
pose_give_in = 5
pose_capture = 6
pose_left_hand_wave = 7
pose_right_hand_wave = 8
pose_idle = 9

marker_all_with_follow_line = -1
marker_trans_all = -2
marker_number_all = -3
marker_letter_all = -4
marker_all = 0
marker_trans_stop = 1
marker_trans_dice = 2
marker_trans_target = 3
marker_trans_left = 4
marker_trans_right = 5
marker_trans_forward = 6
marker_trans_backward = 7
marker_trans_red_heart = 8
marker_trans_sword = 9
marker_number_zero = 10
marker_number_one = 11
marker_number_two = 12
marker_number_three = 13
marker_number_four = 14
marker_number_five = 15
marker_number_six = 16
marker_number_seven = 17
marker_number_eight = 18
marker_number_nine = 19
marker_letter_A = 20
marker_letter_B = 21
marker_letter_C = 22
marker_letter_D = 23
marker_letter_E = 24
marker_letter_F = 25
marker_letter_G = 26
marker_letter_H = 27
marker_letter_I = 28
marker_letter_J = 29
marker_letter_K = 30
marker_letter_L = 31
marker_letter_M = 32
marker_letter_N = 33
marker_letter_O = 34
marker_letter_P = 35
marker_letter_Q = 36
marker_letter_R = 37
marker_letter_S = 38
marker_letter_T = 39
marker_letter_U = 40
marker_letter_V = 41
marker_letter_W = 42
marker_letter_X = 43
marker_letter_Y = 44
marker_letter_Z = 45

cond_recognized_people = "vision_recognized_people"
cond_recognized_head_shoulder = "vision_recognized_head_shoulder"
cond_recognized_pose_victory = "vision_recognized_pose_victory"
cond_recognized_pose_give_in = "vision_recognized_pose_give_in"
cond_recognized_pose_capture = "vision_recognized_pose_capture"
cond_recognized_pose_left_hand_up = "vision_recognized_pose_left_hand_up"
cond_recognized_pose_right_hand_up = "vision_recognized_pose_right_hand_up"
cond_recognized_pose_all = "vision_recognized_pose_all"
cond_recognized_car = "vision_recognized_car"
cond_recognized_marker_trans_red = "vision_recognized_marker_trans_red"
cond_recognized_marker_trans_yellow = "vision_recognized_marker_trans_yellow"
cond_recognized_marker_trans_green = "vision_recognized_marker_trans_green"
cond_recognized_marker_trans_left = "vision_recognized_marker_trans_left"
cond_recognized_marker_trans_right = "vision_recognized_marker_trans_right"
cond_recognized_marker_trans_forward = "vision_recognized_marker_trans_forward"
cond_recognized_marker_trans_backward = "vision_recognized_marker_trans_backward"
cond_recognized_marker_trans_stop = "vision_recognized_marker_trans_stop"
cond_recognized_marker_trans_red_heart = "vision_recognized_marker_trans_red_heart"
cond_recognized_marker_trans_sword = "vision_recognized_marker_trans_sword"
cond_recognized_marker_trans_target = "vision_recognized_marker_trans_target"
cond_recognized_marker_trans_dice = "vision_recognized_marker_trans_dice"
cond_recognized_marker_trans_all = "vision_recognized_marker_trans_all"
cond_recognized_marker_number_zero = "vision_recognized_marker_number_zero"
cond_recognized_marker_number_one = "vision_recognized_marker_number_one"
cond_recognized_marker_number_two = "vision_recognized_marker_number_two"
cond_recognized_marker_number_three = "vision_recognized_marker_number_three"
cond_recognized_marker_number_four = "vision_recognized_marker_number_four"
cond_recognized_marker_number_five = "vision_recognized_marker_number_five"
cond_recognized_marker_number_six = "vision_recognized_marker_number_six"
cond_recognized_marker_number_seven = "vision_recognized_marker_number_seven"
cond_recognized_marker_number_eight = "vision_recognized_marker_number_eight"
cond_recognized_marker_number_nine = "vision_recognized_marker_number_nine"
cond_recognized_marker_number_all = "vision_recognized_marker_number_all"
cond_recognized_marker_letter_A = "vision_recognized_marker_letter_A"
cond_recognized_marker_letter_B = "vision_recognized_marker_letter_B"
cond_recognized_marker_letter_C = "vision_recognized_marker_letter_C"
cond_recognized_marker_letter_D = "vision_recognized_marker_letter_D"
cond_recognized_marker_letter_E = "vision_recognized_marker_letter_E"
cond_recognized_marker_letter_F = "vision_recognized_marker_letter_F"
cond_recognized_marker_letter_G = "vision_recognized_marker_letter_G"
cond_recognized_marker_letter_H = "vision_recognized_marker_letter_H"
cond_recognized_marker_letter_I = "vision_recognized_marker_letter_I"
cond_recognized_marker_letter_J = "vision_recognized_marker_letter_J"
cond_recognized_marker_letter_K = "vision_recognized_marker_letter_K"
cond_recognized_marker_letter_L = "vision_recognized_marker_letter_L"
cond_recognized_marker_letter_M = "vision_recognized_marker_letter_M"
cond_recognized_marker_letter_N = "vision_recognized_marker_letter_N"
cond_recognized_marker_letter_O = "vision_recognized_marker_letter_O"
cond_recognized_marker_letter_P = "vision_recognized_marker_letter_P"
cond_recognized_marker_letter_Q = "vision_recognized_marker_letter_Q"
cond_recognized_marker_letter_R = "vision_recognized_marker_letter_R"
cond_recognized_marker_letter_S = "vision_recognized_marker_letter_S"
cond_recognized_marker_letter_T = "vision_recognized_marker_letter_T"
cond_recognized_marker_letter_U = "vision_recognized_marker_letter_U"
cond_recognized_marker_letter_V = "vision_recognized_marker_letter_V"
cond_recognized_marker_letter_W = "vision_recognized_marker_letter_W"
cond_recognized_marker_letter_X = "vision_recognized_marker_letter_X"
cond_recognized_marker_letter_Y = "vision_recognized_marker_letter_Y"
cond_recognized_marker_letter_Z = "vision_recognized_marker_letter_Z"
cond_recognized_marker_letter_all = "vision_recognized_marker_letter_all"
cond_recongized_marker_all = "vision_recongnized_marker_all"

detection_push_status_exit = 0
detection_push_status_init = 1
detection_push_status_wait_confirm = 2
detection_push_status_running = 3

line_detection_any_point = 0
line_detection_near_point = 1
line_detection_middle_point = 2
line_detection_far_point = 3

line_intersection_end = -1
line_intersection_all = -2
line_intersection_Y = -3
line_intersection_X = -4

line_there = True
line_lost = False

line_follow_chassis_front = 1
line_follow_chassis_left = 2
line_follow_chassis_right = 3
line_follow_chassis_turn_around = 4
line_follow_chassis_stop = 5

line_follow_color_red = 1
line_follow_color_yellow = 2
line_follow_color_blue = 3
line_follow_color_green = 4

marker_detection_color_red = 1
marker_detection_color_yellow = 2
marker_detection_color_blue = 3
marker_detection_color_green = 4

line_follow_front_speed_default = 0.8  # m/s
line_follow_line_lost_distance_default = 0.1  # m

# state push type
state_gimbal_yaw_str = "gimbal_yaw"
state_gimbal_pitch_str = "gimbal_pitch"
state_battery_str = "battery"
state_move_speed_str = "move_speed"
state_chassis_pitch_str = "chassis_pitch"
state_chassis_roll_str = "chassis_roll"
state_chassis_yaw_str = "chassis_yaw"

# media

sound_detection_default = 0
sound_detection_chinese = 1
sound_detection_english = 2
sound_detection_applause = 3

sound_detection_default_type = 0
sound_detection_chinese_type = 1
sound_detection_english_type = 2
sound_detection_applause_type = 3

applause_all = 0
applause_once = 1
applause_twice = 2
applause_thrice = 3

cond_sound_recognized_applause_once = "sound_recognized_applause_once"
cond_sound_recognized_applause_twice = "sound_recognized_applause_twice"
cond_sound_recognized_applause_thrice = "sound_recognized_applause_thrice"

exposure_value_default = 0x00
exposure_value_large = 0x00
exposure_value_medium = 0x1C
exposure_value_small = 0x1D

zoom_value_min = 100
zoom_value_max = 400

cond_sensor_adapter1_port1_high_event = "sensor_adapter1_port1_high"
cond_sensor_adapter1_port1_low_event = "sensor_adapter1_port1_low"
cond_sensor_adapter1_port1_trigger_event = "sensor_adapter1_port1_trigger"
cond_sensor_adapter1_port2_high_event = "sensor_adapter1_port2_high"
cond_sensor_adapter1_port2_low_event = "sensor_adapter1_port2_low"
cond_sensor_adapter1_port2_trigger_event = "sensor_adapter1_port2_trigger"
cond_sensor_adapter2_port1_high_event = "sensor_adapter2_port1_high"
cond_sensor_adapter2_port1_low_event = "sensor_adapter2_port1_low"
cond_sensor_adapter2_port1_trigger_event = "sensor_adapter2_port1_trigger"
cond_sensor_adapter2_port2_high_event = "sensor_adapter2_port2_high"
cond_sensor_adapter2_port2_low_event = "sensor_adapter2_port2_low"
cond_sensor_adapter2_port2_trigger_event = "sensor_adapter2_port2_trigger"
cond_sensor_adapter3_port1_high_event = "sensor_adapter3_port1_high"
cond_sensor_adapter3_port1_low_event = "sensor_adapter3_port1_low"
cond_sensor_adapter3_port1_trigger_event = "sensor_adapter3_port1_trigger"
cond_sensor_adapter3_port2_high_event = "sensor_adapter3_port2_high"
cond_sensor_adapter3_port2_low_event = "sensor_adapter3_port2_low"
cond_sensor_adapter3_port2_trigger_event = "sensor_adapter3_port2_trigger"
cond_sensor_adapter4_port1_high_event = "sensor_adapter4_port1_high"
cond_sensor_adapter4_port1_low_event = "sensor_adapter4_port1_low"
cond_sensor_adapter4_port1_trigger_event = "sensor_adapter4_port1_trigger"
cond_sensor_adapter4_port2_high_event = "sensor_adapter4_port2_high"
cond_sensor_adapter4_port2_low_event = "sensor_adapter4_port2_low"
cond_sensor_adapter4_port2_trigger_event = "sensor_adapter4_port2_trigger"
cond_sensor_adapter5_port1_high_event = "sensor_adapter5_port1_high"
cond_sensor_adapter5_port1_low_event = "sensor_adapter5_port1_low"
cond_sensor_adapter5_port1_trigger_event = "sensor_adapter5_port1_trigger"
cond_sensor_adapter5_port2_high_event = "sensor_adapter5_port2_high"
cond_sensor_adapter5_port2_low_event = "sensor_adapter5_port2_low"
cond_sensor_adapter5_port2_trigger_event = "sensor_adapter5_port2_trigger"
cond_sensor_adapter6_port1_high_event = "sensor_adapter6_port1_high"
cond_sensor_adapter6_port1_low_event = "sensor_adapter6_port1_low"
cond_sensor_adapter6_port1_trigger_event = "sensor_adapter6_port1_trigger"
cond_sensor_adapter6_port2_high_event = "sensor_adapter6_port2_high"
cond_sensor_adapter6_port2_low_event = "sensor_adapter6_port2_low"
cond_sensor_adapter6_port2_trigger_event = "sensor_adapter6_port2_trigger"
cond_sensor_adapter7_port1_high_event = "sensor_adapter7_port1_high"
cond_sensor_adapter7_port1_low_event = "sensor_adapter7_port1_low"
cond_sensor_adapter7_port1_trigger_event = "sensor_adapter7_port1_trigger"
cond_sensor_adapter7_port2_high_event = "sensor_adapter7_port2_high"
cond_sensor_adapter7_port2_low_event = "sensor_adapter7_port2_low"
cond_sensor_adapter7_port2_trigger_event = "sensor_adapter7_port2_trigger"

# sound effect
media_sound_attacked = 0x101
media_sound_shoot = 0x102
media_sound_scanning = 0x103
media_sound_recognize_success = 0x104
media_sound_gimbal_rotate = 0x105
media_sound_count_down = 0x106

media_sound_solmization_1C = 0x107
media_sound_solmization_1CSharp = 0x108
media_sound_solmization_1D = 0x109
media_sound_solmization_1DSharp = 0x10A
media_sound_solmization_1E = 0x10B
media_sound_solmization_1F = 0x10C
media_sound_solmization_1FSharp = 0x10D
media_sound_solmization_1G = 0x10E
media_sound_solmization_1GSharp = 0x10F
media_sound_solmization_1A = 0x110
media_sound_solmization_1ASharp = 0x111
media_sound_solmization_1B = 0x112
media_sound_solmization_2C = 0x113
media_sound_solmization_2CSharp = 0x114
media_sound_solmization_2D = 0x115
media_sound_solmization_2DSharp = 0x116
media_sound_solmization_2E = 0x117
media_sound_solmization_2F = 0x118
media_sound_solmization_2FSharp = 0x119
media_sound_solmization_2G = 0x11A
media_sound_solmization_2GSharp = 0x11B
media_sound_solmization_2A = 0x11C
media_sound_solmization_2ASharp = 0x11D
media_sound_solmization_2B = 0x11E
media_sound_solmization_3C = 0x11F
media_sound_solmization_3CSharp = 0x120
media_sound_solmization_3D = 0x121
media_sound_solmization_3DSharp = 0x122
media_sound_solmization_3E = 0x123
media_sound_solmization_3F = 0x124
media_sound_solmization_3FSharp = 0x125
media_sound_solmization_3G = 0x126
media_sound_solmization_3GSharp = 0x127
media_sound_solmization_3A = 0x128
media_sound_solmization_3ASharp = 0x129
media_sound_solmization_3B = 0x12A

# mobile
custom_msg_max_len = 800
custom_msg_type_debug = 0
custom_msg_type_show = 1
custom_msg_level_none = 0
custom_msg_level_info = 1
custom_msg_level_debug = 2
custom_msg_level_error = 3
custom_msg_level_fatal = 4

# modue msg ctrl
module_msg_type_debug = 0x00
module_msg_type_info = 0x01
module_msg_type_warning = 0x02
module_msg_type_error = 0x03

module_status_online = 0xFF
module_status_offline = 0xFE
module_status_error = 0xFD

# local service
local_service_query_type_armor_hit = 1

mobile_info_accel_id = 0x01
mobile_info_atti_id = 0x02
mobile_info_gyro_id = 0x04
mobile_info_gps_id = 0x08

mobile_info_accel_type = 1
mobile_info_atti_type = 2
mobile_info_gyro_type = 3
mobile_info_gps_type = 4

mobile_accel_x = 1
mobile_accel_y = 2
mobile_accel_z = 3
mobile_gyro_x = 4
mobile_gyro_y = 5
mobile_gyro_z = 6
mobile_atti_pitch = 7
mobile_atti_roll = 8
mobile_atti_yaw = 9

# RobotTools
timer_start = 0
timer_stop = 1
timer_reset = 2

localtime_year = 0
localtime_month = 1
localtime_day = 2
localtime_hour = 3
localtime_minute = 4
localtime_second = 5

# Robotic Gripper
gripper_stop = 0
gripper_open = 1
gripper_close = 2

# Robotic Arm
opposite_move = 0
absolute_move = 1
mask_x = 0x01
mask_y = 0x02
mask_xy = 0x03


# msg_v1 attri

# TODO receiver_id use hex
camera_id = 100
mobile_id = 200
chassis_id = 306
gimbal_id = 400
gun_id = 2300
vision_id = 1707
battery_id = 1100
hdvt_uav_id = 900
system_id = 801
system_scratch_id = 803
scratch_sys_id = 905
scratch_script_id = 906
armor_id = 2400
armor1_id = 2401
armor2_id = 2402
armor3_id = 2403
armor4_id = 2404
armor5_id = 2405
armor6_id = 2406
esc0_id = 1200
esc1_id = 1201
esc2_id = 1202
esc3_id = 1203
blackbox_id = 2900
sensor_adapter_id = 2200
sensor_adapter1_id = 2201
sensor_adapter2_id = 2202
sensor_adapter3_id = 2203
sensor_adapter4_id = 2204
sensor_adapter5_id = 2205
sensor_adapter6_id = 2206
sensor_adapter7_id = 2207
tof_id = 1800
tof1_id = 1801
tof2_id = 1802
tof3_id = 1803
tof4_id = 1804
servo_id = 2500
servo1_id = 2501
servo2_id = 2502
servo3_id = 2503
servo4_id = 2504
robotic_gripper_id = 2701
robotic_arm_id = 2702

# cmd_type
req_pkg_type = 0x00
ack_pkg_type = 0x80
no_ack_type = 0x00
need_ack_type = 0x40
no_enc_type = 0x00
aes_128_enc_type = 0x01
customize_enc_type = 0x02
xor_enc_type = 0x03
des_56_enc_type = 0x04
des_112_enc_type = 0x05
aes_192_enc_type = 0x06
aes_256_enc_type = 0x07

## Pyhthon API ##
class robot_mode(object):
    gimbal_lead = robot_mode_chassis_follow
    chassis_lead = robot_mode_gimbal_follow
    free = robot_mode_free


class chassis_status(object):
    static = "static_flag"
    uphill = "uphill_flag"
    downhill = "downhill_flag"
    on_slope = "on_slope_flag"
    pick_up = "pick_up_flag"
    impact = "impact"
    bumpy = "imoact_z_flag"
    roll_over = "roll_over_flag"


class gimbal_status(object):
    wake = gimbal_resume
    sleep = gimbal_suspend


class detection_func(object):
    marker = vision_detection_marker
    line = vision_detection_line
    people = vision_detection_people
    pose = vision_detection_pose
    robot = vision_detection_car


class detection_type(object):
    marker = vision_detection_marker_type
    line = vision_detection_line_type
    people = vision_detection_people_type
    pose = vision_detection_pose_type
    robot = vision_detection_car_type


class led_effect(object):
    blink = effect_flash
    pulse = effect_breath
    scrolling = effect_marquee
    solid = effect_always_on
    off = effect_always_off


class led_position(object):
    top_left = armor_top_left
    top_right = armor_top_right
    bottom_front = armor_bottom_front
    bottom_back = armor_bottom_back
    bottom_left = armor_bottom_left
    bottom_right = armor_bottom_right


class pwm_port(object):
    pwm1 = pwm1
    pwm2 = pwm2
    pwm3 = pwm3
    pwm4 = pwm4
    pwm5 = pwm5
    pwm6 = pwm6
    pwm_all = pwm_all


class line_color(object):
    red = line_follow_color_red = 1
    yellow = line_follow_color_yellow = 2
    blue = line_follow_color_blue = 3


class sound_id(object):
    attacked = media_sound_attacked
    shoot = media_sound_shoot
    scanning = media_sound_scanning
    recognize_success = media_sound_recognize_success
    gimbal_rotate = media_sound_gimbal_rotate
    count_down = media_sound_count_down
