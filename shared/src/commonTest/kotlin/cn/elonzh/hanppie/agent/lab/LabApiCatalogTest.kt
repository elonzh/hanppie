package cn.elonzh.hanppie.agent.lab

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LabApiCatalogTest {
    @Test fun indexIsBoundedAndNamesEveryVerifiedCategory() {
        val index = LabApiCatalog.query("index")

        assertEquals(
            listOf("runtime", "chassis", "gimbal", "robot", "led", "media", "logging", "gun"),
            index.availableCategories,
        )
        assertTrue(index.inCatalog)
        assertTrue(index.sections.isEmpty())
    }

    @Test fun multipleRelevantCategoriesCanBeQueriedBeforeWritingOneScript() {
        val result = LabApiCatalog.query("底盘 灯光 日志")

        val facts = result.facts()
        assertEquals(listOf("chassis", "led", "logging"), result.sections.map { it.category })
        assertContains(facts, "chassis_ctrl.move_with_time")
        assertContains(facts, "led_ctrl.set_led")
        assertContains(facts, "log_ctrl.print_msg")
        assertFalse(facts.contains("gun_ctrl.fire_once"))
    }

    @Test fun unknownApisAreNotInvented() {
        val result = LabApiCatalog.query("teleport")

        assertFalse(result.inCatalog)
        assertContains(result.guidance, "不要猜测接口")
        assertFalse(result.facts().contains("teleport("))
    }

    @Test fun catalogCoversEveryControllerCallUsedByCurrentPresets() {
        val catalog = listOf("runtime", "chassis", "gimbal", "robot", "led", "media", "logging", "gun")
            .joinToString("\n") { LabApiCatalog.query(it).facts() }

        listOf(
            "chassis_ctrl.move_with_time",
            "chassis_ctrl.rotate_with_degree",
            "chassis_ctrl.set_rotate_speed",
            "chassis_ctrl.set_trans_speed",
            "chassis_ctrl.stop",
            "gimbal_ctrl.recenter",
            "gimbal_ctrl.rotate_with_degree",
            "gimbal_ctrl.set_rotate_speed",
            "gimbal_ctrl.stop",
            "led_ctrl.set_led",
            "log_ctrl.print_msg",
            "media_ctrl.play_sound",
            "robot_ctrl.get_battery_percentage",
            "robot_ctrl.set_mode",
        ).forEach { api -> assertContains(catalog, api) }
    }

    @Test fun keywordArgumentNamesMatchTheRecoveredLabRuntime() {
        val chassis = LabApiCatalog.query("chassis").facts()
        val gimbal = LabApiCatalog.query("gimbal").facts()

        assertContains(chassis, "move_with_time(direction_angle, time_wait)")
        assertContains(chassis, "rotate_with_time(direction, time_wait)")
        assertContains(chassis, "move_with_speed(speed_x, speed_y, speed_z)")
        assertContains(gimbal, "set_rotate_speed(speed, speed2=None)")
        assertFalse(chassis.contains("move_with_time(direction_angle, seconds)"))
        assertFalse(chassis.contains("move_with_speed(x, y, z)"))
    }

    private fun cn.elonzh.hanppie.agent.tools.LabApiReferenceTool.Result.facts(): String =
        sections.flatMap { it.facts }.joinToString("\n")
}
