package cn.elonzh.hanppie.ui.robot.device

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.robot.protocol.RouterProvisioning
import cn.elonzh.hanppie.ui.design.WorkbenchGlyph
import cn.elonzh.hanppie.ui.design.WorkbenchIcon
import cn.elonzh.hanppie.ui.design.WorkbenchIconButton
import cn.elonzh.hanppie.ui.i18n.tr
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal enum class ConnectionGuideMode { DIRECT, ROUTER }

@Composable
internal fun ConnectionGuidePage(
    mode: ConnectionGuideMode?,
    compact: Boolean,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onSelectMode: (ConnectionGuideMode) -> Unit,
    onOpenWifiSettings: (() -> Unit)?,
    onDiscover: () -> Unit,
    routerSsid: String,
    routerPassword: String,
    appId: String,
    onPairRouter: (String, String) -> Unit,
    busy: Boolean,
) {
    LazyColumn(
        modifier.fillMaxWidth().testTag("connection-guide-page"),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(bottom = if (compact) 88.dp else 24.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().height(72.dp), verticalAlignment = Alignment.CenterVertically) {
                WorkbenchIconButton(tr(Res.string.back), WorkbenchGlyph.BACK, onBack, tag = "connection-guide-back")
                Spacer(Modifier.width(12.dp))
                Text(
                    tr(if (mode == null) Res.string.connect_robot else if (mode == ConnectionGuideMode.DIRECT) Res.string.direct_mode else Res.string.router_mode),
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        if (mode == null) {
            item { ConnectionModeSelection(compact, onSelectMode) }
        } else {
            item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    ConnectionSteps(
                        mode,
                        compact,
                        onOpenWifiSettings,
                        onDiscover,
                        routerSsid,
                        routerPassword,
                        appId,
                        onPairRouter,
                        busy,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectionModeSelection(compact: Boolean, onSelectMode: (ConnectionGuideMode) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tr(Res.string.select_connection_mode), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text(
                tr(Res.string.connection_mode_summary),
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        if (compact) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ConnectionModeCard(ConnectionGuideMode.DIRECT, Modifier.fillMaxWidth(), onSelectMode)
                ConnectionModeCard(ConnectionGuideMode.ROUTER, Modifier.fillMaxWidth(), onSelectMode)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ConnectionModeCard(ConnectionGuideMode.DIRECT, Modifier.weight(1f), onSelectMode)
                ConnectionModeCard(ConnectionGuideMode.ROUTER, Modifier.weight(1f), onSelectMode)
            }
        }
    }
}

@Composable
private fun ConnectionModeCard(mode: ConnectionGuideMode, modifier: Modifier, onSelect: (ConnectionGuideMode) -> Unit) {
    val colors = MiuixTheme.colorScheme
    val direct = mode == ConnectionGuideMode.DIRECT
    Card(
        modifier
            .heightIn(min = 156.dp)
            .testTag(if (direct) "direct-mode" else "router-mode")
            .clickable(role = Role.Button) { onSelect(mode) },
        insideMargin = PaddingValues(0.dp),
        colors = CardDefaults.defaultColors(color = colors.surfaceContainer),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                Modifier.size(52.dp).background(colors.primary.copy(alpha = .14f), RoundedCornerShape(17.dp)),
                contentAlignment = Alignment.Center,
            ) {
                WorkbenchIcon(if (direct) WorkbenchGlyph.WIFI else WorkbenchGlyph.ROUTER, colors.primary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr(if (direct) Res.string.direct_mode else Res.string.router_mode), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    tr(if (direct) Res.string.direct_mode_description else Res.string.router_mode_description),
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = colors.onSurfaceVariantSummary,
                )
            }
            WorkbenchIcon(WorkbenchGlyph.CHEVRON_RIGHT, colors.onSurfaceVariantSummary)
        }
    }
}

@Composable
private fun ConnectionSteps(
    mode: ConnectionGuideMode,
    compact: Boolean,
    onOpenWifiSettings: (() -> Unit)?,
    onDiscover: () -> Unit,
    routerSsid: String,
    routerPassword: String,
    appId: String,
    onPairRouter: (String, String) -> Unit,
    busy: Boolean,
) {
    val direct = mode == ConnectionGuideMode.DIRECT
    val colors = MiuixTheme.colorScheme
    Column(Modifier.widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(
            Modifier.fillMaxWidth(),
            insideMargin = PaddingValues(20.dp),
            colors = CardDefaults.defaultColors(color = colors.surfaceContainer),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(
                    Modifier.size(52.dp).background(colors.primary.copy(alpha = .14f), RoundedCornerShape(17.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    WorkbenchIcon(if (direct) WorkbenchGlyph.WIFI else WorkbenchGlyph.ROUTER, colors.primary)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(tr(if (direct) Res.string.direct_mode else Res.string.router_mode), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        tr(if (direct) Res.string.direct_mode_description else Res.string.router_mode_description),
                        fontSize = 13.sp,
                        color = colors.onSurfaceVariantSummary,
                    )
                }
            }
        }
        if (direct) {
            Card(
                Modifier.fillMaxWidth(),
                insideMargin = PaddingValues(20.dp),
                colors = CardDefaults.defaultColors(color = colors.surfaceContainer),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    ConnectionStep(1, tr(Res.string.switch_to_direct_mode), tr(Res.string.switch_to_direct_mode_detail))
                    ConnectionStep(2, tr(Res.string.connect_robot_wifi), tr(Res.string.connect_robot_wifi_detail))
                    ConnectionStep(3, tr(Res.string.return_to_hanppie), tr(Res.string.return_to_hanppie_detail))
                }
            }
        } else {
            RouterProvisioningCard(compact, routerSsid, routerPassword, appId, onPairRouter, busy)
        }
        Card(
            Modifier.fillMaxWidth(),
            insideMargin = PaddingValues(18.dp),
            colors = CardDefaults.defaultColors(color = colors.surfaceContainerHigh),
        ) {
            Text(
                tr(if (direct) Res.string.direct_mode_help else Res.string.router_mode_help),
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = colors.onSurfaceVariantSummary,
            )
        }
        if (onOpenWifiSettings != null) {
            Button(
                onOpenWifiSettings,
                Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("open-wifi-settings"),
                enabled = !busy,
            ) {
                WorkbenchIcon(WorkbenchGlyph.WIFI)
                Spacer(Modifier.width(10.dp))
                Text(tr(Res.string.open_wifi_settings))
            }
        }
        Button(
            onDiscover,
            Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("discover-robot"),
            colors = ButtonDefaults.buttonColorsPrimary(),
            enabled = !busy,
        ) {
            WorkbenchIcon(WorkbenchGlyph.SEARCH, colors.onPrimary)
            Spacer(Modifier.width(10.dp))
            Text(tr(if (direct) Res.string.wifi_connected_find_robot else Res.string.find_robot_on_network))
        }
    }
}

@Composable
private fun RouterProvisioningCard(
    compact: Boolean,
    savedSsid: String,
    savedPassword: String,
    appId: String,
    onPairRouter: (String, String) -> Unit,
    busy: Boolean,
) {
    val colors = MiuixTheme.colorScheme
    var ssid by remember(savedSsid) { mutableStateOf(savedSsid) }
    var password by remember(savedPassword) { mutableStateOf(savedPassword) }
    val ssidBytes = ssid.encodeToByteArray().size
    val passwordBytes = password.encodeToByteArray().size
    val validationMessage = when {
        ssidBytes > RouterProvisioning.MAX_SSID_BYTES -> tr(Res.string.router_network_name_too_long)
        password.isNotEmpty() && passwordBytes !in RouterProvisioning.MIN_PASSWORD_BYTES..RouterProvisioning.MAX_PASSWORD_BYTES ->
            tr(Res.string.router_network_password_invalid)
        else -> null
    }
    val valid = ssidBytes in 1..RouterProvisioning.MAX_SSID_BYTES &&
        passwordBytes in RouterProvisioning.MIN_PASSWORD_BYTES..RouterProvisioning.MAX_PASSWORD_BYTES
    val payload = remember(ssid, password, appId) {
        if (valid && Regex("[0-9a-f]{8}").matches(appId)) RouterProvisioning.encode(ssid, password, appId) else null
    }

    Card(
        Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(20.dp),
        colors = CardDefaults.defaultColors(color = colors.surfaceContainer),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            ConnectionStep(1, tr(Res.string.switch_to_router_mode), tr(Res.string.switch_to_router_mode_detail))
            ConnectionStep(2, tr(Res.string.configure_robot_network), tr(Res.string.configure_robot_network_detail))
            if (compact) {
                RouterCredentials(ssid, { ssid = it }, password, { password = it }, validationMessage, valid)
                RouterQrCode(payload, Modifier.align(Alignment.CenterHorizontally))
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RouterCredentials(
                        ssid,
                        { ssid = it },
                        password,
                        { password = it },
                        validationMessage,
                        valid,
                        Modifier.weight(1f),
                    )
                    RouterQrCode(payload)
                }
            }
            ConnectionStep(3, tr(Res.string.scan_router_qr), tr(Res.string.scan_router_qr_detail))
            Button(
                onClick = { onPairRouter(ssid, password) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("pair-router"),
                enabled = valid && !busy,
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(tr(if (busy) Res.string.waiting_for_robot_to_scan_qr else Res.string.start_waiting_for_scan))
            }
            ConnectionStep(4, tr(Res.string.join_the_same_network), tr(Res.string.join_the_same_network_detail))
        }
    }
}

@Composable
private fun RouterCredentials(
    ssid: String,
    onSsidChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    validationMessage: String?,
    valid: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextField(
            value = ssid,
            onValueChange = onSsidChange,
            modifier = Modifier.fillMaxWidth().testTag("router-ssid"),
            label = tr(Res.string.router_network_name),
            singleLine = true,
        )
        TextField(
            value = password,
            onValueChange = onPasswordChange,
            modifier = Modifier.fillMaxWidth().testTag("router-password"),
            label = tr(Res.string.router_network_password),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
        )
        Text(
            validationMessage ?: tr(if (valid) Res.string.router_qr_ready else Res.string.enter_router_credentials),
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = if (validationMessage == null) colors.onSurfaceVariantSummary else colors.error,
        )
    }
}

@Composable
private fun RouterQrCode(payload: String?, modifier: Modifier = Modifier) {
    Box(
        modifier.size(232.dp).background(Color.White, RoundedCornerShape(18.dp)).padding(14.dp)
            .testTag(if (payload == null) "router-qr-placeholder" else "router-qr-code"),
        contentAlignment = Alignment.Center,
    ) {
        if (payload == null) {
            Text(
                tr(Res.string.enter_router_credentials),
                modifier = Modifier.padding(18.dp),
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = Color(0xff59636b),
            )
        } else {
            Image(
                painter = rememberQrCodePainter(payload),
                contentDescription = tr(Res.string.router_provisioning_qr),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun ConnectionStep(number: Int, title: String, detail: String) {
    val colors = MiuixTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(30.dp).background(colors.primary.copy(alpha = .14f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(number.toString(), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colors.primary)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, fontSize = 13.sp, lineHeight = 19.sp, color = colors.onSurfaceVariantSummary)
        }
    }
}
