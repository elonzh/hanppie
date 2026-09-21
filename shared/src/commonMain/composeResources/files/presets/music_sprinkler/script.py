REPEAT_COUNT = 1
BEAT_SECONDS = 0.55
TRANS_SPEED = 0.12

__all__ = ["start"]

# 经典民歌《兰花草》（胡适词，陈贤德/张弼曲，1=C 4/4 拍）
# 共 4 个大乐句（8 小节，每句 8 拍 / 4.4 秒），全曲 32 拍约 17.6 秒。
# 水炮朝斜上方高昂仰起 20 度模拟高压喷淋，并在 70 度（±35度）扇面内以 4.4 秒为周期随乐句正弦往复扫洒。
# 动作设计：前两乐句低速从容前行（行程约 0.9 米，安全平稳），后两乐句定点大角度漫洒后停稳收工。
PHRASES = (
    (
        "乐句 1/4：我从山中来，带着兰花草 —— 慢速巡航出车，水炮向斜上方扇形扫洒",
        True,
        (
            (rm_define.media_sound_solmization_1A, 0.5),   # 我 (低音 6)
            (rm_define.media_sound_solmization_2E, 0.5),   # 从 (3)
            (rm_define.media_sound_solmization_2E, 0.5),   # 山 (3)
            (rm_define.media_sound_solmization_2E, 0.5),   # 中 (3)
            (rm_define.media_sound_solmization_2E, 1.5),   # 来 (3.)
            (rm_define.media_sound_solmization_2D, 0.5),   # (2)
            (rm_define.media_sound_solmization_2C, 0.75),  # 带 (1.)
            (rm_define.media_sound_solmization_2D, 0.25),  # 着 (2)
            (rm_define.media_sound_solmization_2C, 0.5),   # 兰 (1)
            (rm_define.media_sound_solmization_1B, 0.5),   # 花 (低音 7)
            (rm_define.media_sound_solmization_1A, 2.0),   # 草 (低音 6)
        ),
    ),
    (
        "乐句 2/4：种在小园中，希望花开早 —— 扇形水幕向两侧均匀挥洒",
        True,
        (
            (rm_define.media_sound_solmization_1A, 0.5),       # 种 (低音 6)
            (rm_define.media_sound_solmization_1A, 0.5),       # 在 (低音 6)
            (rm_define.media_sound_solmization_1A, 0.5),       # 小 (低音 6)
            (rm_define.media_sound_solmization_1A, 0.5),       # 园 (低音 6)
            (rm_define.media_sound_solmization_1A, 1.5),       # 中 (低音 6.)
            (rm_define.media_sound_solmization_1G, 0.5),       # (低音 5)
            (rm_define.media_sound_solmization_1E, 0.5),       # 希 (低音 3)
            (rm_define.media_sound_solmization_1G, 0.5),       # 望 (低音 5)
            (rm_define.media_sound_solmization_1G, 0.5),       # 花 (低音 5)
            (rm_define.media_sound_solmization_1FSharp, 0.5),  # 开 (低音 #4)
            (rm_define.media_sound_solmization_1E, 2.0),       # 早 (低音 3)
        ),
    ),
    (
        "乐句 3/4：一日看三回，看得花时过 —— 到位停稳，水炮继续大角度漫洒",
        False,
        (
            (rm_define.media_sound_solmization_2E, 0.5),   # 一 (3)
            (rm_define.media_sound_solmization_2A, 0.5),   # 日 (6)
            (rm_define.media_sound_solmization_2A, 0.5),   # 看 (6)
            (rm_define.media_sound_solmization_2G, 0.5),   # 三 (5)
            (rm_define.media_sound_solmization_2E, 1.5),   # 回 (3.)
            (rm_define.media_sound_solmization_2D, 0.5),   # (2)
            (rm_define.media_sound_solmization_2C, 0.5),   # 看 (1)
            (rm_define.media_sound_solmization_2D, 0.5),   # 得 (2)
            (rm_define.media_sound_solmization_2C, 0.5),   # 花 (1)
            (rm_define.media_sound_solmization_1B, 0.5),   # 时 (低音 7)
            (rm_define.media_sound_solmization_1A, 1.0),   # 过 (低音 6)
            (rm_define.media_sound_solmization_1E, 1.0),   # (低音 3)
        ),
    ),
    (
        "乐句 4/4：兰花却依然，苞也无一个 —— 水幕挥洒，准备关水收工",
        False,
        (
            (rm_define.media_sound_solmization_1E, 0.5),   # 兰 (低音 3)
            (rm_define.media_sound_solmization_2C, 0.5),   # 花 (1)
            (rm_define.media_sound_solmization_2C, 0.5),   # 却 (1)
            (rm_define.media_sound_solmization_1B, 0.5),   # 依 (低音 7)
            (rm_define.media_sound_solmization_1A, 1.5),   # 然 (低音 6.)
            (rm_define.media_sound_solmization_1E, 0.5),   # (低音 3)
            (rm_define.media_sound_solmization_2D, 0.75),  # 苞 (2.)
            (rm_define.media_sound_solmization_2C, 0.25),  # 也 (1)
            (rm_define.media_sound_solmization_1B, 0.5),   # 无 (低音 7)
            (rm_define.media_sound_solmization_1G, 0.5),   # 一 (低音 5)
            (rm_define.media_sound_solmization_1A, 2.0),   # 个 (低音 6)
        ),
    ),
)


