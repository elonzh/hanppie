package cn.elonzh.hanppie.ui

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeTrue
import kotlin.test.*

/** Explicit-target physical acceptance for the bundled gimbal demo and automatic Lab cleanup. */
class ScriptExecutionLiveTest {
    @Test fun curiousSentryReportsProgressCompletesAndCanRunAgain() = runBlocking {
        val ip = System.getenv("HANPPIE_TEST_ROBOT_IP")
        val appId = System.getenv("HANPPIE_TEST_APPID")
        assumeTrue("实机云台测试需要显式目标与运动授权", !ip.isNullOrBlank() && !appId.isNullOrBlank() &&
            System.getenv("HANPPIE_TEST_ALLOW_LAB") == "1" && System.getenv("HANPPIE_TEST_ALLOW_MOTION") == "1")
        val model = ConsoleModel()
        val yawSamples = CopyOnWriteArrayList<Double>()
        val samples = launch { model.state.collect { state -> state.gimbal?.yawDegrees?.let(yawSamples::add) } }
        try {
            model.connect(ip!!, appId!!)
            withTimeout(25_000) { model.state.first { it.connected && !it.busy } }
            val preset = presetScripts.single { it.id == "curious-sentry" }

            model.runScript(preset.source, "好奇哨兵")
            withTimeout(45_000) { model.state.first {
                it.scriptRunPhase in setOf(ScriptRunPhase.COMPLETED, ScriptRunPhase.FAILED, ScriptRunPhase.UNKNOWN)
            } }
            assertEquals(ScriptRunPhase.COMPLETED, model.state.value.scriptRunPhase,
                "首次运行未完成：${model.state.value.scriptMessage}；日志=${model.state.value.scriptMessages}")
            val firstRunId = assertNotNull(model.state.value.scriptRunId)
            assertTrue(model.state.value.scriptMessages.any { it.contains("Sentry scan 3/3: center") })
            assertTrue(yawSamples.isNotEmpty())
            assertTrue(yawSamples.max() - yawSamples.min() >= 20.0,
                "云台 yaw 变化不足：${yawSamples.min()}..${yawSamples.max()}")

            model.runScript(preset.source, "好奇哨兵")
            withTimeout(45_000) { model.state.first {
                it.scriptRunId != firstRunId && it.scriptRunPhase in
                    setOf(ScriptRunPhase.COMPLETED, ScriptRunPhase.FAILED, ScriptRunPhase.UNKNOWN)
            } }
            assertEquals(ScriptRunPhase.COMPLETED, model.state.value.scriptRunPhase,
                "第二次运行未完成：${model.state.value.scriptMessage}；日志=${model.state.value.scriptMessages}")
            assertTrue(model.state.value.scriptMessages.any { it.contains("Sentry scan 3/3: center") })
            println("S1 confirmed two complete curious-sentry runs; yaw=${yawSamples.min()}..${yawSamples.max()}")
        } finally {
            if (model.state.value.canStop) {
                model.stop()
                withTimeoutOrNull(5_000) { model.state.first { !it.scriptRunPhase.mayBeExecuting } }
            }
            samples.cancelAndJoin()
            model.close()
        }
    }
}
