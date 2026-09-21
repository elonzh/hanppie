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
        log_ctrl.print_msg("深夜小摊，热锅开张")
        for heat in range(1, 9):
            led_ctrl.set_led(rm_define.armor_all, heat * 28, heat * 8, 0, rm_define.effect_always_on)
            time.sleep(0.2)

        for order in range(REPEAT_COUNT):
            log_ctrl.print_msg("新订单：切菜备料")
            for chop in range(6):
                media_ctrl.play_sound(NOTES[0 if chop % 2 == 0 else 2])
                time.sleep(0.18)

            log_ctrl.print_msg("大火翻炒，准备出锅")
            for toss in range(5):
                led_ctrl.set_led(rm_define.armor_all, 255, 70, 10, rm_define.effect_always_on)
                time.sleep(0.15)
                led_ctrl.set_led(rm_define.armor_all, 80, 15, 0, rm_define.effect_always_on)
                time.sleep(0.15)

            log_ctrl.print_msg("出餐，请慢用")
            for note in (4, 7):
                led_ctrl.set_led(rm_define.armor_all, 255, 210, 100, rm_define.effect_always_on)
                media_ctrl.play_sound(NOTES[note])
                time.sleep(BEAT)
            time.sleep(0.8)

        log_ctrl.print_msg("最后一位客人离开，收摊")
    finally:
        led_ctrl.set_led(rm_define.armor_all, 0, 0, 0, rm_define.effect_always_off)
