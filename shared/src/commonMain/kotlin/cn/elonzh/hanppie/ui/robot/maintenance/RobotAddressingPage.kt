package cn.elonzh.hanppie.ui.robot.maintenance

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.robot.maintenance.*
import cn.elonzh.hanppie.ui.app.ConsoleController
import cn.elonzh.hanppie.ui.design.*
import cn.elonzh.hanppie.ui.i18n.tr
import org.jetbrains.compose.resources.StringResource
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class AddressingAction { START, ASSIGN, SKIP, END, EXIT, RECOVER }
private data class AddressingPrompt(val action: AddressingAction, val connection: Long, val kind: ModuleAddressingKind?,
    val id: Int? = null, val candidate: Int? = null, val armorStep: Int = 0)

@Composable
internal fun RobotAddressingPage(model: ConsoleController, modifier: Modifier = Modifier, onBack: () -> Unit,
    exitRequested: Boolean = false, onExitCancelled: () -> Unit = {}, onExitComplete: () -> Unit = onBack) {
    val controller = model.robotAddressing
    val ui by controller.state.collectAsState()
    val console by model.state.collectAsState()
    val health by model.robotHealth.state.collectAsState()
    val camera by model.robotCamera.state.collectAsState()
    val album by model.robotAlbum.state.collectAsState()
    val chat by model.chat.state.collectAsState()
    val talk by model.pushToTalk.state.collectAsState()
    val wifi by model.robotWifi.state.collectAsState()
    val sdr by model.robotSdr.state.collectAsState()
    val task = ui.task
    val canChange = ui.connected && console.connected && !console.busy && !console.scriptRunPhase.mayBeExecuting &&
        !camera.storageBusy && camera.storage?.recording != true && !album.nativeBusy && !chat.running && !talk.active &&
        wifi.recovery == null && sdr.recovery == null
    var selected by remember(ui.connection) { mutableStateOf<ModuleAddressingKind?>(null) }
    var chooseAgain by remember(ui.connection) { mutableStateOf(false) }
    var prompt by remember { mutableStateOf<AddressingPrompt?>(null) }
    fun dismissPrompt() { if (prompt?.action == AddressingAction.EXIT) onExitCancelled(); prompt = null }
    fun request(action: AddressingAction, id: Int? = null) {
        controller.holdWheel(null, ui.connection)
        prompt = AddressingPrompt(action, ui.connection, if (action == AddressingAction.START) selected else task.kind,
            id, task.candidateId, task.armorSteps)
    }
    DisposableEffect(controller, ui.connection) {
        val connection = ui.connection
        onDispose { controller.pageLeft(connection) }
    }
    LaunchedEffect(exitRequested) {
        if (exitRequested && !ui.ending) {
            if (task.kind == ModuleAddressingKind.SENSOR_ADAPTER) {
                onExitCancelled(); controller.end(ui.connection, onExitComplete)
            } else request(AddressingAction.EXIT)
        }
    }
    LaunchedEffect(ui.connection, ui.connected, ui.busy, canChange, task.phase, task.candidateId, task.selectableIds, task.armorSteps) {
        prompt?.let { p ->
            val requiresIdle = p.action !in setOf(AddressingAction.END, AddressingAction.EXIT)
            if (p.connection != ui.connection || !ui.connected || requiresIdle && (ui.busy || !canChange) ||
                p.action == AddressingAction.ASSIGN && (p.candidate != task.candidateId || p.id !in task.selectableIds || task.phase != AddressingPhase.DISCOVERING) ||
                p.action == AddressingAction.SKIP && (p.armorStep != task.armorSteps || task.phase != AddressingPhase.ADDRESSING)) dismissPrompt()
        }
    }
    val pending = prompt
    WorkbenchDialog(show = pending != null, onDismissRequest = ::dismissPrompt,
        title = tr(when (pending?.action) {
            AddressingAction.START -> Res.string.addressing_start_question
            AddressingAction.ASSIGN -> Res.string.addressing_assign_question
            AddressingAction.SKIP -> Res.string.addressing_skip_question
            AddressingAction.RECOVER -> Res.string.addressing_recover_question
            else -> Res.string.addressing_end_question
        }), summary = when (pending?.action) {
            AddressingAction.START -> pending.kind?.let { tr(addressingStartSummary(it)) }
            AddressingAction.ASSIGN -> tr(Res.string.addressing_assign_summary)
            AddressingAction.SKIP -> tr(Res.string.addressing_skip_summary)
            AddressingAction.RECOVER -> tr(when (ui.recovery?.kind) {
                ModuleAddressingKind.SENSOR_ADAPTER -> Res.string.addressing_adapter_end_summary
                ModuleAddressingKind.WHEELS -> Res.string.addressing_recover_summary
                else -> Res.string.addressing_recover_accessory
            })
            else -> tr(if (pending?.kind == ModuleAddressingKind.SENSOR_ADAPTER) Res.string.addressing_adapter_end_summary else Res.string.addressing_end_summary)
        }) {
        if (pending?.action == AddressingAction.ASSIGN) Text(tr(Res.string.addressing_write_id, pending.id ?: 0), fontSize = 16.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(::dismissPrompt, Modifier.weight(1f).heightIn(min = 48.dp).testTag("addressing-confirm-cancel")) { Text(tr(Res.string.cancel)) }
            Button({
                val p = prompt ?: return@Button
                prompt = null
                if (p.action == AddressingAction.EXIT) onExitCancelled()
                val current = controller.state.value
                if (current.connection != p.connection || !current.connected) return@Button
                if (p.action == AddressingAction.ASSIGN && (p.candidate != current.task.candidateId || p.id !in current.task.selectableIds)) return@Button
                if (p.action == AddressingAction.SKIP && p.armorStep != current.task.armorSteps) return@Button
                when (p.action) {
                    AddressingAction.START -> { selected = null; chooseAgain = false; p.kind?.let { controller.begin(it, p.connection) } }
                    AddressingAction.ASSIGN -> p.id?.let { controller.assign(it, p.connection, p.candidate) }
                    AddressingAction.SKIP -> controller.skipArmor(p.connection, p.armorStep)
                    AddressingAction.RECOVER -> if (controller.state.value.task.blocksControl) controller.end(p.connection) else controller.recover(p.connection)
                    AddressingAction.END -> controller.end(p.connection)
                    AddressingAction.EXIT -> controller.end(p.connection, onExitComplete)
                }
            }, Modifier.weight(1f).heightIn(min = 48.dp).testTag("addressing-confirm"), colors = ButtonDefaults.buttonColorsPrimary()) {
                Text(tr(if (pending?.action == AddressingAction.START && pending.kind == ModuleAddressingKind.WHEELS)
                    Res.string.addressing_wheels_ready else Res.string.robot_power_confirm))
            }
        }
    }
    Column(modifier.fillMaxSize().testTag("robot-addressing-page"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().testTag("addressing-header"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WorkbenchIconButton(tr(Res.string.back), WorkbenchGlyph.BACK, onBack, enabled = !ui.ending, tag = "addressing-back")
            Text(tr(Res.string.addressing_title), Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).testTag("addressing-body"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!task.blocksControl && ui.recovery == null) task.problem?.let { Text(tr(addressingProblemText(it)),
                Modifier.testTag("addressing-problem"), fontSize = 15.sp, color = MiuixTheme.colorScheme.error) }
            ui.error?.let { Text(it.resolve(), Modifier.testTag("addressing-error"), fontSize = 15.sp, color = MiuixTheme.colorScheme.error) }
            if (ui.busy) Text(tr(if (ui.ending) Res.string.addressing_ending else Res.string.addressing_working),
                Modifier.testTag("addressing-working"), fontSize = 15.sp)
            val recovery = ui.recovery
            if (recovery != null) {
                Text(tr(Res.string.addressing_interrupted), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(tr(Res.string.addressing_origin, addressingKindName(recovery.kind), recovery.target.ip), fontSize = 15.sp)
                Text(tr(Res.string.addressing_no_replay), fontSize = 15.sp)
                if (!ui.connected) {
                    Button(model::reconnectAddressing, Modifier.heightIn(min = 48.dp).testTag("addressing-reconnect"),
                        enabled = !console.busy && !ui.busy, colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr(Res.string.addressing_reconnect)) }
                } else if (ui.canRecover) {
                    Button({ request(AddressingAction.RECOVER) }, Modifier.heightIn(min = 48.dp).testTag("addressing-recover"),
                        enabled = canChange && !ui.busy, colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr(Res.string.addressing_recover)) }
                } else {
                    Text(tr(Res.string.addressing_wrong_robot), fontSize = 15.sp)
                    Button(model::disconnect, Modifier.heightIn(min = 48.dp), enabled = !console.busy && !ui.busy) { Text(tr(Res.string.disconnect_close_session)) }
                }
            } else if (!ui.connected || !console.connected) {
                Text(tr(Res.string.addressing_connect_first), fontSize = 15.sp)
                Button(model::discover, Modifier.heightIn(min = 48.dp), enabled = !console.busy) { Text(tr(Res.string.automatic_connection)) }
            } else if (task.blocksControl) {
                task.kind?.let { kind ->
                    Text(addressingKindName(kind), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    task.problem?.let { Text(tr(addressingProblemText(it)), Modifier.testTag("addressing-problem"),
                        fontSize = 15.sp, color = MiuixTheme.colorScheme.error) }
                    if (task.phase !in setOf(AddressingPhase.STARTING, AddressingPhase.ENDING, AddressingPhase.UNCERTAIN)) {
                        when (kind) {
                            ModuleAddressingKind.WHEELS -> WheelAddressingContent(controller, ui, canChange)
                            ModuleAddressingKind.ARMOR -> ArmorAddressingContent(task, canChange && !ui.busy) { request(AddressingAction.SKIP) }
                            ModuleAddressingKind.SERVO, ModuleAddressingKind.TOF ->
                                AccessoryAddressingContent(controller, ui, canChange) { id -> request(AddressingAction.ASSIGN, id) }
                            ModuleAddressingKind.SENSOR_ADAPTER -> AdapterAddressingContent(controller, ui, canChange)
                        }
                    }
                }
            } else if (ui.busy) {
                Text(tr(Res.string.addressing_keep_connected), fontSize = 15.sp)
            } else if (selected != null) {
                val kind = checkNotNull(selected)
                Text(addressingKindName(kind), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(tr(addressingPreparation(kind)), Modifier.testTag("addressing-preparation"), fontSize = 15.sp)
                val needsInventory = kind in setOf(ModuleAddressingKind.SERVO, ModuleAddressingKind.TOF, ModuleAddressingKind.SENSOR_ADAPTER)
                if (needsInventory && health.modules == null) Text(tr(Res.string.addressing_wait_inventory), fontSize = 15.sp)
                if (!canChange) Text(tr(Res.string.addressing_task_active), fontSize = 15.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({
                        if (kind == ModuleAddressingKind.SENSOR_ADAPTER) { selected = null; chooseAgain = false; controller.begin(kind, ui.connection) }
                        else request(AddressingAction.START)
                    }, Modifier.heightIn(min = 48.dp).testTag("addressing-start"),
                        enabled = canChange && (!needsInventory || health.modules != null), colors = ButtonDefaults.buttonColorsPrimary()) {
                        Text(tr(if (kind == ModuleAddressingKind.SENSOR_ADAPTER) Res.string.addressing_start_guide else Res.string.addressing_start))
                    }
                    Button({ selected = null; chooseAgain = true }, Modifier.heightIn(min = 48.dp).testTag("addressing-choose-another")) { Text(tr(Res.string.addressing_choose_another)) }
                }
            } else if (task.phase == AddressingPhase.FINISHED && !chooseAgain) {
                Text(tr(if (task.kind == ModuleAddressingKind.SENSOR_ADAPTER) Res.string.addressing_guide_ended
                    else if (task.nativeEndAcknowledged) Res.string.addressing_end_ack else Res.string.addressing_result_unconfirmed),
                    Modifier.testTag("addressing-result"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                if (task.verifiedWheels.isNotEmpty()) Text(tr(Res.string.addressing_wheels_checked, task.verifiedWheels.size), fontSize = 15.sp)
                if (task.verifiedIds.isNotEmpty()) Text(tr(Res.string.addressing_checked_ids, task.verifiedIds.sorted().joinToString(", ")), fontSize = 15.sp)
                if (task.kind == ModuleAddressingKind.ARMOR) Text(tr(Res.string.addressing_armor_report, task.armorSteps, task.skippedArmorSteps.size), fontSize = 15.sp)
                Text(tr(Res.string.addressing_result_check), fontSize = 15.sp)
                Button({ chooseAgain = true }, Modifier.heightIn(min = 48.dp).testTag("addressing-choose-another")) { Text(tr(Res.string.addressing_choose_another)) }
            } else {
                Text(tr(Res.string.addressing_choose), fontSize = 16.sp)
                ModuleAddressingKind.entries.forEach { kind ->
                    Button({ selected = kind }, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("addressing-kind-" + kind.name.lowercase())) {
                        Text(addressingKindName(kind))
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        if (ui.recovery == null && (task.blocksControl || ui.busy)) Button({
            if (task.kind == ModuleAddressingKind.SENSOR_ADAPTER) controller.end(ui.connection) else request(AddressingAction.END)
        },
            Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("addressing-end"), enabled = ui.connected && !ui.ending) {
            Text(tr(if (task.kind == ModuleAddressingKind.SENSOR_ADAPTER) Res.string.addressing_end_guide else Res.string.addressing_end))
        }
    }
}

@Composable
private fun ColumnScope.WheelAddressingContent(controller: RobotAddressingController, ui: AddressingUiState, canChange: Boolean) {
    val task = ui.task
    if (task.phase == AddressingPhase.ADDRESSING) {
        Text(tr(Res.string.addressing_wheel_turn), fontSize = 15.sp)
        Text(tr(Res.string.addressing_wheel_order), fontSize = 15.sp)
        Text(task.wheelPercent?.let { tr(Res.string.addressing_percent, it) } ?: tr(Res.string.addressing_wait_progress),
            Modifier.testTag("addressing-wheel-progress"), fontSize = 16.sp)
        if (task.wheelReportedComplete) Button({ controller.prepareVerification(ui.connection) },
            Modifier.heightIn(min = 48.dp).testTag("addressing-verify"), enabled = canChange && !ui.busy,
            colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr(Res.string.addressing_verify_wheels)) }
    } else if (task.phase == AddressingPhase.VERIFYING) {
        Text(tr(Res.string.addressing_wheel_hold_help), fontSize = 15.sp)
        AddressingWheel.entries.forEach { wheel ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WheelHoldButton(controller, ui.connection, wheel, canChange && !ui.busy && task.wheelTestReady &&
                    (task.wheelHeld == null || task.wheelHeld == wheel), task.wheelHeld == wheel, Modifier.weight(1f))
                Button({ controller.confirmWheel(wheel, ui.connection) }, Modifier.weight(1f).heightIn(min = 48.dp)
                    .testTag("addressing-wheel-confirm-" + wheel.name.lowercase()),
                    enabled = !ui.busy && task.wheelHeld == null && wheel in task.testedWheels && wheel !in task.verifiedWheels) {
                    Text(tr(if (wheel in task.verifiedWheels) Res.string.addressing_checked else Res.string.addressing_wheel_matches))
                }
            }
        }
        Text(tr(Res.string.addressing_wheels_checked, task.verifiedWheels.size), Modifier.testTag("addressing-wheels-checked"), fontSize = 15.sp)
        if (task.wheelHeld != null && !task.wheelDriving) Text(tr(Res.string.addressing_wheel_release), fontSize = 14.sp)
    }
}

@Composable
private fun WheelHoldButton(controller: RobotAddressingController, connection: Long, wheel: AddressingWheel,
    enabled: Boolean, pressed: Boolean, modifier: Modifier = Modifier) {
    val colors = MiuixTheme.colorScheme
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }
    var keyHeld by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val label = tr(Res.string.addressing_hold_wheel, addressingWheelName(wheel))
    DisposableEffect(controller, connection) { onDispose { controller.holdWheel(null, connection) } }
    LaunchedEffect(enabled) { if (!enabled && keyHeld) { keyHeld = false; controller.holdWheel(null, connection) } }
    Box(modifier.heightIn(min = 48.dp).testTag("addressing-wheel-hold-" + wheel.name.lowercase())
        .background(if (pressed) colors.primary else if (hovered) colors.surfaceVariant else colors.surfaceContainer, RoundedCornerShape(16.dp))
        .border(if (focused) 2.dp else 1.dp, if (focused) colors.primary else colors.surfaceVariant, RoundedCornerShape(16.dp))
        .semantics {
            role = Role.Button; contentDescription = label
            if (!enabled) disabled()
            else onClick { controller.pulseWheel(wheel, connection); true }
        }.focusRequester(focus).onFocusChanged {
            focused = it.isFocused
            if (!it.isFocused && keyHeld) { keyHeld = false; controller.holdWheel(null, connection) }
        }.onKeyEvent {
            if (!enabled || it.key !in setOf(Key.Spacebar, Key.Enter)) false
            else if (it.type == KeyEventType.KeyDown) {
                if (!keyHeld) { keyHeld = true; controller.holdWheel(wheel, connection) }; true
            } else if (it.type == KeyEventType.KeyUp) { keyHeld = false; controller.holdWheel(null, connection); true } else false
        }.focusable(enabled, interactions).hoverable(interactions, enabled)
        .pointerInput(enabled, connection) {
            if (enabled) awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false); down.consume(); focus.requestFocus()
                controller.holdWheel(wheel, connection)
                try { waitForUpOrCancellation() } finally { controller.holdWheel(null, connection) }
            }
        }.padding(horizontal = 12.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(label, fontSize = 15.sp, color = if (pressed) colors.onPrimary else if (enabled) colors.onSurface else colors.onSurfaceVariantSummary)
    }
}

@Composable
private fun ColumnScope.ArmorAddressingContent(task: RobotAddressingState, enabled: Boolean, onSkip: () -> Unit) {
    Text(tr(Res.string.addressing_armor_report, task.armorSteps, task.skippedArmorSteps.size),
        Modifier.testTag("addressing-armor-progress"), fontSize = 16.sp)
    if (task.armorSteps < 4) {
        val next = tr(listOf(Res.string.addressing_armor_back, Res.string.addressing_armor_front, Res.string.addressing_armor_left, Res.string.addressing_armor_right)[task.armorSteps])
        Text(tr(Res.string.addressing_armor_next, next), Modifier.testTag("addressing-armor-next"), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(tr(Res.string.addressing_armor_help), fontSize = 15.sp)
        Button(onSkip, Modifier.heightIn(min = 48.dp).testTag("addressing-armor-skip"), enabled = enabled) { Text(tr(Res.string.addressing_skip_armor, next)) }
    } else Text(tr(Res.string.addressing_armor_check), fontSize = 15.sp)
}

@Composable
private fun ColumnScope.AccessoryAddressingContent(controller: RobotAddressingController, ui: AddressingUiState,
    canChange: Boolean, onAssign: (Int) -> Unit) {
    val task = ui.task
    var selected by remember(ui.connection, task.candidateId, task.phase) { mutableStateOf<Int?>(null) }
    if (task.inventoryKnown) Text(tr(Res.string.addressing_inventory_age, task.inventoryAgeSeconds), fontSize = 14.sp)
    if (task.phase == AddressingPhase.DISCOVERING) {
        Text(tr(if (task.waitingForQuiet) Res.string.addressing_wait_quiet else if (task.candidateId == null)
            Res.string.addressing_connect_one else Res.string.addressing_one_module), fontSize = 15.sp)
        task.candidateId?.let { Text(tr(Res.string.addressing_candidate, it), Modifier.testTag("addressing-candidate"), fontSize = 16.sp) }
        if (task.reservedIds.isNotEmpty()) Text(tr(Res.string.addressing_arm_reserved, task.reservedIds.sorted().joinToString(", ")), fontSize = 15.sp)
        if (task.assignedIds.isNotEmpty()) Text(tr(Res.string.addressing_pending_ids, task.assignedIds.sorted().joinToString(", ")), fontSize = 15.sp)
        if (task.candidateId != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..if (task.kind == ModuleAddressingKind.SERVO) 3 else 4).forEach { id ->
                    Button({ selected = id }, Modifier.heightIn(min = 48.dp).testTag("addressing-id-" + id),
                        enabled = canChange && !ui.busy && id in task.selectableIds,
                        colors = if (selected == id) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors()) { Text(id.toString()) }
                }
            }
            val id = selected
            Button({ id?.let(onAssign) }, Modifier.heightIn(min = 48.dp).testTag("addressing-assign"),
                enabled = canChange && !ui.busy && id != null && id in task.selectableIds, colors = ButtonDefaults.buttonColorsPrimary()) {
                Text(if (id == null) tr(Res.string.addressing_select_id) else tr(Res.string.addressing_write_id, id))
            }
        }
        Button({ controller.prepareVerification(ui.connection) }, Modifier.heightIn(min = 48.dp).testTag("addressing-verify"),
            enabled = canChange && !ui.busy) { Text(tr(Res.string.addressing_verify_modules)) }
    } else if (task.phase == AddressingPhase.VERIFYING) {
        if (!task.inventoryKnown) Text(tr(Res.string.addressing_wait_verification_report), fontSize = 15.sp)
        else if (task.onlineIds.isEmpty()) Text(tr(Res.string.addressing_no_modules), fontSize = 15.sp)
        else {
            Text(tr(Res.string.addressing_identify_help), fontSize = 15.sp)
            task.onlineIds.sorted().forEach { id ->
                Text(tr(Res.string.addressing_module_id, id), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ controller.identify(id, ui.connection) }, Modifier.heightIn(min = 48.dp).testTag("addressing-identify-" + id),
                        enabled = canChange && !ui.busy && task.identifyingId == null) { Text(tr(Res.string.addressing_identify)) }
                    Button({ controller.confirmModule(id, ui.connection) }, Modifier.heightIn(min = 48.dp).testTag("addressing-module-confirm-" + id),
                        enabled = !ui.busy && id in task.identifiedIds && task.identifyingId == null && id !in task.verifiedIds) {
                        Text(tr(if (id in task.verifiedIds) Res.string.addressing_checked else Res.string.addressing_module_matches))
                    }
                }
            }
        }
        task.identifyingId?.let { Text(tr(Res.string.addressing_identifying, it), Modifier.testTag("addressing-identifying"), fontSize = 15.sp) }
    }
}

@Composable
private fun ColumnScope.AdapterAddressingContent(controller: RobotAddressingController, ui: AddressingUiState, canChange: Boolean) {
    val task = ui.task
    var selected by remember(ui.connection) { mutableStateOf(1) }
    Text(tr(Res.string.addressing_adapter_steps), fontSize = 15.sp)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        (1..6).forEach { id -> Button({ selected = id }, Modifier.heightIn(min = 48.dp).testTag("addressing-adapter-id-" + id),
            colors = if (selected == id) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors()) { Text(id.toString()) } }
    }
    Text(tr(Res.string.addressing_adapter_pattern, selected), fontSize = 16.sp)
    val lit = when (selected) { 5 -> setOf(1, 4); 6 -> setOf(2, 4); else -> setOf(selected) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        (1..4).forEach { id ->
            val colors = MiuixTheme.colorScheme
            val label = tr(if (id in lit) Res.string.addressing_led_on else Res.string.addressing_led_off, id)
            Box(Modifier.size(48.dp).testTag("addressing-adapter-led-" + id).semantics { contentDescription = label }
                .background(if (id in lit) colors.primary else colors.surfaceContainer, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Text(id.toString(), color = if (id in lit) colors.onPrimary else colors.onSurface, fontSize = 16.sp)
            }
        }
    }
    Text(tr(Res.string.addressing_online_ids, task.onlineIds.sorted().joinToString(", ").ifEmpty { "—" }), fontSize = 15.sp)
    Text(tr(Res.string.addressing_inventory_age, task.inventoryAgeSeconds), fontSize = 14.sp)
    if (selected !in task.onlineIds) Text(tr(Res.string.addressing_adapter_wait, selected), fontSize = 15.sp)
    Button({ controller.confirmModule(selected, ui.connection) }, Modifier.heightIn(min = 48.dp).testTag("addressing-adapter-confirm"),
        enabled = canChange && !ui.busy && selected in task.onlineIds && selected !in task.verifiedIds) {
        Text(tr(if (selected in task.verifiedIds) Res.string.addressing_checked else Res.string.addressing_adapter_matches))
    }
}

@Composable internal fun addressingKindName(kind: ModuleAddressingKind): String = tr(when (kind) {
    ModuleAddressingKind.WHEELS -> Res.string.addressing_wheels
    ModuleAddressingKind.ARMOR -> Res.string.addressing_armor
    ModuleAddressingKind.SERVO -> Res.string.addressing_servo
    ModuleAddressingKind.TOF -> Res.string.addressing_tof
    ModuleAddressingKind.SENSOR_ADAPTER -> Res.string.addressing_adapter
})
@Composable private fun addressingWheelName(wheel: AddressingWheel): String = tr(when (wheel) {
    AddressingWheel.FRONT_RIGHT -> Res.string.addressing_front_right
    AddressingWheel.FRONT_LEFT -> Res.string.addressing_front_left
    AddressingWheel.REAR_LEFT -> Res.string.addressing_rear_left
    AddressingWheel.REAR_RIGHT -> Res.string.addressing_rear_right
})
private fun addressingPreparation(kind: ModuleAddressingKind): StringResource = when (kind) {
    ModuleAddressingKind.WHEELS -> Res.string.addressing_prepare_wheels
    ModuleAddressingKind.ARMOR -> Res.string.addressing_prepare_armor
    ModuleAddressingKind.SERVO -> Res.string.addressing_prepare_servo
    ModuleAddressingKind.TOF -> Res.string.addressing_prepare_tof
    ModuleAddressingKind.SENSOR_ADAPTER -> Res.string.addressing_prepare_adapter
}
private fun addressingProblemText(problem: AddressingProblem): StringResource = when (problem) {
    AddressingProblem.RESULT_UNKNOWN -> Res.string.addressing_unknown
    AddressingProblem.MULTIPLE_MODULES -> Res.string.addressing_multiple
    AddressingProblem.ARM_CHANGED -> Res.string.addressing_arm_changed
    AddressingProblem.ARM_RESERVATIONS -> Res.string.addressing_arm_unknown
    AddressingProblem.WHEEL_REPORT -> Res.string.addressing_wheel_report_error
    AddressingProblem.SKIP_RACED -> Res.string.addressing_skip_raced
}
private fun addressingStartSummary(kind: ModuleAddressingKind): StringResource = when (kind) {
    ModuleAddressingKind.WHEELS -> Res.string.addressing_start_wheels
    ModuleAddressingKind.ARMOR -> Res.string.addressing_start_armor
    ModuleAddressingKind.SERVO, ModuleAddressingKind.TOF -> Res.string.addressing_start_accessory
    ModuleAddressingKind.SENSOR_ADAPTER -> Res.string.addressing_prepare_adapter
}
