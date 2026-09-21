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
        log_ctrl.print_msg("月光亮起，舞曲开始")
        for dance in range(REPEAT_COUNT):
            for direction, notes in ((90, (0, 2, 4)), (-90, (1, 3, 5))):
                led_ctrl.set_led(rm_define.armor_all, 100, 70, 240, rm_define.effect_breath)
                for note in notes:
                    media_ctrl.play_sound(NOTES[note])
                    chassis_ctrl.move_with_time(direction, BEAT)

            log_ctrl.print_msg("转身，向舞伴致意")
            chassis_ctrl.rotate_with_degree(rm_define.clockwise, 25)
            for note in (7, 5, 4):
                led_ctrl.set_led(rm_define.armor_all, 180, 130, 255, rm_define.effect_always_on)
                media_ctrl.play_sound(NOTES[note])
                time.sleep(BEAT)
            chassis_ctrl.rotate_with_degree(rm_define.anticlockwise, 25)

        log_ctrl.print_msg("舞曲终了")
        for note in (2, 1, 0):
            led_ctrl.set_led(rm_define.armor_all, 70, 120, 210, rm_define.effect_always_on)
            media_ctrl.play_sound(NOTES[note])
            time.sleep(BEAT)
    finally:
        chassis_ctrl.stop()
        led_ctrl.set_led(rm_define.armor_all, 0, 0, 0, rm_define.effect_always_off)
