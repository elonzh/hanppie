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
        chassis_ctrl.set_trans_speed(0.15)
        chassis_ctrl.set_rotate_speed(30)
        log_ctrl.print_msg("各位乘客请坐好，小火车出发")
        for note in (0, 4, 7):
            led_ctrl.set_led(rm_define.armor_all, 255, 190, 40, rm_define.effect_always_on)
            media_ctrl.play_sound(NOTES[note])
            time.sleep(BEAT)

        stations = (
            ("森林站", (50, 200, 80)),
            ("海边站", (30, 150, 255)),
            ("星空站", (150, 80, 255)),
            ("家乡站", (255, 150, 50)),
        )
        for station, color in stations:
            log_ctrl.print_msg("下一站：" + station)
            for sleeper in range(REPEAT_COUNT):
                led_ctrl.set_led(rm_define.armor_all, color[0], color[1], color[2], rm_define.effect_always_on)
                media_ctrl.play_sound(NOTES[sleeper % 2])
                chassis_ctrl.move_with_time(0, BEAT)

            log_ctrl.print_msg("到站停靠：" + station)
            for note in (4, 2):
                led_ctrl.set_led(rm_define.armor_all, color[0], color[1], color[2], rm_define.effect_always_on)
                media_ctrl.play_sound(NOTES[note])
                time.sleep(BEAT)
            time.sleep(0.5)
            chassis_ctrl.rotate_with_degree(rm_define.clockwise, 90)

        log_ctrl.print_msg("环游结束，请带好随身物品")
    finally:
        chassis_ctrl.stop()
        led_ctrl.set_led(rm_define.armor_all, 0, 0, 0, rm_define.effect_always_off)
