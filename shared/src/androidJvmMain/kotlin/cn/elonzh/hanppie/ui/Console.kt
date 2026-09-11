package cn.elonzh.hanppie.ui

import cn.elonzh.hanppie.resources.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.sample
import top.yukonga.miuix.kmp.basic.*

private val Ink: Color @Composable get() = MiuixTheme.colorScheme.onSurface
private val Muted: Color @Composable get() = MiuixTheme.colorScheme.onSurfaceVariantSummary
private val Accent: Color @Composable get() = MiuixTheme.colorScheme.primary
private val CanvasColor: Color @Composable get() = MiuixTheme.colorScheme.background
private val labels get() = listOf(tr(Res.string.robot), tr(Res.string.script), tr(Res.string.debug), tr(Res.string.chat), tr(Res.string.settings))

@Composable
@OptIn(FlowPreview::class, ExperimentalLayoutApi::class)
internal fun Console(
    model: ConsoleModel,
    document: MutableState<EditorDocument>,
    onImport: () -> Unit = {},
    onExport: () -> Unit = {},
    fileError: String? = null,
    onFileError: (String?) -> Unit = {},
    onSpeechSettings: (() -> Unit)? = null,
    onVoiceInput: (() -> Unit)? = null,
    onPushToTalkStart: (() -> Unit)? = null,
    onPushToTalkStop: (() -> Unit)? = null,
) {
    val renderState = remember(model) { model.state.sample(100) }
    val state by renderState.collectAsState(initial = model.state.value)
    var tab by rememberSaveable { mutableStateOf(0) }
    val selectedTab = tab.coerceIn(0, labels.lastIndex)
    val focus = LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val pageState = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    fun navigate(index: Int) { focus.clearFocus(); keyboard?.hide(); tab = index }
    var manual by rememberSaveable { mutableStateOf(false) }
    var ip by rememberSaveable { mutableStateOf("") }
    var appId by rememberSaveable { mutableStateOf("") }
    var diagnosticTab by rememberSaveable { mutableStateOf(0) }
    var cockpit by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.connected) { if (!state.connected) cockpit = false }
    if(cockpit && state.connected) {
        RemotePage(model, Modifier.fillMaxSize().safeDrawingPadding(), onBack = { cockpit=false },
            onPushToTalkStart = onPushToTalkStart, onPushToTalkStop = onPushToTalkStop)
        return
    }

    WorkbenchDialog(show = manual, onDismissRequest = { manual = false }, title = tr(Res.string.manual_connection)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(ip, { ip = it }, label = tr(Res.string.robot_ipv4), singleLine = true)
            TextField(appId, { appId = it }, label = tr(Res.string.appid_8_hex_characters), singleLine = true)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button({ manual = false }, Modifier.weight(1f).heightIn(min = 48.dp)) { Text(tr(Res.string.cancel)) }
                Spacer(Modifier.width(8.dp))
                Button({ model.connect(ip, appId); manual = false }, Modifier.weight(1f).heightIn(min = 48.dp), enabled = !state.busy && !state.connected,
                    colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr(Res.string.connect)) }
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(CanvasColor).safeDrawingPadding().imePadding()) {
        val compact = maxWidth < 720.dp
        Row(Modifier.fillMaxSize()) {
            if (!compact) NavigationRail(color = MiuixTheme.colorScheme.surfaceVariant, defaultWindowInsetsPadding = false,
                header = { Text("H", Modifier.padding(vertical = 20.dp), color = Accent,
                    style = MiuixTheme.textStyles.title2) }) {
                labels.forEachIndexed { index, label ->
                    NavigationRailItem(selected = selectedTab == index, onClick = { navigate(index) },
                        icon = navigationIcons[index], label = label)
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Column(Modifier.weight(1f).fillMaxWidth().widthIn(max = 1080.dp)
                    .padding(horizontal = if (compact) 20.dp else 28.dp)) {
                    if (selectedTab != 1) {
                        Row(Modifier.fillMaxWidth().height(72.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(if (selectedTab == 0) tr(Res.string.my_robot) else labels[selectedTab], fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Ink)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(6.dp).background(if (state.connected) Color(0xff32aa78) else Color(0xffaab1bb), RoundedCornerShape(50)))
                                Spacer(Modifier.width(6.dp))
                                Text(if (state.connected) tr(Res.string.connected) else state.status, fontSize = 12.sp, color = Muted)
                            }
                        }
                        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
                        (fileError ?: state.error)?.let { Text(it, Modifier.padding(vertical = 8.dp), color = MiuixTheme.colorScheme.error, fontSize = 13.sp) }
                    }
                    when (selectedTab) {
                        3 -> pageState.SaveableStateProvider("chat") { ChatPage(model, Modifier.weight(1f), onVoiceInput) { navigate(4) } }
                        4 -> SettingsPage(model, Modifier.weight(1f), onSpeechSettings)
                        0 -> LazyColumn(Modifier.weight(1f).testTag("device-page"), verticalArrangement = Arrangement.spacedBy(16.dp),
                            contentPadding = PaddingValues(bottom = 20.dp)) {
                            item {
                                Card(Modifier.fillMaxWidth(), colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer)) {
                                    Column(Modifier.padding(22.dp)) {
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f)) {
                                                Text("ROBOMASTER", fontSize = 11.sp, color = Muted, fontWeight = FontWeight.Medium)
                                                Text("S1", Modifier.padding(top = 4.dp), fontSize = 48.sp, color = Ink, fontWeight = FontWeight.Bold)
                                                Text(state.connectedAddress?.takeIf { state.connected } ?: tr(Res.string.not_connected), fontSize = 13.sp, color = Muted)
                                            }
                                            RobotMark(Modifier.size(110.dp))
                                        }
                                        Spacer(Modifier.height(24.dp))
                                        if (state.connected) {
                                            Button({ cockpit = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("enter-remote"), colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr(Res.string.fullscreen_cockpit)) }
                                            Spacer(Modifier.height(12.dp))
                                        }
                                        if (state.connected || state.reconnecting || state.statusMessage.resource == Res.string.connection_lost) {
                                            Button(model::disconnect, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("disconnect-robot"), enabled = !state.busy) { Text(tr(Res.string.disconnect_close_session)) }
                                        } else {
                                            Button(model::discover, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("discover-robot"), enabled = !state.busy,
                                                colors = ButtonDefaults.buttonColorsPrimary()) { Text(if (state.busy) tr(Res.string.searching) else tr(Res.string.find_robots)) }
                                            Spacer(Modifier.height(12.dp))
                                            Button({ manual = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("manual-connect")) { Text(tr(Res.string.manual_connection)) }
                                        }
                                    }
                                }
                            }
                            items(state.devices) { device ->
                                Button({ model.connect(device.ip, device.appId) }, Modifier.fillMaxWidth(), enabled = !state.connected && !state.busy) {
                                    Text("S1  ·  ${device.ip}", Modifier.weight(1f)); Text(tr(Res.string.connect))
                                }
                            }
                            item {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Metric(tr(Res.string.battery), state.battery?.let { "$it%" } ?: "—", Modifier.weight(1f))
                                    Metric(tr(Res.string.signal), state.signalQuality?.toString() ?: "—", Modifier.weight(1f))
                                    Metric(tr(Res.string.packets), state.packets.toString(), Modifier.weight(1f))
                                }
                            }
                            if (!state.connected) item {
                                Text(tr(Res.string.connect_your_phone_or_computer_to_the_same_wi), fontSize = 12.sp, color = Muted)
                            }
                        }
                        1 -> ScriptPage(model, document, compact, onImport, onExport, fileError, onFileError)
                        2 -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                listOf(tr(Res.string.logs), tr(Res.string.telemetry), tr(Res.string.packets_2)).forEachIndexed { index, label ->
                                    Text(label, Modifier.clickable { diagnosticTab = index }.padding(12.dp),
                                        color = if (diagnosticTab == index) Accent else Muted, fontSize = 14.sp)
                                }
                                Spacer(Modifier.weight(1f))
                                Text(tr(Res.string.clear), Modifier.clickable(onClick = model::clearLogs).padding(12.dp), color = Muted, fontSize = 13.sp)
                            }
                            SelectionContainer(Modifier.weight(1f)) {
                                LazyColumn(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surfaceContainer, RoundedCornerShape(20.dp)), contentPadding = PaddingValues(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    if (diagnosticTab == 1) {
                                        state.gimbal?.let { gimbal ->
                                            item { Text(tr(Res.string.gimbal_protocol_angles_last_received), fontSize = 12.sp, color = Muted) }
                                            val angles = listOf(
                                                tr(Res.string.gimbal_yaw) to "${gimbal.yawDegrees}°",
                                                tr(Res.string.gimbal_pitch) to "${gimbal.pitchDegrees}°",
                                                tr(Res.string.ground_reference_yaw) to "${gimbal.groundYawDegrees}°",
                                                tr(Res.string.ground_reference_pitch) to "${gimbal.groundPitchDegrees}°",
                                                tr(Res.string.gimbal_status_byte) to "0x${gimbal.status.toString(16).padStart(2, '0')}",
                                            )
                                            items(angles) { (key, value) ->
                                                Row(Modifier.fillMaxWidth()) { Text(key, Modifier.weight(1f), fontSize = 12.sp); Text(value, fontSize = 12.sp) }
                                            }
                                        }
                                        item { Text(tr(Res.string.raw_fields_uncalibrated), fontSize = 12.sp, color = Muted) }
                                        items(state.values) { (key, value) ->
                                            Row(Modifier.fillMaxWidth()) { Text(key, Modifier.weight(1f), fontSize = 12.sp); Text(value, fontSize = 12.sp) }
                                        }
                                    } else {
                                        val lines = if (diagnosticTab == 0) state.logs else state.frames
                                        if (lines.isEmpty()) item { Text(tr(Res.string.no_records), color = Muted, fontSize = 13.sp) }
                                        items(lines) { Text(it, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
                                    }
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                        }

                    }
                }
                if (selectedTab != 1) {
                    ScriptRunBanner(state, onOpen = { navigate(1) }, onStop = model::stop)
                }
                if (compact) NavigationBar(Modifier.testTag("bottom-navigation"),
                    color = MiuixTheme.colorScheme.surfaceVariant, defaultWindowInsetsPadding = false) {
                    labels.forEachIndexed { index, label ->
                        NavigationBarItem(selected = selectedTab == index, onClick = { navigate(index) },
                            icon = navigationIcons[index], label = label)
                    }
                }
            }
        }
    }
}

