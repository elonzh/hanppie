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
        stage("深夜小摊，热锅开张")
        for heat in range(1, 9):
            glow((heat * 28, heat * 8, 0))
            time.sleep(0.2)
        for order in range(REPEAT_COUNT):
            stage("新订单：切菜备料")
            for chop in range(6):
                media_ctrl.play_sound(NOTES[0 if chop % 2 == 0 else 2])
                time.sleep(0.18)
            stage("大火翻炒，准备出锅")
            for toss in range(5):
                glow((255, 70, 10))
                time.sleep(0.15)
                glow((80, 15, 0))
                time.sleep(0.15)
            stage("出餐，请慢用")
            phrase((4, 7), (255, 210, 100))
            time.sleep(0.8)
        stage("最后一位客人离开，收摊")
    finally:
        glow((0, 0, 0), rm_define.effect_always_off)
