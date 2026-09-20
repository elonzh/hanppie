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
        stage("演习开始：发现倒计时装置")
        for phase in range(REPEAT_COUNT):
            stage("正在排查第 %s 组线路" % (phase + 1))
            interval = 0.7 - phase * 0.2
            for tick in range(5):
                glow((255, 20, 10))
                media_ctrl.play_sound(NOTES[0])
                time.sleep(interval / 2)
                glow((20, 0, 0))
                time.sleep(interval / 2)
        stage("最后一根线……")
        glow((0, 0, 0))
        time.sleep(1.5)
        stage("解除成功！演习结束")
        phrase((0, 2, 4, 7), (20, 220, 90))
        time.sleep(1)
    finally:
        glow((0, 0, 0), rm_define.effect_always_off)
