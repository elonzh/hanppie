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
        log_ctrl.print_msg("演习开始：发现倒计时装置")
        for phase in range(REPEAT_COUNT):
            log_ctrl.print_msg("正在排查第 %s 组线路" % (phase + 1))
            interval = 0.7 - phase * 0.2
            for tick in range(5):
                led_ctrl.set_led(rm_define.armor_all, 255, 20, 10, rm_define.effect_always_on)
                media_ctrl.play_sound(NOTES[0])
                time.sleep(interval / 2)
                led_ctrl.set_led(rm_define.armor_all, 20, 0, 0, rm_define.effect_always_on)
                time.sleep(interval / 2)

        log_ctrl.print_msg("最后一根线……")
        led_ctrl.set_led(rm_define.armor_all, 0, 0, 0, rm_define.effect_always_off)
        time.sleep(1.5)

        log_ctrl.print_msg("解除成功！演习结束")
        for note in (0, 2, 4, 7):
            led_ctrl.set_led(rm_define.armor_all, 20, 220, 90, rm_define.effect_always_on)
            media_ctrl.play_sound(NOTES[note])
            time.sleep(BEAT)
        time.sleep(1)
    finally:
        led_ctrl.set_led(rm_define.armor_all, 0, 0, 0, rm_define.effect_always_off)
