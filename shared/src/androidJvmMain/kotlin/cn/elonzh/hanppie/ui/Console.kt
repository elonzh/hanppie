package cn.elonzh.hanppie.ui

import cn.elonzh.hanppie.resources.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

import androidx.compose.foundation.*
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.sample
import org.jetbrains.compose.resources.painterResource
import top.yukonga.miuix.kmp.basic.*

private val Ink: Color @Composable get() = MiuixTheme.colorScheme.onSurface
private val Muted: Color @Composable get() = MiuixTheme.colorScheme.onSurfaceVariantSummary
private val Accent: Color @Composable get() = MiuixTheme.colorScheme.primary
private val CanvasColor: Color @Composable get() = MiuixTheme.colorScheme.background
private val navigationOrder = listOf(0, 1, 3, 2, 4)
private val labels get() = listOf(tr(Res.string.robot), tr(Res.string.script), tr(Res.string.debug), tr(Res.string.chat), tr(Res.string.settings))

@Composable
@OptIn(FlowPreview::class, ExperimentalLayoutApi::class)
internal fun Console(
    model: ConsoleModel,
    document: MutableState<EditorDocument>,
    onCockpitChanged: (Boolean) -> Unit = {},
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
    var connectionDetails by remember { mutableStateOf(false) }
    var manual by rememberSaveable { mutableStateOf(false) }
    var ip by rememberSaveable { mutableStateOf("") }
    var appId by rememberSaveable { mutableStateOf("") }
    var diagnosticTab by rememberSaveable { mutableStateOf(0) }
    var cockpit by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.connected) { if (!state.connected) cockpit = false }
    LaunchedEffect(cockpit) { onCockpitChanged(cockpit) }
    if(cockpit) {
        RemotePage(model, Modifier.fillMaxSize().safeDrawingPadding(), onBack = { cockpit=false },
            onPushToTalkStart = onPushToTalkStart, onPushToTalkStop = onPushToTalkStop)
        return
    }

    WorkbenchDialog(show = connectionDetails, onDismissRequest = { connectionDetails = false }, title = tr(Res.string.connection_details)) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Image(painterResource(HanppieBrandAssets.avatar), null, Modifier.size(64.dp))
                Column(Modifier.weight(1f)) {
                    Text("RoboMaster S1", fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                    Text(state.status, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 13.sp)
                }
            }
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ConnectionDetail(tr(Res.string.robot_ipv4), state.connectedAddress ?: "—")
                    ConnectionDetail(tr(Res.string.battery), state.battery?.let { "$it%" } ?: "—")
                    ConnectionDetail(tr(Res.string.signal), state.signalQuality?.toString() ?: "—")
                    ConnectionDetail(tr(Res.string.script), state.scriptStatus)
                }
            }
            if (state.connected || state.reconnecting) {
                Button({ model.disconnect(); connectionDetails = false }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !state.busy) { Text(tr(Res.string.disconnect_close_session)) }
            } else {
                Button({ connectionDetails = false; navigate(0); model.discover() }, Modifier.fillMaxWidth().heightIn(min = 48.dp), colors = ButtonDefaults.buttonColorsPrimary(), enabled = !state.busy) { Text(tr(Res.string.find_robots)) }
                Button({ connectionDetails = false; manual = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("connection-manual")) { Text(tr(Res.string.manual_connection)) }
            }
        }
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
        val compact = maxWidth < HanppieDesignTokens.CompactBreakpoint
        Row(Modifier.fillMaxSize()) {
            if (!compact) NavigationRail(color = MiuixTheme.colorScheme.surfaceVariant, defaultWindowInsetsPadding = false,
                header = { Image(painterResource(HanppieBrandAssets.avatar), null,
                    Modifier.padding(vertical = 18.dp).size(36.dp)) }) {
                navigationOrder.forEach { index ->
                    val label = labels[index]
                    IconButton(onClick = { navigate(index) }, modifier = Modifier.padding(vertical = 4.dp).size(48.dp)
                        .background(if (selectedTab == index) MiuixTheme.colorScheme.primary.copy(alpha = .14f) else Color.Transparent, RoundedCornerShape(16.dp))
                        .semantics { contentDescription = label; selected = selectedTab == index }) {
                        Icon(navigationIcons[index], null, Modifier.size(24.dp), tint = Ink)
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Column(Modifier.weight(1f).widthIn(max = HanppieDesignTokens.PageMaxWidth).fillMaxWidth()
                    .padding(horizontal = if (compact) HanppieDesignTokens.PagePaddingCompact else HanppieDesignTokens.PagePaddingExpanded)) {
                    if (selectedTab != 1) {
                        Row(Modifier.fillMaxWidth().height(72.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(if (selectedTab == 0) tr(Res.string.my_robot) else labels[selectedTab], modifier = Modifier.weight(1f), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Ink, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ConnectionStatusChip(state) { connectionDetails = true }
                                if (selectedTab == 2) {
                                    Spacer(Modifier.width(8.dp))
                                    WorkbenchIconButton(tr(Res.string.clear), WorkbenchGlyph.DELETE, model::clearLogs)
                                }
                            }
                        }
                        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
                        (fileError ?: state.error)?.let { Text(it, Modifier.padding(vertical = 8.dp), color = MiuixTheme.colorScheme.error, fontSize = 13.sp) }
                    }
                    when (selectedTab) {
                        3 -> pageState.SaveableStateProvider("chat") { ChatPage(model, Modifier.weight(1f), onVoiceInput) { navigate(4) } }
                        4 -> SettingsPage(model, Modifier.weight(1f), onSpeechSettings)
                        0 -> DevicePage(model, state, compact, Modifier.weight(1f),
                            onManualConnect = { manual = true },
                            onRemote = { if (state.connected) cockpit = true }, onNavigate = ::navigate)
                        1 -> ScriptPage(model, document, compact, onImport, onExport, fileError, onFileError, onConnectionDetails = { connectionDetails = true })
                        2 -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                TabRow(tabs = listOf(tr(Res.string.logs), tr(Res.string.telemetry), tr(Res.string.packets_2)),
                                    selectedTabIndex = diagnosticTab, onTabSelected = { diagnosticTab = it },
                                    modifier = Modifier.weight(1f), minWidth = 72.dp, maxWidth = 120.dp,
                                    height = HanppieDesignTokens.TouchTarget, itemSpacing = 4.dp,
                                    colors = TabRowDefaults.tabRowColors(selectedBackgroundColor = MiuixTheme.colorScheme.primaryVariant,
                                        selectedContentColor = MiuixTheme.colorScheme.onPrimaryVariant))
                            }
                            val diagnosticScroll = androidx.compose.foundation.lazy.rememberLazyListState()
                            LaunchedEffect(diagnosticTab) { diagnosticScroll.scrollToItem(0) }
                            Box(Modifier.weight(1f)) {
                            SelectionContainer(Modifier.fillMaxSize().padding(end = 12.dp)) {
                                LazyColumn(Modifier.fillMaxSize().testTag("diagnostic-output").background(MiuixTheme.colorScheme.surfaceContainer, RoundedCornerShape(HanppieDesignTokens.CardRadius)), state = diagnosticScroll, contentPadding = PaddingValues(16.dp),
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
                                        if (lines.isEmpty()) item {
                                            Column(Modifier.fillMaxWidth().padding(vertical = 72.dp),
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                                HanppieIcon(HanppieSymbol.File, Muted, Modifier.size(40.dp))
                                                Text(tr(Res.string.no_records), color = Muted, fontSize = 14.sp)
                                            }
                                        }
                                        items(lines) { Text(it, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
                                    }
                                }
                            }
                            DesktopListScrollbar(diagnosticScroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight().testTag("diagnostic-scrollbar"))
                            }
                            Spacer(Modifier.height(4.dp))
                        }

                    }
                }
                if (selectedTab != 1) {
                    ScriptRunBanner(state, onOpen = { navigate(1) }, onStop = model::stop)
                }
                if (compact) NavigationBar(Modifier.testTag("bottom-navigation"), mode = NavigationBarDisplayMode.IconOnly,
                    color = MiuixTheme.colorScheme.surfaceVariant, defaultWindowInsetsPadding = false) {
                    navigationOrder.forEach { index ->
                    val label = labels[index]
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
