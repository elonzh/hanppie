package cn.elonzh.hanppie.ui

import cn.elonzh.hanppie.resources.*
import kotlinx.serialization.Serializable
import org.jetbrains.compose.resources.StringResource

@Serializable
internal data class StoredScript(
    val id: String,
    val name: String,
    val source: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
) {
    init {
        require(id.isNotBlank())
        require(validScriptName(name))
        require(source.length <= MAX_SCRIPT_LENGTH)
    }
}

internal enum class PresetCapability { EFFECT, GIMBAL_MOTION, CHASSIS_MOTION }

internal data class PresetScript(
    val id: String,
    val name: StringResource,
    val summary: StringResource,
    val capability: PresetCapability,
    val source: String,
)

internal val presetScripts = listOf(
    PresetScript(
        id = "music-sprinkler",
        name = Res.string.preset_music_sprinkler,
        summary = Res.string.preset_music_sprinkler_summary,
        capability = PresetCapability.CHASSIS_MOTION,
        source = """
            REPEAT_COUNT = 2
            BEAT_SECONDS = 0.60
            # Lan Hua Cao opening phrase, melody and rhythm checked against:
            # https://www.everyonepiano.cn/Number-1136.html
            # Built-in notes approximate a music-box loudspeaker, not a song recording.
            MELODY = (
                (rm_define.media_sound_solmization_1A, 0.5),
                (rm_define.media_sound_solmization_2E, 0.5),
                (rm_define.media_sound_solmization_2E, 0.5),
                (rm_define.media_sound_solmization_2E, 0.5),
                (rm_define.media_sound_solmization_2E, 1.5),
                (rm_define.media_sound_solmization_2D, 0.5),
                (rm_define.media_sound_solmization_2C, 0.75),
                (rm_define.media_sound_solmization_2D, 0.25),
                (rm_define.media_sound_solmization_2C, 0.5),
                (rm_define.media_sound_solmization_1B, 0.5),
                (rm_define.media_sound_solmization_1A, 2.0),
            )

            def start():
                try:
                    robot_ctrl.set_mode(rm_define.robot_mode_free)
                    chassis_ctrl.set_trans_speed(0.10)
                    log_ctrl.print_msg("早班出车：兰花草响起，请让一让")
                    led_ctrl.set_led(rm_define.armor_all, 255, 160, 30, rm_define.effect_breath)
                    time.sleep(1)
                    for section in range(REPEAT_COUNT):
                        log_ctrl.print_msg("沿街洒水 %s/%s：蓝灯模拟水流，不发射" % (section + 1, REPEAT_COUNT))
                        for sound, beats in MELODY:
                            led_ctrl.set_led(rm_define.armor_all, 20, 150 + section * 30, 255, rm_define.effect_marquee)
                            media_ctrl.play_sound(sound)
                            chassis_ctrl.move_with_time(0, beats * BEAT_SECONDS)
                    log_ctrl.print_msg("到站关水，停车收工")
                    led_ctrl.set_led(rm_define.armor_all, 30, 220, 160, rm_define.effect_breath)
                    time.sleep(1)
                finally:
                    chassis_ctrl.stop()
                    led_ctrl.set_led(rm_define.armor_all, 0, 0, 0, rm_define.effect_always_off)
        """.trimIndent() + "\n",
    ),
    PresetScript(
        id = "space-launch",
        name = Res.string.preset_space_launch,
        summary = Res.string.preset_space_launch_summary,
        capability = PresetCapability.EFFECT,
        source = """
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
        """.trimIndent() + "\n",
    ),
    PresetScript(
        id = "firefly-garden",
        name = Res.string.preset_firefly_garden,
        summary = Res.string.preset_firefly_garden_summary,
        capability = PresetCapability.EFFECT,
        source = """
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
        """.trimIndent() + "\n",
    ),
    PresetScript(
        id = "lucky-cat",
        name = Res.string.preset_lucky_cat,
        summary = Res.string.preset_lucky_cat_summary,
        capability = PresetCapability.GIMBAL_MOTION,
        source = """
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
                    robot_ctrl.set_mode(rm_define.robot_mode_free)
                    gimbal_ctrl.set_rotate_speed(30)
                    gimbal_ctrl.recenter()
                    stage("小店开门，欢迎光临")
                    phrase((0, 2, 4), (255, 185, 40))
                    for visitor in range(REPEAT_COUNT):
                        stage("招手迎接第 %s 位客人" % (visitor + 1))
                        glow((255, 185, 40), rm_define.effect_breath)
                        gimbal_ctrl.rotate_with_degree(rm_define.gimbal_right, 20)
                        gimbal_ctrl.rotate_with_degree(rm_define.gimbal_left, 40)
                        gimbal_ctrl.rotate_with_degree(rm_define.gimbal_right, 20)
                        gimbal_ctrl.rotate_with_degree(rm_define.gimbal_down, 8)
                        gimbal_ctrl.rotate_with_degree(rm_define.gimbal_up, 8)
                        phrase((4, 7), (255, 220, 130))
                    stage("谢谢惠顾")
                finally:
                    gimbal_ctrl.stop()
                    glow((0, 0, 0), rm_define.effect_always_off)
        """.trimIndent() + "\n",
    ),
    PresetScript(
        id = "moonlight-waltz",
        name = Res.string.preset_moonlight_waltz,
        summary = Res.string.preset_moonlight_waltz_summary,
        capability = PresetCapability.CHASSIS_MOTION,
        source = """
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
                    robot_ctrl.set_mode(rm_define.robot_mode_free)
                    chassis_ctrl.set_trans_speed(0.15)
                    chassis_ctrl.set_rotate_speed(30)
                    stage("月光亮起，舞曲开始")
                    for dance in range(REPEAT_COUNT):
                        for direction, notes in ((90, (0, 2, 4)), (-90, (1, 3, 5))):
                            glow((100, 70, 240), rm_define.effect_breath)
                            for note in notes:
                                media_ctrl.play_sound(NOTES[note])
                                chassis_ctrl.move_with_time(direction, BEAT)
                        stage("转身，向舞伴致意")
                        chassis_ctrl.rotate_with_degree(rm_define.clockwise, 25)
                        phrase((7, 5, 4), (180, 130, 255))
                        chassis_ctrl.rotate_with_degree(rm_define.anticlockwise, 25)
                    stage("舞曲终了")
                    phrase((2, 1, 0), (70, 120, 210))
                finally:
                    chassis_ctrl.stop()
                    glow((0, 0, 0), rm_define.effect_always_off)
        """.trimIndent() + "\n",
    ),
    PresetScript(
        id = "station-train",
        name = Res.string.preset_station_train,
        summary = Res.string.preset_station_train_summary,
        capability = PresetCapability.CHASSIS_MOTION,
        source = """
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
                    robot_ctrl.set_mode(rm_define.robot_mode_free)
                    chassis_ctrl.set_trans_speed(0.15)
                    chassis_ctrl.set_rotate_speed(30)
                    stage("各位乘客请坐好，小火车出发")
                    phrase((0, 4, 7), (255, 190, 40))
                    for station, color in (("森林站", (50, 200, 80)), ("海边站", (30, 150, 255)), ("星空站", (150, 80, 255)), ("家乡站", (255, 150, 50))):
                        stage("下一站：" + station)
                        for sleeper in range(REPEAT_COUNT):
                            glow(color)
                            media_ctrl.play_sound(NOTES[sleeper % 2])
                            chassis_ctrl.move_with_time(0, BEAT)
                        stage("到站停靠：" + station)
                        phrase((4, 2), color)
                        time.sleep(0.5)
                        chassis_ctrl.rotate_with_degree(rm_define.clockwise, 90)
                    stage("环游结束，请带好随身物品")
                finally:
                    chassis_ctrl.stop()
                    glow((0, 0, 0), rm_define.effect_always_off)
        """.trimIndent() + "\n",
    ),
    PresetScript(
        id = "battery-mood-show",
        name = Res.string.preset_battery_mood_show,
        summary = Res.string.preset_battery_mood_show_summary,
        capability = PresetCapability.EFFECT,
        source = """
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
        """.trimIndent() + "\n",
    ),
    PresetScript(
        id = "street-chef",
        name = Res.string.preset_street_chef,
        summary = Res.string.preset_street_chef_summary,
        capability = PresetCapability.EFFECT,
        source = """
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
        """.trimIndent() + "\n",
    ),
    PresetScript(
        id = "penalty-keeper",
        name = Res.string.preset_penalty_keeper,
        summary = Res.string.preset_penalty_keeper_summary,
        capability = PresetCapability.CHASSIS_MOTION,
        source = """
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
                    robot_ctrl.set_mode(rm_define.robot_mode_free)
                    chassis_ctrl.set_trans_speed(0.15)
                    chassis_ctrl.set_rotate_speed(30)
                    stage("点球大战，门将就位；来球由剧情模拟")
                    phrase((0, 0, 4), (60, 150, 255))
                    for shot in range(REPEAT_COUNT):
                        stage("盯住来球……")
                        glow((255, 170, 30), rm_define.effect_breath)
                        time.sleep(1)
                        if shot == 0:
                            stage("判断错了！赶快回扑")
                            chassis_ctrl.move_with_time(90, 0.4)
                            chassis_ctrl.move_with_time(-90, 0.8)
                            chassis_ctrl.move_with_time(90, 0.4)
                        else:
                            stage("扑向左侧，稳稳接住")
                            chassis_ctrl.move_with_time(-90, 0.6)
                            phrase((0, 4, 7), (30, 230, 90))
                            chassis_ctrl.move_with_time(90, 0.6)
                    stage("比赛结束，守门成功")
                    phrase((7, 4, 7), (30, 230, 90))
                finally:
                    chassis_ctrl.stop()
                    glow((0, 0, 0), rm_define.effect_always_off)
        """.trimIndent() + "\n",
    ),
    PresetScript(
        id = "traffic-officer",
        name = Res.string.preset_traffic_officer,
        summary = Res.string.preset_traffic_officer_summary,
        capability = PresetCapability.GIMBAL_MOTION,
        source = """
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
                    robot_ctrl.set_mode(rm_define.robot_mode_free)
                    gimbal_ctrl.set_rotate_speed(30)
                    gimbal_ctrl.recenter()
                    for crossing in range(REPEAT_COUNT):
                        stage("红灯：请在停止线后等待")
                        glow((255, 30, 20))
                        time.sleep(1)
                        stage("左右观察，确认模拟路口无车")
                        gimbal_ctrl.rotate_with_degree(rm_define.gimbal_left, 30)
                        gimbal_ctrl.rotate_with_degree(rm_define.gimbal_right, 60)
                        gimbal_ctrl.rotate_with_degree(rm_define.gimbal_left, 30)
                        stage("绿灯：行人通行")
                        for pedestrian in range(4):
                            glow((30, 220, 80))
                            media_ctrl.play_sound(NOTES[4])
                            time.sleep(0.5)
                        stage("黄灯：本轮通行结束")
                        for warning in range(3):
                            glow((255, 160, 20))
                            time.sleep(0.25)
                            glow((0, 0, 0))
                            time.sleep(0.25)
                    stage("执勤结束")
                finally:
                    gimbal_ctrl.stop()
                    glow((0, 0, 0), rm_define.effect_always_off)
        """.trimIndent() + "\n",
    ),
    PresetScript(
        id = "bomb-squad",
        name = Res.string.preset_bomb_squad,
        summary = Res.string.preset_bomb_squad_summary,
        capability = PresetCapability.EFFECT,
        source = """
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
        """.trimIndent() + "\n",
    ),
    PresetScript(
        id = "curious-sentry",
        name = Res.string.preset_curious_sentry,
        summary = Res.string.preset_curious_sentry_summary,
        capability = PresetCapability.GIMBAL_MOTION,
        source = """
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
        """.trimIndent() + "\n",
    ),
)

internal const val MAX_SCRIPT_LENGTH = 1_000_000

internal fun validScriptName(value: String): Boolean =
    value.isNotBlank() && value.length <= 64 && value.none { it == '\n' || it == '\r' || it.isISOControl() }

internal fun normalizeScriptName(value: String): String = value.trim().also {
    require(validScriptName(it)) { "Script name must contain 1 to 64 visible characters" }
}

internal fun suggestedScriptFileName(displayName: String?): String {
    val safeBase = displayName.orEmpty().trim()
        .map { if (it.isISOControl() || it in "<>:\"/\\|?*") '_' else it }
        .joinToString("")
        .trimEnd(' ', '.')
        .ifBlank { "script" }
    return if (safeBase.endsWith(".py", ignoreCase = true)) safeBase else "$safeBase.py"
}
