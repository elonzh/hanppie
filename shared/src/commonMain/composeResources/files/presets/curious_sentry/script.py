REPEAT_COUNT = 3

def start():
    robot_ctrl.set_mode(rm_define.robot_mode_free)
    gimbal_ctrl.set_rotate_speed(60)
    led_ctrl.set_led(
        rm_define.armor_all, 40, 120, 255,
        rm_define.effect_breath
    )
    try:
        for round_index in range(REPEAT_COUNT):
            log_ctrl.print_msg("Sentry scan %s/%s: right" % (round_index + 1, REPEAT_COUNT))
            media_ctrl.play_sound(rm_define.media_sound_scanning)
            gimbal_ctrl.rotate_with_degree(
                rm_define.gimbal_right, 35
            )
            log_ctrl.print_msg("Sentry scan %s/%s: left" % (round_index + 1, REPEAT_COUNT))
            gimbal_ctrl.rotate_with_degree(
                rm_define.gimbal_left, 70
            )
            log_ctrl.print_msg("Sentry scan %s/%s: center" % (round_index + 1, REPEAT_COUNT))
            gimbal_ctrl.rotate_with_degree(
                rm_define.gimbal_right, 35
            )
        media_ctrl.play_sound(
            rm_define.media_sound_recognize_success,
            wait_for_complete=True
        )
    finally:
        gimbal_ctrl.stop()
        led_ctrl.set_led(
            rm_define.armor_all, 0, 0, 0,
            rm_define.effect_always_off
        )
        gimbal_ctrl.recenter()
