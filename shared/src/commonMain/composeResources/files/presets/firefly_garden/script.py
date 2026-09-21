REPEAT_COUNT = 3
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
        log_ctrl.print_msg("黄昏，花园渐渐安静")
        for level in range(8, 0, -1):
            led_ctrl.set_led(rm_define.armor_all, level * 20, level * 8, level * 3, rm_define.effect_always_on)
            time.sleep(0.2)

        for meeting in range(REPEAT_COUNT):
            log_ctrl.print_msg("萤火虫的问答 %s" % (meeting + 1))
            for note, tint in ((4, (150, 230, 30)), (7, (30, 200, 140))):
                media_ctrl.play_sound(NOTES[note])
                for level in (1, 2, 4, 6, 8, 6, 4, 2, 1, 0):
                    r = tint[0] * level // 8
                    g = tint[1] * level // 8
                    b = tint[2] * level // 8
                    led_ctrl.set_led(rm_define.armor_all, r, g, b, rm_define.effect_always_on)
                    time.sleep(0.12)

        log_ctrl.print_msg("晚安，明天见")
        time.sleep(1)
    finally:
        led_ctrl.set_led(rm_define.armor_all, 0, 0, 0, rm_define.effect_always_off)
