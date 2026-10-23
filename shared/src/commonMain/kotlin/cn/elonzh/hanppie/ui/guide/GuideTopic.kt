package cn.elonzh.hanppie.ui.guide

import cn.elonzh.hanppie.resources.*
import kotlinx.serialization.Serializable
import org.jetbrains.compose.resources.StringResource

/** Read-only navigation intents. The guide cannot issue robot commands. */
internal enum class GuideDestination(val label: StringResource) {
    CONNECTION(Res.string.guide_open_connection), ROBOT(Res.string.guide_open_robot),
    ALBUM(Res.string.guide_open_album), SCRIPTS(Res.string.guide_open_scripts),
    HEALTH(Res.string.guide_open_health), ADDRESSING(Res.string.guide_open_addressing),
    CALIBRATION(Res.string.guide_open_calibration), FIRMWARE(Res.string.guide_open_firmware),
    ARM(Res.string.guide_open_arm), GAMEPAD(Res.string.guide_open_gamepad),
}

internal data class GuideStep(val title: StringResource, val body: StringResource, val destination: GuideDestination? = null)

@Serializable
internal enum class GuideTopic(val title: StringResource, val summary: StringResource, val steps: List<GuideStep>) {
    FIRST_CONNECTION(Res.string.guide_first_connection_title, Res.string.guide_first_connection_summary, listOf(
        GuideStep(Res.string.guide_first_connection_0_title, Res.string.guide_first_connection_0_body, GuideDestination.CONNECTION),
        GuideStep(Res.string.guide_first_connection_1_title, Res.string.guide_first_connection_1_body, GuideDestination.HEALTH),
        GuideStep(Res.string.guide_first_connection_2_title, Res.string.guide_first_connection_2_body, GuideDestination.ROBOT),
    )),
    DRIVING(Res.string.guide_driving_title, Res.string.guide_driving_summary, listOf(
        GuideStep(Res.string.guide_driving_0_title, Res.string.guide_driving_0_body),
        GuideStep(Res.string.guide_driving_1_title, Res.string.guide_driving_1_body),
        GuideStep(Res.string.guide_driving_2_title, Res.string.guide_driving_2_body, GuideDestination.ROBOT),
    )),
    TRACKING(Res.string.guide_tracking_title, Res.string.guide_tracking_summary, listOf(
        GuideStep(Res.string.guide_tracking_0_title, Res.string.guide_tracking_0_body),
        GuideStep(Res.string.guide_tracking_1_title, Res.string.guide_tracking_1_body),
        GuideStep(Res.string.guide_tracking_2_title, Res.string.guide_tracking_2_body, GuideDestination.ROBOT),
    )),
    CHALLENGES(Res.string.guide_challenges_title, Res.string.guide_challenges_summary, listOf(
        GuideStep(Res.string.guide_challenges_0_title, Res.string.guide_challenges_0_body),
        GuideStep(Res.string.guide_challenges_1_title, Res.string.guide_challenges_1_body),
        GuideStep(Res.string.guide_challenges_2_title, Res.string.guide_challenges_2_body, GuideDestination.ROBOT),
    )),
    MEDIA(Res.string.guide_media_title, Res.string.guide_media_summary, listOf(
        GuideStep(Res.string.guide_media_0_title, Res.string.guide_media_0_body, GuideDestination.ROBOT),
        GuideStep(Res.string.guide_media_1_title, Res.string.guide_media_1_body, GuideDestination.ALBUM),
        GuideStep(Res.string.guide_media_2_title, Res.string.guide_media_2_body, GuideDestination.ALBUM),
    )),
    PYTHON(Res.string.guide_python_title, Res.string.guide_python_summary, listOf(
        GuideStep(Res.string.guide_python_0_title, Res.string.guide_python_0_body),
        GuideStep(Res.string.guide_python_1_title, Res.string.guide_python_1_body),
        GuideStep(Res.string.guide_python_2_title, Res.string.guide_python_2_body, GuideDestination.SCRIPTS),
    )),
    ACCESSORIES(Res.string.guide_accessories_title, Res.string.guide_accessories_summary, listOf(
        GuideStep(Res.string.guide_accessories_0_title, Res.string.guide_accessories_0_body, GuideDestination.GAMEPAD),
        GuideStep(Res.string.guide_accessories_1_title, Res.string.guide_accessories_1_body, GuideDestination.ROBOT),
        GuideStep(Res.string.guide_accessories_2_title, Res.string.guide_accessories_2_body, GuideDestination.ARM),
    )),
    MAINTENANCE(Res.string.guide_maintenance_title, Res.string.guide_maintenance_summary, listOf(
        GuideStep(Res.string.guide_maintenance_0_title, Res.string.guide_maintenance_0_body),
        GuideStep(Res.string.guide_maintenance_1_title, Res.string.guide_maintenance_1_body, GuideDestination.HEALTH),
        GuideStep(Res.string.guide_maintenance_2_title, Res.string.guide_maintenance_2_body, GuideDestination.ADDRESSING),
        GuideStep(Res.string.guide_maintenance_3_title, Res.string.guide_maintenance_3_body, GuideDestination.CALIBRATION),
        GuideStep(Res.string.guide_maintenance_4_title, Res.string.guide_maintenance_4_body, GuideDestination.FIRMWARE),
    )),
}
