REPEAT_COUNT = 2
BEAT = 0.28
NOTES = (rm_define.media_sound_solmization_1C, rm_define.media_sound_solmization_1D,
         rm_define.media_sound_solmization_1E, rm_define.media_sound_solmization_1F,
         rm_define.media_sound_solmization_1G, rm_define.media_sound_solmization_1A,
         rm_define.media_sound_solmization_1B, rm_define.media_sound_solmization_2C)

def stage(message):
    log_ctrl.print_msg(message)

def glow(color, effect=rm_define.effect_always_on):
    led_ctrl.set_led(rm_define.armor_all, color[0], color[1], color[2], effect)

def phrase(notes, color):
    for note in notes:
        glow(color)
        media_ctrl.play_sound(NOTES[note])
        time.sleep(BEAT)

def start():
    try:
        robot_ctrl.set_mode(rm_define.robot_mode_free)
        gimbal_ctrl.set_rotate_speed(30)
        gimbal_ctrl.recenter()
        for crossing in range(REPEAT_COUNT):
            stage("红灯：请在停止线后等待")
            glow((255, 30, 20))
            time.sleep(1)
            stage("左右观察，确认模拟路口无车")
            gimbal_ctrl.rotate_with_degree(rm_define.gimbal_left, 30)
            gimbal_ctrl.rotate_with_degree(rm_define.gimbal_right, 60)
            gimbal_ctrl.rotate_with_degree(rm_define.gimbal_left, 30)
            stage("绿灯：行人通行")
            for pedestrian in range(4):
                glow((30, 220, 80))
                media_ctrl.play_sound(NOTES[4])
                time.sleep(0.5)
            stage("黄灯：本轮通行结束")
            for warning in range(3):
                glow((255, 160, 20))
                time.sleep(0.25)
                glow((0, 0, 0))
                time.sleep(0.25)
        stage("执勤结束")
    finally:
        gimbal_ctrl.stop()
        glow((0, 0, 0), rm_define.effect_always_off)
