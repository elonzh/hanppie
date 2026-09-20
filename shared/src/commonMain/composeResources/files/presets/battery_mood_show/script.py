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
        stage("清晨，机器人揉揉眼睛")
        for yawn in range(REPEAT_COUNT):
            glow((60, 50, 100), rm_define.effect_breath)
            phrase((0, 1, 0), (60, 50, 100))
        percentage = robot_ctrl.get_battery_percentage()
        if percentage is None:
            stage("还没睡醒，慢慢伸个懒腰")
            phrase((0, 2, 4, 2), (100, 130, 230))
        elif percentage < 25:
            stage("电量 %s%%，今天想赖床" % percentage)
            phrase((4, 2, 0), (255, 100, 40))
            time.sleep(1)
        else:
            stage("电量 %s%%，元气满满出门啦" % percentage)
            phrase((0, 2, 4, 7, 4, 7), (80, 220, 100))
    finally:
        glow((0, 0, 0), rm_define.effect_always_off)
