REPEAT_COUNT = 2
NOTES = (
    rm_define.media_sound_solmization_1C,
    rm_define.media_sound_solmization_1D,
    rm_define.media_sound_solmization_1E,
    rm_define.media_sound_solmization_1F,
    rm_define.media_sound_solmization_1G,
    rm_define.media_sound_solmization_1A,
    rm_define.media_sound_solmization_1B,
    rm_define.media_sound_solmization_2C,
)


def start():
    try:
        robot_ctrl.set_mode(rm_define.robot_mode_free)
        gimbal_ctrl.set_rotate_speed(30)
        gimbal_ctrl.recenter()
        for crossing in range(REPEAT_COUNT):
            log_ctrl.print_msg("红灯：请在停止线后等待")
            led_ctrl.set_led(rm_define.armor_all, 255, 30, 20, rm_define.effect_always_on)
            time.sleep(1)

            log_ctrl.print_msg("左右观察，确认模拟路口无车")
            gimbal_ctrl.rotate_with_degree(rm_define.gimbal_left, 30)
            gimbal_ctrl.rotate_with_degree(rm_define.gimbal_right, 60)
            gimbal_ctrl.rotate_with_degree(rm_define.gimbal_left, 30)

            log_ctrl.print_msg("绿灯：行人通行")
            for pedestrian in range(4):
                led_ctrl.set_led(rm_define.armor_all, 30, 220, 80, rm_define.effect_always_on)
                media_ctrl.play_sound(NOTES[4])
                time.sleep(0.5)

            log_ctrl.print_msg("黄灯：本轮通行结束")
            for warning in range(3):
                led_ctrl.set_led(rm_define.armor_all, 255, 160, 20, rm_define.effect_always_on)
                time.sleep(0.25)
                led_ctrl.set_led(rm_define.armor_all, 0, 0, 0, rm_define.effect_always_off)
                time.sleep(0.25)

        log_ctrl.print_msg("执勤结束")
    finally:
        gimbal_ctrl.stop()
        led_ctrl.set_led(rm_define.armor_all, 0, 0, 0, rm_define.effect_always_off)
