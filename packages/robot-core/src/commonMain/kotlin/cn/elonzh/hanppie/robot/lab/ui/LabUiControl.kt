package cn.elonzh.hanppie.robot.lab.ui

import cn.elonzh.hanppie.robot.protocol.DussFrame
import cn.elonzh.hanppie.robot.protocol.Protocol
import cn.elonzh.hanppie.robot.telemetry.Telemetry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** All calls share the App session transmit lock. No UI callback can retain an old widget owner. */
internal class LabUiControl(
    private val now: () -> Long,
    private val acknowledge: (DussFrame) -> Unit,
    private val sendEvent: (ByteArray) -> Unit,
) : RobotLabUiService {
    private val mutableState = MutableStateFlow(LabUiState())
    override val state = mutableState.asStateFlow()
    private val beforeCreate = mutableMapOf<LabWidgetKey, MutableList<LabUiAttribute>>()
    private val held = mutableSetOf<LabWidgetKey>()
    private val lastSliderEvent = mutableMapOf<LabWidgetKey, Long>()
    private var incarnation = 0L
    private var visible = false
    private var inputDeadline = 0L
    private var fresh = false
    private var closed = false
    private var started = false

    fun reset(runId: String? = null) {
        held.clear(); beforeCreate.clear(); lastSliderEvent.clear()
        visible = false; inputDeadline = 0; started = false
        mutableState.value = LabUiState(runId = runId)
    }

    fun close() { reset(); closed = true }

    override fun setVisible(runId: String, visible: Boolean) {
        if (closed || state.value.runId != runId) return
        this.visible = visible
        if (visible) inputDeadline = now() + 500 else releaseHeld()
        updateInput()
    }

    fun tick(fresh: Boolean) {
        this.fresh = fresh
        if (!fresh || now() >= inputDeadline) releaseHeld()
        updateInput()
    }

    private fun updateInput() {
        mutableState.value = state.value.copy(inputEnabled = !closed && visible && fresh &&
            state.value.running && now() < inputDeadline)
    }

    fun receive(frame: DussFrame) {
        if (closed || !frame.valid) return
        if (frame.receiver == Protocol.HOST_MOBILE &&
            frame.set == Protocol.CMDSET_RM && frame.id == Protocol.CMD_RM_SCRIPT_BLOCK_STATUS_PUSH && frame.attr == Protocol.ATTR_NO_ACK) {
            val status = Telemetry.labScriptStatus(frame) ?: return
            if (state.value.runId == null || !status.guid.equals(state.value.runId, ignoreCase = true)) return
            // The native runtime can publish this GUID as idle immediately before RUNNING.
            if (status.isFailed || (status.isIdle && started)) reset() else if (!status.isIdle) {
                if (status.isRunning) started = true
                fresh = true
                mutableState.value = state.value.copy(running = status.isRunning)
                updateInput()
            }
            return
        }
        if (frame.sender != Protocol.HOST_SCRATCH_SCRIPT || frame.receiver != Protocol.HOST_MOBILE ||
            frame.attr != Protocol.ATTR_NEED_ACK || frame.set != Protocol.CMDSET_RM || frame.id != LabUiProtocol.ATTRIBUTE) return
        // The fixed native App acknowledges receipt before dispatch; this is not an apply confirmation.
        acknowledge(frame)
        if (state.value.runId == null) return
        val attribute = LabUiProtocol.attribute(frame.payload)
        if (attribute == null) { issue(LabUiIssue.INVALID_ATTRIBUTE); return }
        val type = LabWidgetType.entries.firstOrNull { it.raw == attribute.type }
        if (type == null) { issue(LabUiIssue.UNSUPPORTED_ATTRIBUTE); return }
        val key = LabWidgetKey(type, attribute.index)
        try {
            if (attribute.function == 0) {
                require(attribute.parameters.isEmpty())
                if (key !in state.value.widgets) {
                    val layout = when (type) {
                        LabWidgetType.STAGE -> LabWidgetLayout(width = 1920f, height = 1080f)
                        LabWidgetType.BUTTON -> LabWidgetLayout(width = 200f)
                        LabWidgetType.SLIDER -> LabWidgetLayout(y = 113f, width = 500f, height = 60f)
                        LabWidgetType.DROPDOWN -> LabWidgetLayout(y = 15.6f)
                        LabWidgetType.TEXT -> LabWidgetLayout(y = 7f)
                        else -> LabWidgetLayout()
                    }
                    put(LabWidget(key, ++incarnation, layout, attached = type == LabWidgetType.STAGE,
                        name = "${type.name}_${key.index}"))
                    beforeCreate.remove(key)?.forEach {
                        try { apply(key, it) }
                        catch (_: IllegalArgumentException) { issue(LabUiIssue.INVALID_ATTRIBUTE) }
                        catch (_: UnsupportedOperationException) { issue(LabUiIssue.UNSUPPORTED_ATTRIBUTE) }
                    }
                }
            } else if (attribute.function == 1) {
                require(attribute.parameters.isEmpty())
                remove(key)
            } else if (key !in state.value.widgets) {
                if (beforeCreate.values.sumOf { it.size } >= 512) issue(LabUiIssue.RESOURCE_LIMIT)
                else beforeCreate.getOrPut(key) { mutableListOf() }.add(attribute)
            } else apply(key, attribute)
        } catch (_: IllegalArgumentException) { issue(LabUiIssue.INVALID_ATTRIBUTE) }
        catch (_: UnsupportedOperationException) { issue(LabUiIssue.UNSUPPORTED_ATTRIBUTE) }
        val hidden = held.filter { !state.value.isVisible(it) }
        hidden.forEach { release(it) }
    }

    private fun apply(key: LabWidgetKey, attribute: LabUiAttribute) {
        val widget = state.value.widgets[key] ?: return
        val p = attribute.parameters
        fun count(size: Int) { require(p.size == size) }
        fun integer(index: Int) = (p.getOrNull(index) as? LabUiValue.Integer)?.value ?: throw IllegalArgumentException()
        fun decimal(index: Int) = (p.getOrNull(index) as? LabUiValue.Decimal)?.value ?: throw IllegalArgumentException()
        fun pivot(index: Int) = when (val value = p.getOrNull(index)) {
            is LabUiValue.Decimal -> value.value
            is LabUiValue.Integer -> value.value.toFloat()
            else -> throw IllegalArgumentException()
        }
        fun text(index: Int) = (p.getOrNull(index) as? LabUiValue.Text)?.value ?: throw IllegalArgumentException()
        fun flag(index: Int) = (p.getOrNull(index) as? LabUiValue.Flag)?.value ?: throw IllegalArgumentException()
        fun color(): LabUiColor {
            count(4)
            val values = (0..3).map { integer(it).also { value -> require(value in 0..255) } }
            return LabUiColor(values[0], values[1], values[2], values[3])
        }
        fun align() = integer(0).also { require(it in 0..8) }
        fun fontSize() = integer(0).also { require(it in 1..512) }
        fun one() { count(1) }
        val next = when (attribute.function) {
            2 -> { one(); widget.copy(active = flag(0)) }
            3 -> { one(); widget.copy(name = text(0)) }
            4 -> { count(2); widget.copy(layout = widget.layout.copy(x = decimal(0), y = decimal(1))) }
            5 -> { count(2); val w = integer(0); val h = integer(1); require(w in 0..32768 && h in 0..32768)
                widget.copy(layout = widget.layout.copy(width = w.toFloat(), height = h.toFloat())) }
            6 -> { one(); widget.copy(layout = widget.layout.copy(rotation = decimal(0) % 360)) }
            7 -> { count(2); widget.copy(layout = widget.layout.copy(pivotX = pivot(0), pivotY = pivot(1))) }
            8 -> { one(); widget.copy(layout = widget.layout.copy(order = integer(0))) }
            else -> when (key.type) {
                LabWidgetType.STAGE -> {
                    if (attribute.function !in listOf(128, 129)) throw UnsupportedOperationException()
                    count(2)
                    val childType = LabWidgetType.entries.firstOrNull { it.raw == integer(0) } ?: throw IllegalArgumentException()
                    val childKey = LabWidgetKey(childType, integer(1))
                    val child = state.value.widgets[childKey] ?: return
                    require(childKey != key && !descendants(childKey).contains(key))
                    put(child.copy(parent = if (attribute.function == 128) key else null, attached = attribute.function == 128))
                    return
                }
                LabWidgetType.SLIDER -> when (attribute.function) {
                    128 -> { count(2); val min = integer(0); val max = integer(1); require(min <= max)
                        widget.copy(minimum = min, maximum = max, value = widget.value.coerceIn(min, max)) }
                    129 -> widget.copy(background = color())
                    130 -> widget.copy(fill = color())
                    131 -> widget.copy(handle = color())
                    else -> throw UnsupportedOperationException()
                }
                LabWidgetType.DROPDOWN -> when (attribute.function) {
                    128 -> { require(p.all { it is LabUiValue.Text }); widget.copy(options = p.map { (it as LabUiValue.Text).value }, value = 0) }
                    129 -> widget.copy(textStyle = widget.textStyle.copy(color = color()))
                    130 -> widget.copy(background = color())
                    131 -> widget.copy(arrow = color())
                    132 -> widget.copy(itemText = color())
                    133 -> widget.copy(itemBackground = color())
                    134 -> widget.copy(itemCheckmark = color())
                    else -> throw UnsupportedOperationException()
                }
                else -> when (attribute.function) {
                    128 -> { one(); widget.copy(text = text(0)) }
                    129 -> widget.copy(textStyle = widget.textStyle.copy(color = color()))
                    130 -> { one(); widget.copy(textStyle = widget.textStyle.copy(alignment = align())) }
                    131 -> { one(); widget.copy(textStyle = widget.textStyle.copy(size = fontSize())) }
                    else -> when (key.type) {
                        LabWidgetType.BUTTON -> if (attribute.function == 132) widget.copy(background = color()) else throw UnsupportedOperationException()
                        LabWidgetType.TOGGLE -> when (attribute.function) {
                            132 -> widget.copy(background = color())
                            133 -> widget.copy(checkmark = color())
                            134 -> { one(); widget.copy(checked = flag(0)) }
                            else -> throw UnsupportedOperationException()
                        }
                        LabWidgetType.TEXT -> when (attribute.function) {
                            132 -> widget.copy(border = color())
                            133 -> widget.copy(background = color())
                            134 -> { one(); widget.copy(borderVisible = flag(0)) }
                            135 -> { one(); widget.copy(backgroundVisible = flag(0)) }
                            136 -> { one(); val combined = widget.text + text(0); require(combined.length <= 65536); widget.copy(text = combined) }
                            else -> throw UnsupportedOperationException()
                        }
                        LabWidgetType.INPUT -> when (attribute.function) {
                            132 -> { one(); widget.copy(hint = text(0)) }
                            133 -> widget.copy(hintStyle = widget.hintStyle.copy(color = color()))
                            134 -> { one(); widget.copy(hintStyle = widget.hintStyle.copy(alignment = align())) }
                            135 -> { one(); widget.copy(hintStyle = widget.hintStyle.copy(size = fontSize())) }
                            136 -> widget.copy(background = color())
                            else -> throw UnsupportedOperationException()
                        }
                        else -> throw UnsupportedOperationException()
                    }
                }
            }
        }
        put(next)
    }

    private fun put(widget: LabWidget) {
        mutableState.value = state.value.copy(widgets = state.value.widgets + (widget.key to widget))
    }

    private fun descendants(key: LabWidgetKey): Set<LabWidgetKey> {
        val result = mutableSetOf<LabWidgetKey>()
        fun visit(parent: LabWidgetKey) {
            state.value.widgets.values.filter { it.parent == parent }.forEach { if (result.add(it.key)) visit(it.key) }
        }
        visit(key)
        return result
    }

    private fun remove(key: LabWidgetKey) {
        val removed = descendants(key) + key
        removed.forEach { release(it); beforeCreate.remove(it); lastSliderEvent.remove(it) }
        mutableState.value = state.value.copy(widgets = state.value.widgets - removed)
    }

    override fun interact(runId: String, key: LabWidgetKey, token: Long, event: Int, value: LabUiValue?, final: Boolean) {
        updateInput()
        val current = state.value
        val widget = current.widgets[key] ?: return
        if (current.runId != runId || !current.inputEnabled || widget.token != token || !current.isVisible(key)) return
        val next = when (key.type) {
            LabWidgetType.BUTTON -> {
                require(value == null && event in 0..2)
                if (event == 1 && !held.add(key) || event == 2 && !held.remove(key)) return
                widget
            }
            LabWidgetType.TOGGLE -> { require(event == 0 && value is LabUiValue.Flag); widget.copy(checked = value.value) }
            LabWidgetType.SLIDER -> { require(event == 0 && value is LabUiValue.Integer && value.value in widget.minimum..widget.maximum)
                widget.copy(value = value.value) }
            LabWidgetType.DROPDOWN -> { require(event == 0 && value is LabUiValue.Integer && value.value in widget.options.indices)
                widget.copy(value = value.value) }
            LabWidgetType.INPUT -> { require(event == 0 && value is LabUiValue.Text); widget.copy(text = value.value) }
            else -> return
        }
        val payload = LabUiProtocol.event(key, event, value)
        put(next)
        if (key.type == LabWidgetType.SLIDER && !final && now() - (lastSliderEvent[key] ?: Long.MIN_VALUE / 2) < 200) return
        if (key.type == LabWidgetType.SLIDER) lastSliderEvent[key] = now()
        try { sendEvent(payload) } catch (error: Exception) { issue(LabUiIssue.SEND_FAILED); throw error }
    }

    private fun release(key: LabWidgetKey) {
        if (!held.remove(key)) return
        runCatching { sendEvent(LabUiProtocol.event(key, 2, null)) }.onFailure { issue(LabUiIssue.SEND_FAILED) }
    }

    private fun releaseHeld() { held.toList().forEach(::release) }
    private fun issue(issue: LabUiIssue) { mutableState.value = state.value.copy(issue = issue, issueCount = state.value.issueCount + 1) }
}
