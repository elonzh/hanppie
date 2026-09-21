REPEAT_COUNT = 3
BEAT = 0.28
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
        log_ctrl.print_msg("小店开门，欢迎光临")
        for note in (0, 2, 4):
            led_ctrl.set_led(rm_define.armor_all, 255, 185, 40, rm_define.effect_always_on)
            media_ctrl.play_sound(NOTES[note])
            time.sleep(BEAT)

        for visitor in range(REPEAT_COUNT):
            log_ctrl.print_msg("招手迎接第 %s 位客人" % (visitor + 1))
            led_ctrl.set_led(rm_define.armor_all, 255, 185, 40, rm_define.effect_breath)
            gimbal_ctrl.rotate_with_degree(rm_define.gimbal_right, 20)
            gimbal_ctrl.rotate_with_degree(rm_define.gimbal_left, 40)
            gimbal_ctrl.rotate_with_degree(rm_define.gimbal_right, 20)
            gimbal_ctrl.rotate_with_degree(rm_define.gimbal_down, 8)
            gimbal_ctrl.rotate_with_degree(rm_define.gimbal_up, 8)
            for note in (4, 7):
                led_ctrl.set_led(rm_define.armor_all, 255, 220, 130, rm_define.effect_always_on)
                media_ctrl.play_sound(NOTES[note])
                time.sleep(BEAT)

        log_ctrl.print_msg("谢谢惠顾")
    finally:
        gimbal_ctrl.stop()
        led_ctrl.set_led(rm_define.armor_all, 0, 0, 0, rm_define.effect_always_off)
