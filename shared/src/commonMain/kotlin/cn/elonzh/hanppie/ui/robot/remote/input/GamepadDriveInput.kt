package cn.elonzh.hanppie.ui.robot.remote.input

import cn.elonzh.hanppie.robot.gamepad.*
import cn.elonzh.hanppie.ui.settings.GamepadInputSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.abs

internal enum class GamepadDriveProblem { NO_DATA, RECENTER, STALE, OWNER_CHANGED, SETTINGS_CHANGED }
internal data class GamepadDriveState(val active: Boolean = false, val repeatPrimary: Boolean = false,
    val problem: GamepadDriveProblem? = null, val fresh: Boolean = false, val gimbalActive: Boolean = false)
internal data class GamepadDriveFrame(val forward: Double = 0.0, val right: Double = 0.0,
    val pitch: Double = 0.0, val yaw: Double = 0.0, val gimbalActive: Boolean = false)

/** Page-scoped owner only; the cockpit's existing 50ms ticker is the sole drive sender.
 * Called on the UI thread. Lost ownership or fresh-data expiry requires an explicit new start.
 */
internal class GamepadDriveInput(private val snapshot: () -> GamepadState, private val acceptsEvent: (GamepadButtonEvent) -> Boolean,
    private val nowMillis: () -> Long,
    private val controlRevision: () -> Long, private val onPrimaryPress: () -> Unit = {},
    private val onSecondaryPress: () -> Unit = {}) {
    val state = MutableStateFlow(GamepadDriveState())
    private var ownerRevision = 0L
    private var connection = 0L
    private var settings = GamepadInputSettings()
    private var primary = false
    private var secondary = false
    private var primaryAt = 0L
    private var eventNumber = 0L

    fun start(value: GamepadInputSettings) {
        stop()
        val current = snapshot()
        val report = current.report
        observe(current)
        if (current.phase != GamepadPhase.CONNECTED || !current.reportFresh || report == null) return fail(GamepadDriveProblem.NO_DATA)
        val moving = report.chassisActive && listOf(report.forward, report.lateral).any { abs(it - 1024) > 13 } ||
            report.gimbalActive && listOf(report.pitch, report.yaw).any { abs(it - 1024) > 13 }
        if (moving || report.buttons and 0xf0c0 != 0) return fail(GamepadDriveProblem.RECENTER)
        settings = value; connection = current.connection; eventNumber = current.reportNumber; ownerRevision = controlRevision()
        state.value = state.value.copy(active = true)
    }
    fun stop() { primary = false; secondary = false; state.value = state.value.copy(active = false, repeatPrimary = false, problem = null) }
    private fun fail(problem: GamepadDriveProblem) { stop(); state.value = state.value.copy(problem = problem) }
    private fun observe(current: GamepadState) {
        val fresh = current.phase == GamepadPhase.CONNECTED && current.reportFresh
        state.value = state.value.copy(fresh = fresh, gimbalActive = fresh && current.report?.gimbalActive == true)
    }

    fun event(event: GamepadButtonEvent, value: GamepadInputSettings, allowed: Boolean) {
        if (!state.value.active || event.number <= eventNumber || event.connection != connection || !acceptsEvent(event)) return
        if (!allowed || ownerRevision != controlRevision()) return
        if (value != settings) return
        eventNumber = event.number
        val report = event.report
        val held = report.fire || report.mouseConnected && report.mouseLeft
        val zoom = report.mouseConnected && report.mouseRight
        if (held && !primary) { primaryAt = nowMillis(); onPrimaryPress() }
        if (zoom && !secondary) onSecondaryPress()
        primary = held; secondary = zoom
        if (!held) state.value = state.value.copy(repeatPrimary = false)
    }

    fun frame(value: GamepadInputSettings, allowed: Boolean): GamepadDriveFrame {
        val current = snapshot()
        observe(current)
        if (!state.value.active) return GamepadDriveFrame()
        if (!allowed || ownerRevision != controlRevision() || current.connection != connection) {
            fail(GamepadDriveProblem.OWNER_CHANGED); return GamepadDriveFrame()
        }
        if (value != settings) { fail(GamepadDriveProblem.SETTINGS_CHANGED); return GamepadDriveFrame() }
        val report = current.report
        if (current.phase != GamepadPhase.CONNECTED || !current.reportFresh || report == null) {
            fail(GamepadDriveProblem.STALE); return GamepadDriveFrame()
        }
        val held = report.fire || report.mouseConnected && report.mouseLeft
        state.value = state.value.copy(repeatPrimary = held && primary && nowMillis() - primaryAt >= 250)
        return GamepadDriveFrame(
            if (report.chassisActive) value.forward(report.forward) else 0.0,
            if (report.chassisActive) value.lateral(report.lateral) else 0.0,
            if (report.gimbalActive) value.pitch.apply(report.pitch) else 0.0,
            if (report.gimbalActive) value.yaw.apply(report.yaw) else 0.0,
            report.gimbalActive)
    }
}