@Composable
private fun ScriptRunBanner(state: ConsoleState, onOpen: () -> Unit, onStop: () -> Unit) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    val active = state.scriptRunPhase.active
    val recentTerminal = state.scriptFinishedAtEpochMillis?.let { now - it < 8_000 } == true
    LaunchedEffect(active, state.scriptFinishedAtEpochMillis) {
        while (active || state.scriptFinishedAtEpochMillis?.let { now - it < 8_000 } == true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    if (!active && !recentTerminal) return
    val elapsed = state.scriptStartedAtEpochMillis?.let { ((now - it).coerceAtLeast(0) / 1_000) }
    val elapsedText = elapsed?.let { "%d:%02d".format(it / 60, it % 60) }
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
        .testTag("script-run-banner").clickable(onClick = onOpen),
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHigh),
        insideMargin = PaddingValues(0.dp)) {
        Column {
            if (active) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(scriptRunColor(state.scriptRunPhase), RoundedCornerShape(50)))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(state.scriptTitle ?: tr(Res.string.script), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text(listOfNotNull(state.scriptStatus, elapsedText).joinToString(" · "), fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    state.scriptMessages.lastOrNull()?.let {
                        Text(it, fontSize = 11.sp, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
                if (state.canStop) {
                    WorkbenchIconButton(
                        label = tr(Res.string.stop_script),
                        glyph = WorkbenchGlyph.STOP,
                        onClick = onStop,
                        danger = true,
                        tag = "script-banner-stop",
                    )
                }
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(18.dp)) {
            Text(label, color = Muted, fontSize = 12.sp)
            Text(value, Modifier.padding(top = 10.dp), fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        }
    }
}

@Composable
private fun RobotMark(modifier: Modifier) {
    val ink = Ink
    val accent = Accent
    val surface = MiuixTheme.colorScheme.surfaceContainer
    Canvas(modifier.semantics { contentDescription = tr(Res.string.s1_robot_icon) }) {
        val w = size.width; val h = size.height
        drawCircle(Color(0xff29334a), w*.49f)
        drawRoundRect(Color(0xff46536c), Offset(w*.18f,h*.55f), Size(w*.65f,h*.2f), androidx.compose.ui.geometry.CornerRadius(w*.08f))
        listOf(.18f,.67f).forEach { x -> drawRoundRect(ink, Offset(w*x,h*.58f),Size(w*.15f,h*.25f),androidx.compose.ui.geometry.CornerRadius(w*.04f)) }
        drawRoundRect(Color(0xffacb9d2), Offset(w*.33f,h*.28f),Size(w*.33f,h*.32f),androidx.compose.ui.geometry.CornerRadius(w*.06f))
        drawRoundRect(accent,Offset(w*.47f,h*.36f),Size(w*.4f,h*.09f),androidx.compose.ui.geometry.CornerRadius(w*.025f))
        drawCircle(surface,w*.06f,Offset(w*.44f,h*.35f))
    }
}
