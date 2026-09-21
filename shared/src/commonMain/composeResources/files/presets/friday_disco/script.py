REPEAT_COUNT = 2


def start():
    try:
        robot_ctrl.set_mode(rm_define.robot_mode_free)
        chassis_ctrl.set_trans_speed(0.40)
        chassis_ctrl.set_rotate_speed(360)
        gimbal_ctrl.set_rotate_speed(180)
        gimbal_ctrl.recenter()

        # ==========================================
        # 整首原声伴奏连续播放 (88.16s BGM)
        # ==========================================
        try:
            media_ctrl.play_sound(rm_define.media_custom_audio_0)
        except Exception:
            pass

        # ==========================================
        # 0.00s - 14.04s: 开场站桩 + 自带干冰喷雾 + 主歌律动
        # ==========================================
        log_ctrl.print_msg("Beck Martin: Ready!")
        led_ctrl.set_led(rm_define.armor_all, 255, 255, 255, rm_define.effect_always_on)
        gimbal_ctrl.rotate_with_degree(rm_define.gimbal_up, 15)
        time.sleep(0.79)

        log_ctrl.print_msg("启动自带干冰烟雾机: 噗—— (Dry ice smoke blast!)")
        led_ctrl.set_led(rm_define.armor_all, 255, 255, 255, rm_define.effect_flash)
        for _ in range(5):
            chassis_ctrl.move_with_time(0, 0.08)
            chassis_ctrl.move_with_time(180, 0.08)
        time.sleep(0.90)

        log_ctrl.print_msg(">>> Techno Beat Drop! Club X Factor 营业！<<<")
        led_ctrl.set_led(rm_define.armor_all, 255, 0, 150, rm_define.effect_marquee)
        for _ in range(3):
            gimbal_ctrl.rotate_with_degree(rm_define.gimbal_down, 8)
            gimbal_ctrl.rotate_with_degree(rm_define.gimbal_up, 8)
            time.sleep(0.20)
        time.sleep(0.63)

        log_ctrl.print_msg("Beck: I was working all week...")
        led_ctrl.set_led(rm_define.armor_all, 0, 200, 255, rm_define.effect_breath)
        for _ in range(2):
            chassis_ctrl.move_with_time(-90, 0.35)
            gimbal_ctrl.rotate_with_degree(rm_define.gimbal_down, 6)
            gimbal_ctrl.rotate_with_degree(rm_define.gimbal_up, 6)
        chassis_ctrl.move_with_time(-90, 0.35)
        time.sleep(1.05)

        log_ctrl.print_msg("Beck: ...nothing exciting")
        gimbal_ctrl.rotate_with_degree(rm_define.gimbal_left, 18)
        gimbal_ctrl.rotate_with_degree(rm_define.gimbal_right, 36)
        gimbal_ctrl.rotate_with_degree(rm_define.gimbal_left, 18)
        time.sleep(1.26)

        log_ctrl.print_msg("Beck: I was waiting all week...")
        led_ctrl.set_led(rm_define.armor_all, 0, 200, 255, rm_define.effect_breath)
        for _ in range(2):
            chassis_ctrl.move_with_time(90, 0.35)
            gimbal_ctrl.rotate_with_degree(rm_define.gimbal_down, 6)
            gimbal_ctrl.rotate_with_degree(rm_define.gimbal_up, 6)
        chassis_ctrl.move_with_time(90, 0.35)
        time.sleep(0.95)

        log_ctrl.print_msg("Beck: ...so boring")
        led_ctrl.set_led(rm_define.armor_all, 50, 100, 255, rm_define.effect_breath)
        gimbal_ctrl.rotate_with_degree(rm_define.gimbal_down, 14)
        time.sleep(2.08)

        # ==========================================
        # 14.04s - 39.08s: 周五降临 + 健身教练律动滑步
        # ==========================================
        log_ctrl.print_msg("Beck: But tonight I will go out, because it is Friday night!")
        led_ctrl.set_led(rm_define.armor_all, 255, 50, 200, rm_define.effect_marquee)
        chassis_ctrl.move_with_time(0, 0.35)
        gimbal_ctrl.rotate_with_degree(rm_define.gimbal_up, 14)
        for _ in range(5):
            chassis_ctrl.move_with_time(-90, 0.28)
            gimbal_ctrl.rotate_with_degree(rm_define.gimbal_right, 16)
            chassis_ctrl.move_with_time(90, 0.28)
            gimbal_ctrl.rotate_with_degree(rm_define.gimbal_left, 16)
        chassis_ctrl.move_with_time(180, 0.35)
        time.sleep(0.24)

        log_ctrl.print_msg("Beck: I wanna keep moving all night!")
        led_ctrl.set_led(rm_define.armor_all, 255, 140, 0, rm_define.effect_marquee)
        for _ in range(5):
            chassis_ctrl.move_with_time(-90, 0.24)
            gimbal_ctrl.rotate_with_degree(rm_define.gimbal_up, 7)
            chassis_ctrl.move_with_time(90, 0.24)
            gimbal_ctrl.rotate_with_degree(rm_define.gimbal_down, 7)
        time.sleep(0.18)

        log_ctrl.print_msg("Beck: I wanna dance all night, all night!")
        for _ in range(5):
            gimbal_ctrl.rotate_with_degree(rm_define.gimbal_up, 9)
            chassis_ctrl.move_with_time(0, 0.10)
            gimbal_ctrl.rotate_with_degree(rm_define.gimbal_down, 9)
            chassis_ctrl.move_with_time(180, 0.10)
        time.sleep(1.12)

        log_ctrl.print_msg("Beck: And I wanna drink all night...")
        led_ctrl.set_led(rm_define.armor_all, 255, 215, 0, rm_define.effect_breath)
        chassis_ctrl.move_with_time(-90, 0.35)
        gimbal_ctrl.rotate_with_degree(rm_define.gimbal_up, 8)
        chassis_ctrl.move_with_time(-90, 0.35)
        gimbal_ctrl.rotate_with_degree(rm_define.gimbal_down, 8)
        chassis_ctrl.move_with_time(90, 0.25)
        time.sleep(0.81)

        log_ctrl.print_msg("Beck: ...And I wanna party all night!")
        chassis_ctrl.move_with_time(90, 0.35)
        gimbal_ctrl.rotate_with_degree(rm_define.gimbal_up, 8)
        chassis_ctrl.move_with_time(90, 0.35)
        gimbal_ctrl.rotate_with_degree(rm_define.gimbal_down, 8)
        chassis_ctrl.move_with_time(-90, 0.25)
        time.sleep(0.97)

        log_ctrl.print_msg("Beck: ...And I wanna enjoy this night!")
        for _ in range(3):
            chassis_ctrl.move_with_time(-90, 0.20)
            gimbal_ctrl.rotate_with_degree(rm_define.gimbal_up, 6)
            chassis_ctrl.move_with_time(90, 0.20)
            gimbal_ctrl.rotate_with_degree(rm_define.gimbal_down, 6)
        time.sleep(0.08)

        # ==========================================
        # 39.08s - 64.12s: 周五高潮 + Everybody Hands Up!
        # ==========================================
        log_ctrl.print_msg("Beck: Because it is Friday... Friday night!")
        led_ctrl.set_led(rm_define.armor_all, 255, 0, 100, rm_define.effect_flash)
        chassis_ctrl.move_with_time(0, 0.35)
        gimbal_ctrl.rotate_with_degree(rm_define.gimbal_up, 15)
        for _ in range(3):
            chassis_ctrl.move_with_time(-90, 0.26)
            gimbal_ctrl.rotate_with_degree(rm_define.gimbal_down, 8)
            chassis_ctrl.move_with_time(90, 0.26)
            gimbal_ctrl.rotate_with_degree(rm_define.gimbal_up, 8)
        chassis_ctrl.move_with_time(180, 0.35)
        time.sleep(0.97)

        log_ctrl.print_msg("Beck: Everybody hands up!!")
        led_ctrl.set_led(rm_define.armor_all, 255, 215, 0, rm_define.effect_always_on)
        gimbal_ctrl.rotate_with_degree(rm_define.gimbal_up, 20)
        for _ in range(5):
            chassis_ctrl.move_with_time(-90, 0.20)
            chassis_ctrl.move_with_time(90, 0.20)
            gimbal_ctrl.rotate_with_degree(rm_define.gimbal_down, 6)
            gimbal_ctrl.rotate_with_degree(rm_define.gimbal_up, 6)
        time.sleep(0.67)

        log_ctrl.print_msg("Beck: It's all right, keep moving closer!")
        led_ctrl.set_led(rm_define.armor_all, 255, 0, 255, rm_define.effect_marquee)
        chassis_ctrl.move_with_time(0, 0.35)
        gimbal_ctrl.rotate_with_degree(rm_define.gimbal_left, 18)
        gimbal_ctrl.rotate_with_degree(rm_define.gimbal_right, 36)
        gimbal_ctrl.rotate_with_degree(rm_define.gimbal_left, 18)
        for _ in range(3):
            chassis_ctrl.move_with_time(-90, 0.24)
            chassis_ctrl.move_with_time(90, 0.24)
        chassis_ctrl.move_with_time(180, 0.35)
        time.sleep(1.65)

        log_ctrl.print_msg("Beck: Party till the morning, buy you shots and drinks!")
        led_ctrl.set_led(rm_define.armor_all, 0, 255, 200, rm_define.effect_marquee)
        for _ in range(10):
            chassis_ctrl.move_with_time(-90, 0.26)
            gimbal_ctrl.rotate_with_degree(rm_define.gimbal_up, 7)
            chassis_ctrl.move_with_time(90, 0.26)
            gimbal_ctrl.rotate_with_degree(rm_define.gimbal_down, 7)
        time.sleep(1.52)

        # ==========================================
        # 64.12s - 88.16s: 狂欢大回旋 + Nicole 尖叫 Dry Ice + 晋级谢幕
        # ==========================================
        log_ctrl.print_msg("Beck: Dancing all night long, kissing nonstop!")
        led_ctrl.set_led(rm_define.armor_all, 255, 100, 0, rm_define.effect_marquee)
        for _ in range(5):
            chassis_ctrl.move_with_time(-90, 0.25)
            gimbal_ctrl.rotate_with_degree(rm_define.gimbal_up, 8)
            gimbal_ctrl.rotate_with_degree(rm_define.gimbal_down, 8)
            chassis_ctrl.move_with_time(90, 0.25)
            gimbal_ctrl.rotate_with_degree(rm_define.gimbal_up, 8)
            gimbal_ctrl.rotate_with_degree(rm_define.gimbal_down, 8)
        time.sleep(1.01)

        log_ctrl.print_msg("Beck: Because it's Friday night!")
        led_ctrl.set_led(rm_define.armor_all, 255, 0, 255, rm_define.effect_flash)
        gimbal_ctrl.rotate_with_degree(rm_define.gimbal_up, 18)
        chassis_ctrl.move_with_time(0, 0.30)
        for _ in range(4):
            chassis_ctrl.move_with_time(-90, 0.25)
            gimbal_ctrl.rotate_with_degree(rm_define.gimbal_down, 7)
            chassis_ctrl.move_with_time(90, 0.25)
            gimbal_ctrl.rotate_with_degree(rm_define.gimbal_up, 7)
        chassis_ctrl.move_with_time(180, 0.30)
        time.sleep(1.17)

        log_ctrl.print_msg("Nicole 站上评委席热舞！Nicole 尖叫: Woo! Dry ice!! Simon 破天荒起立摇摆！")
        led_ctrl.set_led(rm_define.armor_all, 255, 255, 255, rm_define.effect_flash)
        for _ in range(REPEAT_COUNT):
            chassis_ctrl.rotate_with_degree(rm_define.clockwise, 360)
        led_ctrl.set_led(rm_define.armor_all, 255, 0, 255, rm_define.effect_marquee)
        gimbal_ctrl.rotate_with_degree(rm_define.gimbal_up, 18)
        for _ in range(4):
            chassis_ctrl.move_with_time(-90, 0.22)
            chassis_ctrl.move_with_time(90, 0.22)
            gimbal_ctrl.rotate_with_degree(rm_define.gimbal_down, 7)
            gimbal_ctrl.rotate_with_degree(rm_define.gimbal_up, 7)
        time.sleep(2.24)

        log_ctrl.print_msg("Beck: Friday night climax!")
        for _ in range(4):
            chassis_ctrl.move_with_time(0, 0.12)
            gimbal_ctrl.rotate_with_degree(rm_define.gimbal_up, 8)
            chassis_ctrl.move_with_time(180, 0.12)
            gimbal_ctrl.rotate_with_degree(rm_define.gimbal_down, 8)
        time.sleep(0.75)

        log_ctrl.print_msg("全场起立欢呼！Beck Martin 晋级训练营 (Boot Camp)！鞠躬致谢！")
        led_ctrl.set_led(rm_define.armor_all, 255, 255, 255, rm_define.effect_always_on)
        gimbal_ctrl.rotate_with_degree(rm_define.gimbal_down, 20)
        time.sleep(0.70)
        gimbal_ctrl.recenter()
    finally:
        chassis_ctrl.stop()
        gimbal_ctrl.stop()
        led_ctrl.set_led(rm_define.armor_all, 0, 0, 0, rm_define.effect_always_off)
        gimbal_ctrl.recenter()
