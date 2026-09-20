REPEAT_COUNT = 3
BEAT = 0.28
NOTES = (rm_define.media_sound_solmization_1C, rm_define.media_sound_solmization_1D,
         rm_define.media_sound_solmization_1E, rm_define.media_sound_solmization_1F,
         rm_define.media_sound_solmization_1G, rm_define.media_sound_solmization_1A,
         rm_define.media_sound_solmization_1B, rm_define.media_sound_solmization_2C)

def stage(message):
    log_ctrl.print_msg(message)

def glow(color, effect=rm_define.effect_always_on):
    led_ctrl.set_led(rm_define.armor_all, color[0], color[1], color[2], effect)

def phrase(notes, color):
    for note in notes:
        glow(color)
        media_ctrl.play_sound(NOTES[note])
        time.sleep(BEAT)

def start():
    try:
        stage("黄昏，花园渐渐安静")
        for level in range(8, 0, -1):
            glow((level * 20, level * 8, level * 3))
            time.sleep(0.2)
        for meeting in range(REPEAT_COUNT):
            stage("萤火虫的问答 %s" % (meeting + 1))
            for note, tint in ((4, (150, 230, 30)), (7, (30, 200, 140))):
                media_ctrl.play_sound(NOTES[note])
                for level in (1, 2, 4, 6, 8, 6, 4, 2, 1, 0):
                    glow(tuple(channel * level // 8 for channel in tint))
                    time.sleep(0.12)
        stage("晚安，明天见")
        time.sleep(1)
    finally:
        glow((0, 0, 0), rm_define.effect_always_off)
