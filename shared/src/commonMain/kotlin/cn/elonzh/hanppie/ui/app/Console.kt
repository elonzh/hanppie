package cn.elonzh.hanppie.ui.app

import androidx.compose.foundation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.savedstate.serialization.SavedStateConfiguration
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.chat.ChatPage
import cn.elonzh.hanppie.ui.design.HanppieBrandAssets
import cn.elonzh.hanppie.ui.design.HanppieDesignTokens
import cn.elonzh.hanppie.ui.design.WorkbenchDialog
import cn.elonzh.hanppie.ui.design.WorkbenchGlyph
import cn.elonzh.hanppie.ui.design.WorkbenchIconButton
import cn.elonzh.hanppie.ui.design.navigationIcons
import cn.elonzh.hanppie.ui.i18n.tr
import cn.elonzh.hanppie.ui.robot.device.ConnectionDetail
import cn.elonzh.hanppie.ui.robot.device.ConnectionStatusChip
import cn.elonzh.hanppie.ui.robot.device.DevicePage
import cn.elonzh.hanppie.ui.robot.diagnostics.DebugPage
import cn.elonzh.hanppie.ui.robot.diagnostics.RobotFilesDebugTab
import cn.elonzh.hanppie.ui.robot.remote.RemotePage
import cn.elonzh.hanppie.ui.scripts.EditorDocument
import cn.elonzh.hanppie.ui.scripts.ScriptPage
import cn.elonzh.hanppie.ui.scripts.scriptRunColor
import cn.elonzh.hanppie.ui.settings.SettingsPage
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.sample
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import org.jetbrains.compose.resources.painterResource
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val Ink: Color @Composable get() = MiuixTheme.colorScheme.onSurface
private val CanvasColor: Color @Composable get() = MiuixTheme.colorScheme.background
private val navigationOrder = listOf(0, 1, 3, 2, 4)
private val labels get() = listOf(tr(Res.string.robot), tr(Res.string.script), tr(Res.string.debug), tr(Res.string.chat), tr(Res.string.settings))
private val workbenchNavigationConfiguration = SavedStateConfiguration {
    serializersModule = SerializersModule {
        polymorphic(NavKey::class) {
            subclass(RobotRoute.serializer())
            subclass(ScriptRoute.serializer())
            subclass(DebugRoute.serializer())
            subclass(ChatRoute.serializer())
            subclass(SettingsRoute.serializer())
            subclass(CockpitRoute.serializer())
        }
    }
}

