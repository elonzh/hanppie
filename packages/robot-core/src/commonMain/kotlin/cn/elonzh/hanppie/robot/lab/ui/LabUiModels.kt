package cn.elonzh.hanppie.robot.lab.ui

import kotlinx.coroutines.flow.StateFlow

enum class LabWidgetType(val raw: Int) { STAGE(0), BUTTON(1), TOGGLE(2), SLIDER(3), DROPDOWN(4), TEXT(5), INPUT(6) }
data class LabWidgetKey(val type: LabWidgetType, val index: Int) {
    init { require(index in 0..255) }
}

sealed interface LabUiValue {
    data class Flag(val value: Boolean) : LabUiValue
    data class Integer(val value: Int) : LabUiValue
    data class Decimal(val value: Float) : LabUiValue
    data class Text(val value: String) : LabUiValue
}

data class LabUiColor(val red: Int, val green: Int, val blue: Int, val alpha: Int)
data class LabUiTextStyle(val color: LabUiColor? = null, val alignment: Int = 4, val size: Int = 20)
/** Unity anchored position: centre origin, positive Y upwards; sizes are canvas units. */
data class LabWidgetLayout(val x: Float = 0f, val y: Float = 0f, val width: Float = 300f, val height: Float = 50f,
    val pivotX: Float = .5f, val pivotY: Float = .5f, val rotation: Float = 0f, val order: Int = 0)

data class LabWidget(
    val key: LabWidgetKey,
    val token: Long,
    val layout: LabWidgetLayout,
    val parent: LabWidgetKey? = null,
    val attached: Boolean = false,
    val active: Boolean = true,
    val name: String = "",
    val text: String = "",
    val textStyle: LabUiTextStyle = LabUiTextStyle(),
    val hint: String = "",
    val hintStyle: LabUiTextStyle = LabUiTextStyle(),
    val background: LabUiColor? = null,
    val border: LabUiColor? = null,
    val backgroundVisible: Boolean = true,
    val borderVisible: Boolean = false,
    val checkmark: LabUiColor? = null,
    val fill: LabUiColor? = null,
    val handle: LabUiColor? = null,
    val arrow: LabUiColor? = null,
    val itemText: LabUiColor? = null,
    val itemBackground: LabUiColor? = null,
    val itemCheckmark: LabUiColor? = null,
    val minimum: Int = 0,
    val maximum: Int = 1,
    val value: Int = 0,
    val checked: Boolean = false,
    val options: List<String> = emptyList(),
)

enum class LabUiIssue { INVALID_ATTRIBUTE, UNSUPPORTED_ATTRIBUTE, RESOURCE_LIMIT, SEND_FAILED }
data class LabUiState(val runId: String? = null, val running: Boolean = false,
    val inputEnabled: Boolean = false, val widgets: Map<LabWidgetKey, LabWidget> = emptyMap(),
    val issue: LabUiIssue? = null, val issueCount: Long = 0) {
    fun isVisible(key: LabWidgetKey): Boolean {
        val widget = widgets[key] ?: return false
        return widget.active && widget.attached && widget.layout.width > 0 && widget.layout.height > 0 &&
            (widget.parent?.let(::isVisible) ?: (key.type == LabWidgetType.STAGE))
    }
}

/** The active Lab run owns its widgets; a visible surface renews only the input lease. */
interface RobotLabUiService {
    val state: StateFlow<LabUiState>
    fun setVisible(runId: String, visible: Boolean)
    fun interact(runId: String, key: LabWidgetKey, token: Long, event: Int,
        value: LabUiValue? = null, final: Boolean = false)
}
