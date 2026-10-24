package cn.elonzh.hanppie.ui.scripts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.robot.lab.ui.*
import cn.elonzh.hanppie.ui.app.ConsoleController
import cn.elonzh.hanppie.ui.design.WorkbenchGlyph
import cn.elonzh.hanppie.ui.design.WorkbenchIcon
import cn.elonzh.hanppie.ui.design.WorkbenchIconButton
import cn.elonzh.hanppie.ui.design.adjustableValue
import cn.elonzh.hanppie.ui.i18n.tr
import cn.elonzh.hanppie.ui.robot.device.ConnectionStatusChip
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowListPopup

/** The host toolbar stays outside the authored layout; controls retain their own run/widget identity. */
@Composable
internal fun LabUiPanel(model: ConsoleController, state: LabUiState, title: String, visible: Boolean,
                        onBack: () -> Unit, onMonitor: () -> Unit, onConnectionDetails: () -> Unit,
                        modifier: Modifier = Modifier) {
    val runId = state.runId ?: return
    val horizontal = rememberScrollState()
    val vertical = rememberScrollState()
    val scope = rememberCoroutineScope()
    var inputTooLong by remember(runId) { mutableStateOf(false) }
    val foreground by model.foregroundState.collectAsState()
    val robot by model.state.collectAsState()
    val enabled = visible && state.inputEnabled && foreground && robot.connected && !robot.busy
    LaunchedEffect(runId, visible) {
        if (!visible) model.setLabUiVisible(runId, false)
        else while (isActive) { model.setLabUiVisible(runId, true); delay(100) }
    }
    DisposableEffect(runId) { onDispose { model.setLabUiVisible(runId, false) } }
    LaunchedEffect(runId) {
        snapshotFlow { horizontal.maxValue to vertical.maxValue }.first {
            it.first != Int.MAX_VALUE && it.second != Int.MAX_VALUE
        }
        horizontal.scrollTo(horizontal.maxValue / 2)
        vertical.scrollTo(vertical.maxValue / 2)
    }
    val issue = when {
        inputTooLong -> tr(Res.string.program_panel_input_long)
        state.issue == LabUiIssue.SEND_FAILED -> tr(Res.string.program_panel_send_failed)
        state.issue != null -> tr(Res.string.program_panel_update_failed)
        !enabled -> tr(Res.string.program_panel_waiting)
        else -> null
    }
    Column(modifier.testTag("program-panel"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WorkbenchIconButton(tr(Res.string.program_panel_close), WorkbenchGlyph.BACK, onBack, tag = "program-panel-back")
            Column(Modifier.weight(1f)) {
                Text(tr(Res.string.program_panel), fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(title, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            ConnectionStatusChip(robot, onClick = onConnectionDetails)
            WorkbenchIconButton(tr(Res.string.program_panel_center), WorkbenchGlyph.CROSSHAIR, {
                scope.launch {
                    horizontal.animateScrollTo(horizontal.maxValue / 2)
                    vertical.animateScrollTo(vertical.maxValue / 2)
                }
            }, tag = "program-panel-center")
            WorkbenchIconButton(tr(Res.string.script_monitor), WorkbenchGlyph.VIDEO, onMonitor, tag = "program-panel-monitor")
            WorkbenchIconButton(tr(Res.string.stop_script), WorkbenchGlyph.STOP, model::stop,
                danger = true, enabled = robot.canStop, tag = "program-panel-stop")
        }
        issue?.let {
            Text(it, fontSize = 13.sp, color = if (state.issue != null || inputTooLong)
                MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.testTag("program-panel-message"))
        }
        val visible = state.widgets.values.filter { state.isVisible(it.key) }
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(MiuixTheme.colorScheme.surfaceContainer)) {
            if (visible.none { it.key.type != LabWidgetType.STAGE }) {
                Text(tr(Res.string.program_panel_empty), Modifier.align(Alignment.Center).padding(24.dp), textAlign = TextAlign.Center)
            }
            // A one-to-one readable canvas pans instead of shrinking interactive controls to thumbnails.
            Box(Modifier.fillMaxSize().horizontalScroll(horizontal).verticalScroll(vertical).testTag("program-panel-canvas")) {
                Box(Modifier.requiredSize(1920.dp, 1080.dp)) {
                    visible.filter { it.parent == null }.sortedBy { it.layout.order }.forEach { stage ->
                        LabWidgetNode(stage, state, 1920f, 1080f, enabled,
                            onEvent = { widget, event, value, final ->
                                model.labUiInteract(runId, widget.key, widget.token, event, value, final)
                            }, onInputLimit = { inputTooLong = it })
                    }
                }
            }
        }
    }
}

@Composable
private fun LabWidgetNode(widget: LabWidget, state: LabUiState, parentWidth: Float, parentHeight: Float,
                          enabled: Boolean, onEvent: (LabWidget, Int, LabUiValue?, Boolean) -> Unit,
                          onInputLimit: (Boolean) -> Unit) {
    val layout = widget.layout
    val left = parentWidth / 2 + layout.x - layout.width * layout.pivotX
    val top = parentHeight / 2 - layout.y - layout.height * (1 - layout.pivotY)
    val modifier = Modifier.offset(left.dp, top.dp).requiredSize(layout.width.dp, layout.height.dp)
        .zIndex(layout.order.toFloat()).graphicsLayer {
            rotationZ = -layout.rotation
            transformOrigin = TransformOrigin(layout.pivotX, 1 - layout.pivotY)
        }.testTag("program-widget-${widget.key.type.raw}-${widget.key.index}")
    key(widget.token) {
        Box(modifier) {
            if (widget.key.type == LabWidgetType.STAGE) {
                state.widgets.values.filter { it.parent == widget.key && state.isVisible(it.key) }
                    .sortedBy { it.layout.order }.forEach {
                        LabWidgetNode(it, state, layout.width, layout.height, enabled, onEvent, onInputLimit)
                    }
            } else {
                LabWidgetContent(widget, enabled, onEvent, onInputLimit)
            }
        }
    }
}

@Composable
private fun LabWidgetContent(widget: LabWidget, enabled: Boolean,
                             onEvent: (LabWidget, Int, LabUiValue?, Boolean) -> Unit, onInputLimit: (Boolean) -> Unit) {
    fun event(type: Int, value: LabUiValue? = null, final: Boolean = false) = onEvent(widget, type, value, final)
    val colors = MiuixTheme.colorScheme
    when (widget.key.type) {
        LabWidgetType.BUTTON -> LabPressButton(widget, enabled) { event(it) }
        LabWidgetType.TOGGLE -> Row(Modifier.fillMaxSize()
            .background(widget.background.colorOr(colors.secondaryVariant), RoundedCornerShape(12.dp))
            .toggleable(widget.checked, enabled = enabled, role = Role.Checkbox) { event(0, LabUiValue.Flag(it)) }
            .testTag("program-toggle-${widget.key.index}"),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Checkbox(if (widget.checked) ToggleableState.On else ToggleableState.Off,
                    onClick = null, enabled = enabled,
                    colors = CheckboxDefaults.checkboxColors(
                        uncheckedBackgroundColor = colors.onSurfaceVariantSummary.copy(alpha = .85f),
                        checkedForegroundColor = widget.checkmark.colorOr(colors.onPrimary)))
            }
            WidgetLabel(widget.text, widget.textStyle, Modifier.weight(1f).fillMaxHeight().padding(end = 8.dp))
        }
        LabWidgetType.SLIDER -> {
            var value by remember(widget.token) { mutableIntStateOf(widget.value) }
            LaunchedEffect(widget.value) { value = widget.value }
            val span = widget.maximum.toDouble() - widget.minimum.toDouble()
            val fraction = if (span == 0.0) 0f else ((value.toDouble() - widget.minimum) / span).toFloat()
            // Compose ranges are Float: adjacent large Int endpoints can collapse to one value.
            val fractionalRange = span > 0 && widget.minimum.toFloat() == widget.maximum.toFloat()
            val semanticRange = if (fractionalRange) 0f..1f else widget.minimum.toFloat()..widget.maximum.toFloat()
            Slider(fraction, {
                if (enabled && span > 0) {
                    value = (widget.minimum.toDouble() + it.toDouble() * span).toInt().coerceIn(widget.minimum, widget.maximum)
                    event(0, LabUiValue.Integer(value))
                }
            }, Modifier.fillMaxSize().testTag("program-slider-${widget.key.index}")
                .adjustableValue(widget.text.takeIf { it.isNotBlank() } ?: tr(Res.string.program_panel_value),
                    if (fractionalRange) fraction else value.toFloat(), semanticRange, value.toString(), enabled && span > 0,
                    onChange = {
                        value = (if (fractionalRange) (widget.minimum.toDouble() + it.toDouble() * span).toInt() else it.toInt())
                            .coerceIn(widget.minimum, widget.maximum)
                        event(0, LabUiValue.Integer(value))
                    }, onFinished = { event(0, LabUiValue.Integer(value), final = true) }),
                enabled && span > 0,
                onValueChangeFinished = { if (enabled && span > 0) event(0, LabUiValue.Integer(value), final = true) },
                colors = SliderDefaults.sliderColors(foregroundColor = widget.fill.colorOr(colors.primary),
                    backgroundColor = widget.background.colorOr(colors.sliderBackground), thumbColor = widget.handle.colorOr(colors.onPrimary)))
        }
        LabWidgetType.DROPDOWN -> LabDropdown(widget, enabled) { event(0, LabUiValue.Integer(it)) }
        LabWidgetType.TEXT -> {
            var surface = Modifier.fillMaxSize()
            if (widget.backgroundVisible) surface = surface.background(widget.background.colorOr(Color.Transparent))
            if (widget.borderVisible) surface = surface.border(1.dp, widget.border.colorOr(colors.onSurfaceVariantSummary))
            WidgetLabel(widget.text, widget.textStyle, surface)
        }
        LabWidgetType.INPUT -> LabInput(widget, enabled, onInputLimit) { event(0, LabUiValue.Text(it)) }
        LabWidgetType.STAGE -> Unit
    }
}

/** Click, down and up are separate protocol events; cancellation/focus loss sends only up. */
@Composable
private fun LabPressButton(widget: LabWidget, enabled: Boolean, event: (Int) -> Unit) {
    val latest by rememberUpdatedState(event)
    var held by remember(widget.token) { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    fun press() { if (!held) { held = true; latest(1) } }
    fun release() { if (held) { held = false; latest(2) } }
    DisposableEffect(widget.token) { onDispose { release() } }
    LaunchedEffect(enabled) { if (!enabled) release() }
    val colors = MiuixTheme.colorScheme
    val base = widget.background.colorOr(colors.secondaryVariant)
    var modifier = Modifier.fillMaxSize().background(
        if (!enabled) colors.disabledSecondaryVariant else if (held) base.copy(alpha = .7f) else base, RoundedCornerShape(16.dp))
    if (focused || hovered) modifier = modifier.border(if (focused) 2.dp else 1.dp, colors.primary, RoundedCornerShape(16.dp))
    Box(modifier.focusRequester(focus).onFocusChanged { focused = it.isFocused; if (!it.isFocused) release() }
        .onKeyEvent {
            if (!enabled || it.key !in listOf(Key.Enter, Key.NumPadEnter, Key.Spacebar)) false
            else {
                if (it.type == KeyEventType.KeyDown) press()
                if (it.type == KeyEventType.KeyUp && held) { release(); latest(0) }
                true
            }
        }.hoverable(interactions, enabled).focusable(enabled)
        .semantics(mergeDescendants = true) {
            role = Role.Button
            if (!enabled) disabled() else onClick { latest(0); true }
        }.pointerInput(enabled, widget.token) {
            if (enabled) detectTapGestures(onPress = {
                focus.requestFocus(); press()
                val clicked = try { tryAwaitRelease() } finally { release() }
                if (clicked) latest(0)
            })
        }) {
        WidgetLabel(widget.text, widget.textStyle, Modifier.fillMaxSize().padding(horizontal = 8.dp))
    }
}

@Composable
private fun LabDropdown(widget: LabWidget, enabled: Boolean, select: (Int) -> Unit) {
    var expanded by remember(widget.token) { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) expanded = false }
    val colors = MiuixTheme.colorScheme
    Box(Modifier.fillMaxSize()) {
        Button({ expanded = true }, Modifier.fillMaxSize().testTag("program-dropdown-${widget.key.index}"),
            enabled = enabled && widget.options.isNotEmpty(), minHeight = 0.dp, minWidth = 0.dp,
            insideMargin = PaddingValues(horizontal = 12.dp),
            colors = ButtonDefaults.buttonColors(color = widget.background.colorOr(colors.secondaryVariant))) {
            WidgetLabel(widget.options.getOrNull(widget.value).orEmpty(), widget.textStyle, Modifier.weight(1f).fillMaxHeight())
            WorkbenchIcon(WorkbenchGlyph.CHEVRON_RIGHT, color = widget.arrow.colorOr(colors.onSecondaryVariant),
                modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = 90f })
        }
        WindowListPopup(expanded, onDismissRequest = { expanded = false }) {
            ListPopupColumn {
                widget.options.forEachIndexed { index, option ->
                    Box(Modifier.heightIn(min = 48.dp).testTag("program-option-${widget.key.index}-$index")) {
                        DropdownImpl(DropdownItem(option), widget.options.size, widget.value == index, index,
                            dropdownColors = DropdownDefaults.dropdownColors(
                                contentColor = widget.itemText.colorOr(colors.onSurfaceContainer),
                                selectedContentColor = widget.itemText.colorOr(colors.primary),
                                containerColor = widget.itemBackground.colorOr(colors.surfaceContainer),
                                selectedContainerColor = widget.itemBackground.colorOr(colors.surfaceContainer),
                                selectedIndicatorColor = widget.itemCheckmark.colorOr(colors.primary)),
                            onSelectedIndexChange = { expanded = false; select(index) })
                    }
                }
            }
        }
    }
}