@Composable
@OptIn(FlowPreview::class, ExperimentalLayoutApi::class)
internal fun Console(
    model: ConsoleController,
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
    onRobotFileUpload: ((String) -> Unit)? = null,
    onRobotFileDownload: ((cn.elonzh.hanppie.robot.files.RobotFileEntry) -> Unit)? = null,
    onRobotFileOpen: ((cn.elonzh.hanppie.robot.files.RobotFileEntry) -> Unit)? = null,
) {
    val renderState = remember(model) { model.state.sample(100) }
    val state by renderState.collectAsState(initial = model.state.value)
    val backStack = rememberNavBackStack(workbenchNavigationConfiguration, RobotRoute)
    val currentRoute = backStack.last() as WorkbenchRoute
    val focus = LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    fun navigate(index: Int) {
        focus.clearFocus()
        keyboard?.hide()
        while (backStack.size > 1) backStack.removeLastOrNull()
        backStack[0] = workbenchRoute(index)
    }
    var connectionDetails by remember { mutableStateOf(false) }
    var manual by rememberSaveable { mutableStateOf(false) }
    var ip by rememberSaveable { mutableStateOf("") }
    var appId by rememberSaveable { mutableStateOf("") }
    var diagnosticTab by rememberSaveable { mutableStateOf(0) }
    fun openCockpit() {
        if (state.connected && backStack.lastOrNull() != CockpitRoute) backStack.add(CockpitRoute)
    }
    fun goBack() {
        if (backStack.size > 1) backStack.removeLastOrNull()
        else if (backStack.lastOrNull() != RobotRoute) backStack[0] = RobotRoute
    }
    LaunchedEffect(state.connected, currentRoute) {
        if (!state.connected && currentRoute == CockpitRoute && backStack.size > 1) backStack.removeLastOrNull()
    }
    LaunchedEffect(currentRoute) { onCockpitChanged(currentRoute == CockpitRoute) }

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
    NavDisplay(
        backStack = backStack,
        modifier = Modifier.fillMaxSize(),
        onBack = ::goBack,
        entryProvider = { key ->
            NavEntry(key) {
                val route = key as WorkbenchRoute
                if (route == CockpitRoute) {
                    RemotePage(model, Modifier.fillMaxSize().safeDrawingPadding(), onBack = ::goBack,
                        onPushToTalkStart = onPushToTalkStart, onPushToTalkStop = onPushToTalkStop)
                } else {
                    val routeTab = route.topLevelIndex
                    BoxWithConstraints(Modifier.fillMaxSize().background(CanvasColor).safeDrawingPadding().imePadding()) {
                        val compact = maxWidth < HanppieDesignTokens.CompactBreakpoint
                        Row(Modifier.fillMaxSize()) {
                            if (!compact) NavigationRail(color = MiuixTheme.colorScheme.surfaceVariant,
                                defaultWindowInsetsPadding = false,
                                header = { Image(painterResource(HanppieBrandAssets.avatar), null,
                                    Modifier.padding(vertical = 18.dp).size(36.dp)) }) {
                                navigationOrder.forEach { index ->
                                    val label = labels[index]
                                    IconButton(onClick = { navigate(index) },
                                        modifier = Modifier.padding(vertical = 4.dp).size(48.dp)
                                            .background(if (routeTab == index) MiuixTheme.colorScheme.primary.copy(alpha = .14f)
                                                else Color.Transparent, RoundedCornerShape(16.dp))
                                            .semantics { contentDescription = label; selected = routeTab == index }) {
                                        Icon(navigationIcons[index], null, Modifier.size(24.dp), tint = Ink)
                                    }
                                }
                            }
                            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                                Column(Modifier.weight(1f).widthIn(max = HanppieDesignTokens.PageMaxWidth).fillMaxWidth()
                                    .padding(horizontal = if (compact) HanppieDesignTokens.PagePaddingCompact
                                    else HanppieDesignTokens.PagePaddingExpanded)) {
                                    if (routeTab != 1) {
                                        Row(Modifier.fillMaxWidth().height(72.dp), verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text(if (routeTab == 0) tr(Res.string.my_robot) else labels[routeTab],
                                                modifier = Modifier.weight(1f), fontSize = 26.sp, fontWeight = FontWeight.Bold,
                                                color = Ink, maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                ConnectionStatusChip(state) { connectionDetails = true }
                                                if (routeTab == 2 && diagnosticTab != RobotFilesDebugTab) {
                                                    Spacer(Modifier.width(8.dp))
                                                    WorkbenchIconButton(tr(Res.string.clear), WorkbenchGlyph.DELETE, model::clearLogs)
                                                }
                                            }
                                        }
                                        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
                                        (fileError ?: state.error)?.let { Text(it, Modifier.padding(vertical = 8.dp),
                                            color = MiuixTheme.colorScheme.error, fontSize = 13.sp) }
                                    }
                                    when (route) {
                                        ChatRoute -> ChatPage(model, Modifier.weight(1f), onVoiceInput) { navigate(4) }
                                        SettingsRoute -> SettingsPage(model, Modifier.weight(1f), onSpeechSettings)
                                        RobotRoute -> DevicePage(model, state, compact, Modifier.weight(1f),
                                            onManualConnect = { manual = true }, onRemote = ::openCockpit,
                                            onNavigate = ::navigate)
                                        ScriptRoute -> ScriptPage(model, document, compact, onImport, onExport,
                                            fileError, onFileError, onConnectionDetails = { connectionDetails = true })
                                        DebugRoute -> DebugPage(model, state, diagnosticTab, { diagnosticTab = it }, compact,
                                            Modifier.weight(1f), onRobotFileUpload, onRobotFileDownload, onRobotFileOpen)
                                        CockpitRoute -> Unit
                                    }
                                }
                                if (routeTab != 1) ScriptRunBanner(state, onOpen = { navigate(1) }, onStop = model::stop)
                                if (compact) NavigationBar(Modifier.testTag("bottom-navigation"),
                                    mode = NavigationBarDisplayMode.IconOnly,
                                    color = MiuixTheme.colorScheme.surfaceVariant, defaultWindowInsetsPadding = false) {
                                    navigationOrder.forEach { index ->
                                        val label = labels[index]
                                        NavigationBarItem(selected = routeTab == index, onClick = { navigate(index) },
                                            icon = navigationIcons[index], label = label)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun ScriptRunBanner(state: ConsoleState, onOpen: () -> Unit, onStop: () -> Unit) {
    var now by remember { mutableStateOf(kotlin.time.Clock.System.now().toEpochMilliseconds()) }
    val active = state.scriptRunPhase.active
    val recentTerminal = state.scriptFinishedAtEpochMillis?.let { now - it < 8_000 } == true
    LaunchedEffect(active, state.scriptFinishedAtEpochMillis) {
        while (active || state.scriptFinishedAtEpochMillis?.let { now - it < 8_000 } == true) {
            delay(1_000)
            now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        }
    }
    if (!active && !recentTerminal) return
    val elapsed = state.scriptStartedAtEpochMillis?.let { ((now - it).coerceAtLeast(0) / 1_000) }
    val elapsedText = elapsed?.let { "${it / 60}:${(it % 60).toString().padStart(2, '0')}" }
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
