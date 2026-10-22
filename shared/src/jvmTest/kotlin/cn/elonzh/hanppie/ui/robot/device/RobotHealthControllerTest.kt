package cn.elonzh.hanppie.ui.robot.device

import cn.elonzh.hanppie.robot.telemetry.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

class RobotHealthControllerTest {
    @Test fun onlyTheCurrentSessionCanPublishHealthAndClearRetiresTheObserver() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val first = MutableStateFlow(RobotHealth(battery = BatteryTelemetry(1, 2, 3, 4)))
            val second = MutableStateFlow(RobotHealth())
            val controller = RobotHealthController(scope)
            controller.attach(first); assertEquals(4, controller.state.value.battery?.percent)
            controller.attach(second); first.value = RobotHealth(battery = BatteryTelemetry(5, 6, 7, 8))
            assertEquals(RobotHealth(), controller.state.value)
            second.value = first.value; assertEquals(8, controller.state.value.battery?.percent)
            controller.clear(); second.value = RobotHealth(battery = BatteryTelemetry(9, 10, 11, 12))
            assertEquals(RobotHealth(), controller.state.value)
            scope.cancel(); controller.attach(first); assertEquals(RobotHealth(), controller.state.value)
        } finally { scope.cancel() }
    }
}
