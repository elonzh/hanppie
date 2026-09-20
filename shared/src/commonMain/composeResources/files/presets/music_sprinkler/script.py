REPEAT_COUNT = 2
BEAT_SECONDS = 0.60
# Lan Hua Cao opening phrase, melody and rhythm checked against:
# https://www.everyonepiano.cn/Number-1136.html
# Built-in notes approximate a music-box loudspeaker, not a song recording.
MELODY = (
    (rm_define.media_sound_solmization_1A, 0.5),
    (rm_define.media_sound_solmization_2E, 0.5),
    (rm_define.media_sound_solmization_2E, 0.5),
    (rm_define.media_sound_solmization_2E, 0.5),
    (rm_define.media_sound_solmization_2E, 1.5),
    (rm_define.media_sound_solmization_2D, 0.5),
    (rm_define.media_sound_solmization_2C, 0.75),
    (rm_define.media_sound_solmization_2D, 0.25),
    (rm_define.media_sound_solmization_2C, 0.5),
    (rm_define.media_sound_solmization_1B, 0.5),
    (rm_define.media_sound_solmization_1A, 2.0),
)

def start():
    try:
        robot_ctrl.set_mode(rm_define.robot_mode_free)
        chassis_ctrl.set_trans_speed(0.10)
        log_ctrl.print_msg("早班出车：兰花草响起，请让一让")
        led_ctrl.set_led(rm_define.armor_all, 255, 160, 30, rm_define.effect_breath)
        time.sleep(1)
        for section in range(REPEAT_COUNT):
            log_ctrl.print_msg("沿街洒水 %s/%s：蓝灯模拟水流，不发射" % (section + 1, REPEAT_COUNT))
            for sound, beats in MELODY:
                led_ctrl.set_led(rm_define.armor_all, 20, 150 + section * 30, 255, rm_define.effect_marquee)
                media_ctrl.play_sound(sound)
                chassis_ctrl.move_with_time(0, beats * BEAT_SECONDS)
        log_ctrl.print_msg("到站关水，停车收工")
        led_ctrl.set_led(rm_define.armor_all, 30, 220, 160, rm_define.effect_breath)
        time.sleep(1)
    finally:
        chassis_ctrl.stop()
        led_ctrl.set_led(rm_define.armor_all, 0, 0, 0, rm_define.effect_always_off)
