package cn.elonzh.hanppie.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.sample
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.window.WindowDialog

private val Ink = Color(0xff20252c)
private val Muted = Color(0xff78818d)
private val Blue = Color(0xff3868e8)
private val CanvasColor = Color(0xfff5f6f8)
private val labels get() = listOf(tr("设备"), tr("脚本"), tr("诊断"), tr("对话"), tr("设置"))

@Composable
@OptIn(FlowPreview::class, ExperimentalLayoutApi::class)
internal fun Console(
    model: ConsoleModel,
    document: MutableState<EditorDocument>,
    onOpen: () -> Unit = {},
    onSave: () -> Unit = {},
    fileError: String? = null,
    onSpeechSettings: (() -> Unit)? = null,
    onVoiceInput: (() -> Unit)? = null,
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
    var confirmOpen by remember { mutableStateOf(false) }
    var armed by remember(document.value.source) { mutableStateOf(false) }
    var details by rememberSaveable { mutableStateOf(false) }
    var diagnosticTab by rememberSaveable { mutableStateOf(0) }
    var cockpit by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.connected) { cockpit=state.connected }
    if(cockpit && state.connected) {
        RemotePage(model,Modifier.fillMaxSize().safeDrawingPadding(),onBack={cockpit=false},expanded=true)
        return
    }

    WindowDialog(show = manual, onDismissRequest = { manual = false }, title = tr("手动连接")) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(ip, { ip = it }, label = tr("机器人 IPv4"), singleLine = true)
            TextField(appId, { appId = it }, label = tr("AppID · 8 位十六进制"), singleLine = true)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button({ manual = false }) { Text(tr("取消")) }
                Spacer(Modifier.width(8.dp))
                Button({ model.connect(ip, appId); manual = false }, enabled = !state.busy && !state.connected,
                    colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr("连接")) }
            }
        }
    }
    WindowDialog(show = confirmOpen, onDismissRequest = { confirmOpen = false }, title = tr("替换未保存的脚本？"),
        summary = tr("当前修改尚未保存。")) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ confirmOpen = false }) { Text(tr("返回")) }
            Button({ confirmOpen = false; onOpen() }) { Text(tr("选择文件")) }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(CanvasColor).safeDrawingPadding().imePadding()) {
        val compact = maxWidth < 720.dp
        Row(Modifier.fillMaxSize()) {
            if (!compact) Column(Modifier.width(176.dp).fillMaxHeight().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("hanppie", Modifier.padding(12.dp, 20.dp), fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Ink)
                labels.forEachIndexed { index, label -> NavigationItem(index, label, selectedTab == index, false) { navigate(index) } }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Column(Modifier.weight(1f).fillMaxWidth().widthIn(max = 1080.dp)
                    .padding(horizontal = if (compact) 20.dp else 28.dp)) {
                    Row(Modifier.fillMaxWidth().height(72.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(if (selectedTab == 0) tr("我的机器人") else labels[selectedTab], fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Ink)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(6.dp).background(if (state.connected) Color(0xff32aa78) else Color(0xffaab1bb), RoundedCornerShape(50)))
                            Spacer(Modifier.width(6.dp))
                            Text(if (state.connected) tr("已连接") else tr("未连接"), fontSize = 12.sp, color = Muted)
                        }
                    }
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = Blue)
                    (fileError ?: state.error)?.let { Text(tr(it), Modifier.padding(vertical = 8.dp), color = Color(0xffc64848), fontSize = 13.sp) }
                    when (selectedTab) {
                        3 -> pageState.SaveableStateProvider("chat") { ChatPage(model, Modifier.weight(1f), onVoiceInput) { navigate(4) } }
                        4 -> SettingsPage(model, Modifier.weight(1f), onSpeechSettings)
                        0 -> if (state.connected) RemotePage(model, Modifier.weight(1f),onBack={cockpit=true}) else LazyColumn(Modifier.weight(1f).testTag("device-page"), verticalArrangement = Arrangement.spacedBy(16.dp),
                            contentPadding = PaddingValues(bottom = 20.dp)) {
                            item {
                                Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(22.dp)) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text("ROBOMASTER", fontSize = 11.sp, color = Muted, fontWeight = FontWeight.Medium)
                                            Text("S1", Modifier.padding(top = 4.dp), fontSize = 48.sp, color = Ink, fontWeight = FontWeight.Bold)
                                            Text(if (state.connected) state.status.substringAfterLast(' ') else tr("尚未连接"), fontSize = 13.sp, color = Muted)
                                        }
                                        RobotMark(Modifier.size(110.dp))
                                    }
                                    Spacer(Modifier.height(24.dp))
                                    if (state.connected || tr(state.status) == tr("连接失效")) {
                                        Button(model::disconnect, Modifier.fillMaxWidth(), enabled = !state.busy) { Text(tr("断开 / 清理会话")) }
                                    } else {
                                        Button(model::discover, Modifier.fillMaxWidth(), enabled = !state.busy,
                                            colors = ButtonDefaults.buttonColorsPrimary()) { Text(if (state.busy) tr("搜索中…") else tr("搜索设备")) }
                                        Text(tr("手动连接"), Modifier.align(Alignment.CenterHorizontally).clickable { manual = true }
                                            .padding(top = 16.dp, bottom = 2.dp), color = Blue, fontSize = 14.sp)
                                    }
                                }
                            }
                            items(state.devices) { device ->
                                Button({ model.connect(device.ip, device.appId) }, Modifier.fillMaxWidth(), enabled = !state.connected && !state.busy) {
                                    Text("S1  ·  ${device.ip}", Modifier.weight(1f)); Text(tr("连接"), color = Blue)
                                }
                            }
                            item {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Metric(tr("电量"), state.battery?.let { "$it%" } ?: "—", Modifier.weight(1f))
                                    Metric(tr("接收帧"), state.packets.toString(), Modifier.weight(1f))
                                }
                            }
                            if (!state.connected) item {
                                Text(tr("手机或电脑需与 S1 连接同一 Wi-Fi"), fontSize = 12.sp, color = Muted)
                            }
                        }
                        1 -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text((document.value.path?.substringAfterLast('/') ?: tr("新脚本")) + if (document.value.dirty) tr(" · 未保存") else "", fontSize = 14.sp)
                                    Text("Python 3.6", fontSize = 11.sp, color = Muted)
                                }
                                Button({ if (document.value.dirty) confirmOpen = true else onOpen() }, enabled = !document.value.busy,
                                    insideMargin = PaddingValues(12.dp, 8.dp)) { Text(tr("打开 .py"), fontSize = 13.sp) }
                                Spacer(Modifier.width(6.dp))
                                Button(onSave, enabled = !document.value.busy, insideMargin = PaddingValues(12.dp, 8.dp)) { Text(tr("保存 .py"), fontSize = 13.sp) }
                            }
                            Box(Modifier.weight(1f).fillMaxWidth().background(Color.White, RoundedCornerShape(20.dp)).padding(16.dp)) {
                                BasicTextField(document.value.source, { document.value = document.value.copy(source = it) },
                                    Modifier.fillMaxSize().testTag("script-editor").verticalScroll(rememberScrollState()),
                                    enabled = !document.value.busy, cursorBrush = SolidColor(Blue),
                                    textStyle = TextStyle(color = Ink, fontSize = 14.sp, lineHeight = 23.sp, fontFamily = FontFamily.Monospace))
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(if (armed) ToggleableState.On else ToggleableState.Off, { armed = !armed })
                                Text(tr("允许执行此脚本"), Modifier.padding(start = 8.dp).weight(1f), fontSize = 13.sp)
                                Text(tr("执行说明"), Modifier.clickable { details = !details }.padding(8.dp), color = Muted, fontSize = 12.sp)
                            }
                            if (details) Text(tr("脚本可能产生机械动作。编辑后需重新上传；断开连接不保证机内脚本停止。"), fontSize = 12.sp, color = Muted)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button({ model.upload(document.value.source) }, enabled = state.canUpload(document.value.source)) { Text(tr("上传（不启动）"), fontSize = 13.sp) }
                                Button(model::start, enabled = state.canStart(document.value.source, armed), colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr("执行已上传脚本"), fontSize = 13.sp) }
                                Button(model::stop, enabled = state.canStop) { Text(tr("停止脚本"), fontSize = 13.sp) }
                            }
                            Text(tr(state.scriptStatus), fontSize = 12.sp, color = Muted)
                            if (state.scriptMessages.isNotEmpty()) LazyColumn(Modifier.heightIn(max = 64.dp)) {
                                items(state.scriptMessages.takeLast(10)) { Text(it, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                        2 -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                listOf(tr("日志"), tr("遥测"), tr("报文")).forEachIndexed { index, label ->
                                    Text(label, Modifier.clickable { diagnosticTab = index }.padding(12.dp),
                                        color = if (diagnosticTab == index) Blue else Muted, fontSize = 14.sp)
                                }
                                Spacer(Modifier.weight(1f))
                                Text(tr("清空"), Modifier.clickable(onClick = model::clearLogs).padding(12.dp), color = Muted, fontSize = 13.sp)
                            }
                            SelectionContainer(Modifier.weight(1f)) {
                                LazyColumn(Modifier.fillMaxSize().background(Color.White, RoundedCornerShape(20.dp)), contentPadding = PaddingValues(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    if (diagnosticTab == 1) {
                                        item { Text(tr("原始字段 · 未标定"), fontSize = 12.sp, color = Muted) }
                                        items(state.values) { (key, value) ->
                                            Row(Modifier.fillMaxWidth()) { Text(key, Modifier.weight(1f), fontSize = 12.sp); Text(value, fontSize = 12.sp) }
                                        }
                                    } else {
                                        val lines = if (diagnosticTab == 0) state.logs else state.frames
                                        if (lines.isEmpty()) item { Text(tr("暂无记录"), color = Muted, fontSize = 13.sp) }
                                        items(lines) { Text(it, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
                                    }
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                        }

                    }
                }
                if (compact) Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 8.dp, vertical = 6.dp).testTag("bottom-navigation")) {
                    labels.forEachIndexed { index, label ->
                        Box(Modifier.weight(1f)) { NavigationItem(index, label, selectedTab == index, true) { navigate(index) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun NavigationItem(index: Int, label: String, selected: Boolean, compact: Boolean, action: () -> Unit) {
    val color = if (selected) Blue else Muted
    val modifier = Modifier.fillMaxWidth().background(if (selected && !compact) Color.White else Color.Transparent,
        RoundedCornerShape(16.dp)).clickable(onClick = action).padding(if (compact) 8.dp else 14.dp)
    if (compact) Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        NavigationIcon(index, color); Text(label, color = color, fontSize = 11.sp)
    } else Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        NavigationIcon(index, color); Text(label, Modifier.padding(start = 12.dp), color = color, fontSize = 14.sp)
    }
}

@Composable
private fun NavigationIcon(index: Int, color: Color) {
    Canvas(Modifier.size(22.dp)) {
        val w = size.width; val h = size.height; val stroke = 1.7.dp.toPx()
        when (index) {
            0 -> { drawRoundRect(color, Offset(w*.15f,h*.25f), Size(w*.7f,h*.55f), androidx.compose.ui.geometry.CornerRadius(w*.12f), style = Stroke(stroke))
                drawCircle(color,w*.05f,Offset(w*.35f,h*.5f)); drawCircle(color,w*.05f,Offset(w*.65f,h*.5f)); drawLine(color,Offset(w*.5f,0f),Offset(w*.5f,h*.25f),stroke) }
            1 -> { drawLine(color,Offset(w*.3f,h*.2f),Offset(w*.08f,h*.5f),stroke); drawLine(color,Offset(w*.08f,h*.5f),Offset(w*.3f,h*.8f),stroke)
                drawLine(color,Offset(w*.7f,h*.2f),Offset(w*.92f,h*.5f),stroke); drawLine(color,Offset(w*.92f,h*.5f),Offset(w*.7f,h*.8f),stroke); drawLine(color,Offset(w*.57f,h*.12f),Offset(w*.43f,h*.88f),stroke) }
            2 -> { repeat(3) { i -> drawLine(color,Offset(w*.16f,h*(.25f+i*.25f)),Offset(w*.84f,h*(.25f+i*.25f)),stroke) } }
            3 -> { drawRoundRect(color, Offset(w*.1f,h*.12f), Size(w*.8f,h*.62f), androidx.compose.ui.geometry.CornerRadius(w*.15f), style = Stroke(stroke))
                drawLine(color,Offset(w*.28f,h*.74f),Offset(w*.2f,h*.93f),stroke); drawLine(color,Offset(w*.2f,h*.93f),Offset(w*.5f,h*.74f),stroke) }
            else -> { repeat(3) { i -> val y = h*(.2f+i*.3f); val x = w*(if (i == 1) .65f else .35f)
                drawLine(color,Offset(w*.1f,y),Offset(w*.9f,y),stroke)
                drawCircle(color,w*.1f,Offset(x,y),style = Stroke(stroke)) } }
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier) {
    Card(modifier, insideMargin = PaddingValues(18.dp)) {
        Text(label, color = Muted, fontSize = 12.sp)
        Text(value, Modifier.padding(top = 10.dp), fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = Ink)
    }
}

@Composable
private fun RobotMark(modifier: Modifier) {
    Canvas(modifier.semantics { contentDescription = tr("S1 设备图标") }) {
        val w = size.width; val h = size.height
        drawCircle(Color(0xffedf1fa), w*.49f)
        drawRoundRect(Color(0xffb7c3d5), Offset(w*.18f,h*.55f), Size(w*.65f,h*.2f), androidx.compose.ui.geometry.CornerRadius(w*.08f))
        listOf(.18f,.67f).forEach { x -> drawRoundRect(Ink, Offset(w*x,h*.58f),Size(w*.15f,h*.25f),androidx.compose.ui.geometry.CornerRadius(w*.04f)) }
        drawRoundRect(Color(0xff4e6078), Offset(w*.33f,h*.28f),Size(w*.33f,h*.32f),androidx.compose.ui.geometry.CornerRadius(w*.06f))
        drawRoundRect(Blue,Offset(w*.47f,h*.36f),Size(w*.4f,h*.09f),androidx.compose.ui.geometry.CornerRadius(w*.025f))
        drawCircle(Color.White,w*.06f,Offset(w*.44f,h*.35f))
    }
}
