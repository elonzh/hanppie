REPEAT_COUNT = 2
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
        for remaining in range(5, 0, -1):
            stage("发射倒计时 %s" % remaining)
            glow((255, 35, 30))
            media_ctrl.play_sound(NOTES[0])
            time.sleep(0.6)
        stage("点火，升空！")
        for launch in range(REPEAT_COUNT):
            phrase((0, 1, 2, 3, 4, 5, 6, 7), (255, 100, 15))
        stage("进入轨道，太阳能板展开")
        glow((20, 90, 255), rm_define.effect_breath)
        time.sleep(2)
        phrase((7, 4, 2, 7), (180, 210, 255))
    finally:
        glow((0, 0, 0), rm_define.effect_always_off)