def start():
    try:
        robot_ctrl.set_mode(rm_define.robot_mode_free)
        chassis_ctrl.set_trans_speed(TRANS_SPEED)
        gimbal_ctrl.set_rotate_speed(30)
        gimbal_ctrl.recenter()

        # 水炮就位：向上仰起 20 度，模拟高压水炮向斜上方高昂喷淋姿态
        gimbal_ctrl.rotate_with_degree(rm_define.gimbal_up, 20)

        log_ctrl.print_msg("早班出车：水炮斜向上就位，音乐响起，请避让")
        # 车顶黄色警示灯呼吸闪烁，底盘水蓝色常亮光模拟喷水作业
        led_ctrl.set_top_led(rm_define.armor_top_all, 255, 160, 20, rm_define.effect_breath)
        led_ctrl.set_bottom_led(rm_define.armor_bottom_all, 0, 160, 255, rm_define.effect_always_on)
        time.sleep(1)

        # 启动云台大角度硬件正弦往复扫水（周期 4.4s 完美吻合 8 拍大乐句，摆幅左右各 35 度，扇面达 70 度）
        gimbal_ctrl.compound_motion_ctrl(
            rm_define.gimbal_compound_motion_enable,
            rm_define.gimbal_axis_yaw,
            4.4,
            35,
        )

        for round_idx in range(REPEAT_COUNT):
            for desc, is_cruising, notes in PHRASES:
                log_ctrl.print_msg(desc)
                if is_cruising:
                    chassis_ctrl.set_trans_speed(TRANS_SPEED)
                    chassis_ctrl.move(0)
                else:
                    chassis_ctrl.set_trans_speed(0)
                for sound, beats in notes:
                    media_ctrl.play_sound(sound)
                    time.sleep(beats * BEAT_SECONDS)

        # 演奏完毕，到站收工
        log_ctrl.print_msg("到站关水：停车收工，水炮归位")
        led_ctrl.set_bottom_led(rm_define.armor_bottom_all, 0, 0, 0, rm_define.effect_always_off)
        led_ctrl.set_top_led(rm_define.armor_top_all, 30, 220, 160, rm_define.effect_breath)
        time.sleep(1)
    finally:
        chassis_ctrl.stop()
        gimbal_ctrl.compound_motion_stop()
        gimbal_ctrl.stop()
        led_ctrl.set_led(rm_define.armor_all, 0, 0, 0, rm_define.effect_always_off)
        gimbal_ctrl.recenter()