@Composable
private fun LabInput(widget: LabWidget, enabled: Boolean, onLimit: (Boolean) -> Unit, change: (String) -> Unit) {
    var draft by remember(widget.token) { mutableStateOf(TextFieldValue(widget.text)) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(widget.text) { if (draft.text != widget.text) draft = TextFieldValue(widget.text) }
    val colors = MiuixTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    Box(Modifier.fillMaxSize().background(widget.background.colorOr(colors.secondaryContainer), shape)
        .then(if (focused) Modifier.border(1.dp, colors.primary, shape) else Modifier).padding(horizontal = 12.dp),
        contentAlignment = widget.textStyle.alignment.anchor()) {
        if (draft.text.isEmpty()) WidgetLabel(widget.hint, widget.hintStyle, Modifier.fillMaxSize())
        BasicTextField(draft, { next ->
            val tooLong = next.text.encodeToByteArray().size > 255
            onLimit(tooLong)
            if (!tooLong) { draft = next; if (next.text != widget.text) change(next.text) }
        }, Modifier.fillMaxWidth().testTag("program-input-${widget.key.index}").onFocusChanged { focused = it.isFocused },
            enabled = enabled, textStyle = widget.textStyle.textStyle(colors.onSurface),
            cursorBrush = SolidColor(colors.primary))
    }
}

@Composable
private fun WidgetLabel(text: String, style: LabUiTextStyle, modifier: Modifier) {
    Box(modifier, contentAlignment = style.alignment.anchor()) {
        Text(text, Modifier.fillMaxWidth(), style = style.textStyle(MiuixTheme.colorScheme.onSurface))
    }
}

private fun LabUiColor?.colorOr(fallback: Color) = this?.let { Color(it.red, it.green, it.blue, it.alpha) } ?: fallback
private fun LabUiTextStyle.textStyle(fallback: Color) = TextStyle(color = color.colorOr(fallback), fontSize = size.sp,
    textAlign = when (alignment % 3) { 0 -> TextAlign.Start; 2 -> TextAlign.End; else -> TextAlign.Center })
private fun Int.anchor(): Alignment = when (this) {
    0 -> Alignment.TopStart; 1 -> Alignment.TopCenter; 2 -> Alignment.TopEnd
    3 -> Alignment.CenterStart; 5 -> Alignment.CenterEnd
    6 -> Alignment.BottomStart; 7 -> Alignment.BottomCenter; 8 -> Alignment.BottomEnd
    else -> Alignment.Center
}
