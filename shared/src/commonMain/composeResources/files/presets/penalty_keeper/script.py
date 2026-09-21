REPEAT_COUNT = 2
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
        chassis_ctrl.set_trans_speed(0.15)
        chassis_ctrl.set_rotate_speed(30)
        log_ctrl.print_msg("点球大战，门将就位；来球由剧情模拟")
        for note in (0, 0, 4):
            led_ctrl.set_led(rm_define.armor_all, 60, 150, 255, rm_define.effect_always_on)
            media_ctrl.play_sound(NOTES[note])
            time.sleep(BEAT)

        for shot in range(REPEAT_COUNT):
            log_ctrl.print_msg("盯住来球……")
            led_ctrl.set_led(rm_define.armor_all, 255, 170, 30, rm_define.effect_breath)
            time.sleep(1)
            if shot == 0:
                log_ctrl.print_msg("判断错了！赶快回扑")
                chassis_ctrl.move_with_time(90, 0.4)
                chassis_ctrl.move_with_time(-90, 0.8)
                chassis_ctrl.move_with_time(90, 0.4)
            else:
                log_ctrl.print_msg("扑向左侧，稳稳接住")
                chassis_ctrl.move_with_time(-90, 0.6)
                for note in (0, 4, 7):
                    led_ctrl.set_led(rm_define.armor_all, 30, 230, 90, rm_define.effect_always_on)
                    media_ctrl.play_sound(NOTES[note])
                    time.sleep(BEAT)
                chassis_ctrl.move_with_time(90, 0.6)

        log_ctrl.print_msg("比赛结束，守门成功")
        for note in (7, 4, 7):
            led_ctrl.set_led(rm_define.armor_all, 30, 230, 90, rm_define.effect_always_on)
            media_ctrl.play_sound(NOTES[note])
            time.sleep(BEAT)
    finally:
        chassis_ctrl.stop()
        led_ctrl.set_led(rm_define.armor_all, 0, 0, 0, rm_define.effect_always_off)
