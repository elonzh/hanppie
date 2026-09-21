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
        log_ctrl.print_msg("清晨，机器人揉揉眼睛")
        for yawn in range(REPEAT_COUNT):
            led_ctrl.set_led(rm_define.armor_all, 60, 50, 100, rm_define.effect_breath)
            for note in (0, 1, 0):
                led_ctrl.set_led(rm_define.armor_all, 60, 50, 100, rm_define.effect_always_on)
                media_ctrl.play_sound(NOTES[note])
                time.sleep(BEAT)

        percentage = robot_ctrl.get_battery_percentage()
        if percentage is None:
            log_ctrl.print_msg("还没睡醒，慢慢伸个懒腰")
            for note in (0, 2, 4, 2):
                led_ctrl.set_led(rm_define.armor_all, 100, 130, 230, rm_define.effect_always_on)
                media_ctrl.play_sound(NOTES[note])
                time.sleep(BEAT)
        elif percentage < 25:
            log_ctrl.print_msg("电量 %s%%，今天想赖床" % percentage)
            for note in (4, 2, 0):
                led_ctrl.set_led(rm_define.armor_all, 255, 100, 40, rm_define.effect_always_on)
                media_ctrl.play_sound(NOTES[note])
                time.sleep(BEAT)
            time.sleep(1)
        else:
            log_ctrl.print_msg("电量 %s%%，元气满满出门啦" % percentage)
            for note in (0, 2, 4, 7, 4, 7):
                led_ctrl.set_led(rm_define.armor_all, 80, 220, 100, rm_define.effect_always_on)
                media_ctrl.play_sound(NOTES[note])
                time.sleep(BEAT)
    finally:
        led_ctrl.set_led(rm_define.armor_all, 0, 0, 0, rm_define.effect_always_off)
