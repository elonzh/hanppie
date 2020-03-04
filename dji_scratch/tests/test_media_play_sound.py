import event_client
import rm_ctrl
import tools
import rm_define
import time

event = event_client.EventClient()
media_ctrl = rm_ctrl.MediaCtrl(event)
tools.wait(50)

media_list = [
    rm_define.media_sound_animal_bear,
    rm_define.media_sound_animal_bird,
    rm_define.media_sound_animal_cat,
    rm_define.media_sound_animal_dog,
    rm_define.media_sound_animal_frog,
    rm_define.media_sound_animal_monkey,
    rm_define.media_sound_animal_sheep,
    rm_define.media_sound_cartonn_weird_voice,
    rm_define.media_sound_cartoon_honk,
    rm_define.media_sound_cartoon_squeak,
    rm_define.media_sound_cartoon_yipee,
    rm_define.media_sound_human_burp,
    rm_define.media_sound_human_flapping_lips,
    rm_define.media_sound_human_laugh,
    rm_define.media_sound_human_scared,
    rm_define.media_sound_human_snore,
    rm_define.media_sound_human_whistle,
    rm_define.media_sound_human_yah,
    rm_define.media_sound_human_yawn,
    rm_define.media_sound_instrument_drum,
    rm_define.media_sound_instrument_trombone,
    rm_define.media_sound_instrument_violin,
    rm_define.media_sound_animal_tiger,
    rm_define.media_sound_animal_lion,
    rm_define.media_sound_animal_leopard,
    rm_define.media_sound_heatbeet,
    rm_define.media_sound_cricket,
    rm_define.media_sound_trans_engine,
    rm_define.media_sound_trans_drift,
    rm_define.media_sound_trans_overtake,
    rm_define.media_sound_trans_brake,
    rm_define.media_sound_trans_reverse,
    rm_define.media_sound_trans_alarm,
    rm_define.media_sound_nature_tornado,
    rm_define.media_sound_nature_thunder,
    rm_define.media_sound_nature_strom,
    rm_define.media_sound_special_laser,
    rm_define.media_sound_special_countdown_mechanic,
    rm_define.media_sound_special_countdown_human,
    rm_define.media_sound_special_shutter,
    rm_define.media_sound_special_impact,
    rm_define.media_sound_special_broken_glasses,
    rm_define.media_sound_special_sneak_attack
]
def start():
    for sou in media_list:
        media_ctrl.play_sound(sou)
        time.sleep(4)

try:
    start()
except:
    pass

while(event.is_wait()):
    tools.wait(100)

del media_ctrl

tools.wait(500)
