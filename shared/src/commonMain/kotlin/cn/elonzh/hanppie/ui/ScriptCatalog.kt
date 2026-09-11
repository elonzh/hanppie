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
        id = "battery-mood-show",
        name = Res.string.preset_battery_mood_show,
        summary = Res.string.preset_battery_mood_show_summary,
        capability = PresetCapability.EFFECT,
        source = """
            REPEAT_COUNT = 3

            def start():
                percentage = robot_ctrl.get_battery_percentage()
                if percentage is None:
                    color = (80, 120, 255)
                    sound = rm_define.media_sound_scanning
                    mood = "unknown"
                elif percentage >= 60:
                    color = (40, 220, 100)
                    sound = rm_define.media_sound_recognize_success
                    mood = "energetic"
                elif percentage >= 25:
                    color = (255, 170, 30)
                    sound = rm_define.media_sound_count_down
                    mood = "careful"
                else:
                    color = (255, 50, 60)
                    sound = rm_define.media_sound_attacked
                    mood = "hungry"

                log_ctrl.print_msg("Battery mood: %s (%s%%)" % (mood, percentage))
                try:
                    for round_index in range(REPEAT_COUNT):
                        log_ctrl.print_msg("Mood pulse %s/%s" % (round_index + 1, REPEAT_COUNT))
                        led_ctrl.set_led(
                            rm_define.armor_all,
                            color[0], color[1], color[2],
                            rm_define.effect_breath
                        )
                        media_ctrl.play_sound(sound, wait_for_complete=True)
                        time.sleep(0.25)
                finally:
                    led_ctrl.set_led(
                        rm_define.armor_all, 0, 0, 0,
                        rm_define.effect_always_off
                    )
        """.trimIndent() + "\n",
    ),
    PresetScript(
        id = "rainbow-melody",
        name = Res.string.preset_rainbow_melody,
        summary = Res.string.preset_rainbow_melody_summary,
        capability = PresetCapability.EFFECT,
        source = """
            REPEAT_COUNT = 2
            COLORS = (
                (255, 40, 70),
                (255, 140, 30),
                (255, 220, 40),
                (40, 220, 100),
                (50, 140, 255),
                (170, 80, 255),
                (255, 70, 180),
                (80, 210, 255),
            )
            NOTES = (
                rm_define.media_sound_solmization_1C,
                rm_define.media_sound_solmization_1D,
                rm_define.media_sound_solmization_1E,
                rm_define.media_sound_solmization_1F,
                rm_define.media_sound_solmization_1G,
                rm_define.media_sound_solmization_1A,
                rm_define.media_sound_solmization_1B,
                rm_define.media_sound_solmization_2C,
            )

            def start():
                try:
                    for round_index in range(REPEAT_COUNT):
                        log_ctrl.print_msg("Rainbow round %s/%s" % (round_index + 1, REPEAT_COUNT))
                        for index in range(len(NOTES)):
                            log_ctrl.print_msg("Note %s/%s" % (index + 1, len(NOTES)))
                            color = COLORS[index]
                            led_ctrl.set_led(
                                rm_define.armor_all,
                                color[0], color[1], color[2],
                                rm_define.effect_always_on
                            )
                            media_ctrl.play_sound(
                                NOTES[index],
                                wait_for_complete=True
                            )
                finally:
                    led_ctrl.set_led(
                        rm_define.armor_all, 0, 0, 0,
                        rm_define.effect_always_off
                    )
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
    PresetScript(
        id = "square-parade",
        name = Res.string.preset_square_parade,
        summary = Res.string.preset_square_parade_summary,
        capability = PresetCapability.CHASSIS_MOTION,
        source = """
            REPEAT_COUNT = 2
            COLORS = (
                (255, 70, 90),
                (60, 180, 255),
                (80, 230, 120),
                (255, 180, 40),
            )

            def start():
                robot_ctrl.set_mode(rm_define.robot_mode_free)
                chassis_ctrl.set_trans_speed(0.25)
                chassis_ctrl.set_rotate_speed(45)
                try:
                    media_ctrl.play_sound(
                        rm_define.media_sound_count_down,
                        wait_for_complete=True
                    )
                    for lap in range(REPEAT_COUNT):
                        log_ctrl.print_msg("Square parade lap %s" % (lap + 1))
                        for side in range(4):
                            log_ctrl.print_msg("Lap %s/%s, corner %s/4" % (lap + 1, REPEAT_COUNT, side + 1))
                            color = COLORS[side]
                            led_ctrl.set_led(
                                rm_define.armor_all,
                                color[0], color[1], color[2],
                                rm_define.effect_always_on
                            )
                            chassis_ctrl.move_with_time(0, 0.8)
                            chassis_ctrl.rotate_with_degree(
                                rm_define.clockwise, 90
                            )
                    media_ctrl.play_sound(
                        rm_define.media_sound_recognize_success,
                        wait_for_complete=True
                    )
                finally:
                    chassis_ctrl.stop()
                    led_ctrl.set_led(
                        rm_define.armor_all, 0, 0, 0,
                        rm_define.effect_always_off
                    )
        """.trimIndent() + "\n",
    ),
    PresetScript(
        id = "victory-dance",
        name = Res.string.preset_victory_dance,
        summary = Res.string.preset_victory_dance_summary,
        capability = PresetCapability.CHASSIS_MOTION,
        source = """
            REPEAT_COUNT = 3

            def start():
                robot_ctrl.set_mode(rm_define.robot_mode_free)
                chassis_ctrl.set_trans_speed(0.20)
                chassis_ctrl.set_rotate_speed(35)
                gimbal_ctrl.set_rotate_speed(55)
                try:
                    for round_index in range(REPEAT_COUNT):
                        log_ctrl.print_msg("Victory dance %s/%s: side step" % (round_index + 1, REPEAT_COUNT))
                        led_ctrl.set_led(
                            rm_define.armor_all, 255, 60, 190,
                            rm_define.effect_marquee
                        )
                        media_ctrl.play_sound(
                            rm_define.media_sound_solmization_1C,
                            wait_for_complete=True
                        )
                        chassis_ctrl.move_with_time(90, 0.35)
                        chassis_ctrl.move_with_time(-90, 0.70)
                        chassis_ctrl.move_with_time(90, 0.35)
                        gimbal_ctrl.rotate_with_degree(
                            rm_define.gimbal_right, 25
                        )
                        log_ctrl.print_msg("Victory dance %s/%s: spin" % (round_index + 1, REPEAT_COUNT))
                        gimbal_ctrl.rotate_with_degree(
                            rm_define.gimbal_left, 50
                        )
                        gimbal_ctrl.rotate_with_degree(
                            rm_define.gimbal_right, 25
                        )
                        chassis_ctrl.rotate_with_degree(
                            rm_define.clockwise, 45
                        )
                        chassis_ctrl.rotate_with_degree(
                            rm_define.anticlockwise, 45
                        )
                    media_ctrl.play_sound(
                        rm_define.media_sound_recognize_success,
                        wait_for_complete=True
                    )
                finally:
                    chassis_ctrl.stop()
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
