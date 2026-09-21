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
        for remaining in range(5, 0, -1):
            log_ctrl.print_msg("发射倒计时 %s" % remaining)
            led_ctrl.set_led(rm_define.armor_all, 255, 35, 30, rm_define.effect_always_on)
            media_ctrl.play_sound(NOTES[0])
            time.sleep(0.6)

        log_ctrl.print_msg("点火，升空！")
        for launch in range(REPEAT_COUNT):
            for note in (0, 1, 2, 3, 4, 5, 6, 7):
                led_ctrl.set_led(rm_define.armor_all, 255, 100, 15, rm_define.effect_always_on)
                media_ctrl.play_sound(NOTES[note])
                time.sleep(BEAT)

        log_ctrl.print_msg("进入轨道，太阳能板展开")
        led_ctrl.set_led(rm_define.armor_all, 20, 90, 255, rm_define.effect_breath)
        time.sleep(2)
        for note in (7, 4, 2, 7):
            led_ctrl.set_led(rm_define.armor_all, 180, 210, 255, rm_define.effect_always_on)
            media_ctrl.play_sound(NOTES[note])
            time.sleep(BEAT)
    finally:
        led_ctrl.set_led(rm_define.armor_all, 0, 0, 0, rm_define.effect_always_off)
