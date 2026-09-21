REPEAT_COUNT = 2



def start():
    try:
        robot_ctrl.set_mode(rm_define.robot_mode_free)
        chassis_ctrl.set_trans_speed(0.25)
        chassis_ctrl.set_rotate_speed(180)
        gimbal_ctrl.set_rotate_speed(60)
        gimbal_ctrl.recenter()

        # ==========================================
        # PART 0: Ready! + 干冰机喷雾 + 主歌律动 (14.04s)
        # ==========================================
        log_ctrl.print_msg("Beck Martin: Ready!")
        try:
            media_ctrl.play_sound(rm_define.media_custom_audio_0)
        except Exception:
            pass

        led_ctrl.set_led(rm_define.armor_all, 255, 255, 255, rm_define.effect_always_on)
        gimbal_ctrl.rotate_with_degree(rm_define.gimbal_up, 12)
        time.sleep(0.80)

        log_ctrl.print_msg("启动自带干冰烟雾机: 噗—— (Dry ice smoke blast!)")
        led_ctrl.set_led(rm_define.armor_all, 255, 255, 255, rm_define.effect_flash)
        chassis_ctrl.move_with_time(0, 0.10)
        chassis_ctrl.move_with_time(180, 0.10)
        time.sleep(1.80)

        log_ctrl.print_msg(">>> Techno Beat Drop! Club X Factor 营业！<<<")
        led_ctrl.set_led(rm_define.armor_all, 255, 0, 150, rm_define.effect_marquee)
        time.sleep(1.84)

        log_ctrl.print_msg("Beck: I was working all week...")
        led_ctrl.set_led(rm_define.armor_all, 0, 200, 255, rm_define.effect_breath)
        chassis_ctrl.move_with_time(-90, 0.40)
        gimbal_ctrl.rotate_with_degree(rm_define.gimbal_down, 6)
        gimbal_ctrl.rotate_with_degree(rm_define.gimbal_up, 6)
        time.sleep(1.90)

        log_ctrl.print_msg("Beck: ...nothing exciting")
        gimbal_ctrl.rotate_with_degree(rm_define.gimbal_left, 15)
        gimbal_ctrl.rotate_with_degree(rm_define.gimbal_right, 30)
        gimbal_ctrl.rotate_with_degree(rm_define.gimbal_left, 15)
        time.sleep(0.50)

        log_ctrl.print_msg("Beck: I was waiting all week...")
        led_ctrl.set_led(rm_define.armor_all, 0, 200, 255, rm_define.effect_breath)
        chassis_ctrl.move_with_time(90, 0.40)
        gimbal_ctrl.rotate_with_degree(rm_define.gimbal_down, 6)
        gimbal_ctrl.rotate_with_degree(rm_define.gimbal_up, 6)
        time.sleep(1.90)

        log_ctrl.print_msg("Beck: ...so boring")
        led_ctrl.set_led(rm_define.armor_all, 50, 100, 255, rm_define.effect_breath)
        gimbal_ctrl.rotate_with_degree(rm_define.gimbal_down, 12)
        time.sleep(2.50)

        # ==========================================
        # PART 1: 副歌经典放电与健身滑步 (25.04s)
        # ==========================================
        log_ctrl.print_msg("Beck: But tonight I will go out, because it is Friday night!")
        try:
            media_ctrl.play_sound(rm_define.media_custom_audio_1)
        except Exception:
            pass

        led_ctrl.set_led(rm_define.armor_all, 255, 50, 200, rm_define.effect_marquee)
        chassis_ctrl.move_with_time(0, 0.40)
        gimbal_ctrl.rotate_with_degree(rm_define.gimbal_up, 12)
        gimbal_ctrl.rotate_with_degree(rm_define.gimbal_right, 24)
        gimbal_ctrl.rotate_with_degree(rm_define.gimbal_left, 48)
        gimbal_ctrl.rotate_with_degree(rm_define.gimbal_right, 24)
        time.sleep(5.80)

        log_ctrl.print_msg("Beck: I wanna keep moving all night!")
        led_ctrl.set_led(rm_define.armor_all, 255, 140, 0, rm_define.effect_marquee)
        for step in range(2):
            chassis_ctrl.move_with_time(-90, 0.35)
            chassis_ctrl.move_with_time(90, 0.35)
        time.sleep(3.60)

        log_ctrl.print_msg("Beck: I wanna dance all night, all night!")
        for nod in range(2):
            gimbal_ctrl.rotate_with_degree(rm_define.gimbal_up, 9)
            gimbal_ctrl.rotate_with_degree(rm_define.gimbal_down, 9)
        time.sleep(3.40)

        log_ctrl.print_msg("Beck: And I wanna drink all night...")
        led_ctrl.set_led(rm_define.armor_all, 255, 215, 0, rm_define.effect_breath)
        chassis_ctrl.move_with_time(-90, 0.30)
        time.sleep(2.70)

        log_ctrl.print_msg("Beck: ...And I wanna party all night!")
        chassis_ctrl.move_with_time(90, 0.30)
        time.sleep(4.74)

        # ==========================================
        # PART 2: 周五高潮与全场举手 (25.04s)
        # ==========================================
        log_ctrl.print_msg("Beck: Because it is Friday... Friday night!")
        try:
            media_ctrl.play_sound(rm_define.media_custom_audio_2)
        except Exception:
            pass

        led_ctrl.set_led(rm_define.armor_all, 255, 0, 100, rm_define.effect_flash)
        gimbal_ctrl.rotate_with_degree(rm_define.gimbal_up, 15)
        chassis_ctrl.move_with_time(0, 0.35)
        time.sleep(4.40)

        log_ctrl.print_msg("Beck: Everybody hands up!!")
        led_ctrl.set_led(rm_define.armor_all, 255, 215, 0, rm_define.effect_always_on)
        gimbal_ctrl.rotate_with_degree(rm_define.gimbal_up, 18)
        time.sleep(3.70)

        log_ctrl.print_msg("Beck: It's all right, keep moving closer!")
        led_ctrl.set_led(rm_define.armor_all, 255, 0, 255, rm_define.effect_marquee)
        chassis_ctrl.move_with_time(0, 0.40)
        gimbal_ctrl.rotate_with_degree(rm_define.gimbal_left, 18)
        gimbal_ctrl.rotate_with_degree(rm_define.gimbal_right, 36)
        gimbal_ctrl.rotate_with_degree(rm_define.gimbal_left, 18)
        time.sleep(6.40)

        log_ctrl.print_msg("Beck: Party till the morning, buy you shots and drinks!")
        led_ctrl.set_led(rm_define.armor_all, 0, 255, 200, rm_define.effect_marquee)
        for step in range(2):
            chassis_ctrl.move_with_time(-90, 0.35)
            chassis_ctrl.move_with_time(90, 0.35)
        time.sleep(6.64)

        # ==========================================
        # PART 3: 狂欢大回旋 + Nicole 尖叫 Dry Ice + 晋级庆祝 (24.04s)
        # ==========================================
        log_ctrl.print_msg("Beck: Dancing all night long, kissing nonstop!")
        try:
            media_ctrl.play_sound(rm_define.media_custom_audio_3)
        except Exception:
            pass

        led_ctrl.set_led(rm_define.armor_all, 255, 100, 0, rm_define.effect_breath)
        for nod in range(2):
            gimbal_ctrl.rotate_with_degree(rm_define.gimbal_up, 9)
            gimbal_ctrl.rotate_with_degree(rm_define.gimbal_down, 9)
        time.sleep(5.40)

        log_ctrl.print_msg("Beck: Because it's Friday night!")
        led_ctrl.set_led(rm_define.armor_all, 255, 0, 255, rm_define.effect_flash)
        gimbal_ctrl.rotate_with_degree(rm_define.gimbal_up, 12)
        time.sleep(4.80)

        log_ctrl.print_msg("Nicole 站上评委席热舞！Nicole 尖叫: Woo! Dry ice!! Simon 破天荒起立摇摆！")
        led_ctrl.set_led(rm_define.armor_all, 255, 0, 255, rm_define.effect_marquee)
        for spin in range(REPEAT_COUNT):
            chassis_ctrl.rotate_with_degree(rm_define.clockwise, 360)
        time.sleep(3.00)

        log_ctrl.print_msg("全场起立欢呼！Beck Martin 晋级训练营 (Boot Camp)！鞠躬致谢！")
        led_ctrl.set_led(rm_define.armor_all, 255, 255, 255, rm_define.effect_flash)
        gimbal_ctrl.rotate_with_degree(rm_define.gimbal_down, 20)
        time.sleep(5.37)
        gimbal_ctrl.recenter()
    finally:
        chassis_ctrl.stop()
        gimbal_ctrl.stop()
        led_ctrl.set_led(rm_define.armor_all, 0, 0, 0, rm_define.effect_always_off)
        gimbal_ctrl.recenter()
