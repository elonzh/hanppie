package cn.elonzh.hanppie.ui.robot.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.robot.files.RobotFileEntry
import cn.elonzh.hanppie.ui.app.ConsoleController
import cn.elonzh.hanppie.ui.app.ConsoleState
import cn.elonzh.hanppie.ui.design.DesktopListScrollbar
import cn.elonzh.hanppie.ui.design.HanppieDesignTokens
import cn.elonzh.hanppie.ui.design.WorkbenchGlyph
import cn.elonzh.hanppie.ui.design.WorkbenchIcon
import cn.elonzh.hanppie.ui.i18n.tr
import cn.elonzh.hanppie.ui.robot.files.RobotFilesPage
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.TabRowDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal const val RobotFilesDebugTab = 3

@Composable
internal fun DebugPage(
    model: ConsoleController,
    state: ConsoleState,
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier,
    onRobotFileUpload: ((String) -> Unit)? = null,
    onRobotFileDownload: ((RobotFileEntry) -> Unit)? = null,
    onRobotFileOpen: ((RobotFileEntry) -> Unit)? = null,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TabRow(
            tabs = listOf(
                tr(Res.string.logs),
                tr(Res.string.telemetry),
                tr(Res.string.packets_2),
                tr(Res.string.ftp_files_tab),
            ),
            selectedTabIndex = selectedTab,
            onTabSelected = onTabSelected,
            modifier = Modifier.fillMaxWidth().testTag("debug-tabs"),
            minWidth = if (compact) 72.dp else 96.dp,
            maxWidth = if (compact) 108.dp else 144.dp,
            height = HanppieDesignTokens.TouchTarget,
            itemSpacing = 4.dp,
            colors = TabRowDefaults.tabRowColors(
                selectedBackgroundColor = MiuixTheme.colorScheme.primaryVariant,
                selectedContentColor = MiuixTheme.colorScheme.onPrimaryVariant,
            ),
        )

        if (selectedTab == RobotFilesDebugTab) {
            if (state.connected) {
                LaunchedEffect(state.connectedAddress) { model.robotFiles.refresh() }
                RobotFilesPage(
                    model = model,
                    compact = compact,
                    modifier = Modifier.weight(1f),
                    onUpload = onRobotFileUpload,
                    onDownload = onRobotFileDownload,
                    onOpen = onRobotFileOpen,
                )
            } else {
                Column(
                    Modifier.weight(1f).fillMaxWidth().testTag("robot-files-disconnected"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    WorkbenchIcon(WorkbenchGlyph.CONNECT, MiuixTheme.colorScheme.onSurfaceVariantSummary, Modifier.size(40.dp))
                    Spacer(Modifier.height(16.dp))
                    Text(tr(Res.string.connect_to_manage_robot_files), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
            return@Column
        }

        val diagnosticScroll = androidx.compose.foundation.lazy.rememberLazyListState()
        LaunchedEffect(selectedTab) { diagnosticScroll.scrollToItem(0) }
        Box(Modifier.weight(1f)) {
            SelectionContainer(Modifier.fillMaxSize().padding(end = 12.dp)) {
                LazyColumn(
                    Modifier.fillMaxSize().testTag("diagnostic-output")
                        .background(MiuixTheme.colorScheme.surfaceContainer, RoundedCornerShape(HanppieDesignTokens.CardRadius)),
                    state = diagnosticScroll,
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (selectedTab == 1) {
                        state.gimbal?.let { gimbal ->
                            item { Text(tr(Res.string.gimbal_protocol_angles_last_received), fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
                            val angles = listOf(
                                tr(Res.string.gimbal_yaw) to "${gimbal.yawDegrees}°",
                                tr(Res.string.gimbal_pitch) to "${gimbal.pitchDegrees}°",
                                tr(Res.string.ground_reference_yaw) to "${gimbal.groundYawDegrees}°",
                                tr(Res.string.ground_reference_pitch) to "${gimbal.groundPitchDegrees}°",
                                tr(Res.string.gimbal_status_byte) to "0x${gimbal.status.toString(16).padStart(2, '0')}",
                            )
                            items(angles) { (key, value) ->
                                Row(Modifier.fillMaxWidth()) {
                                    Text(key, Modifier.weight(1f), fontSize = 12.sp)
                                    Text(value, fontSize = 12.sp)
                                }
                            }
                        }
                        item { Text(tr(Res.string.raw_fields_uncalibrated), fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
                        items(state.values) { (key, value) ->
                            Row(Modifier.fillMaxWidth()) {
                                Text(key, Modifier.weight(1f), fontSize = 12.sp)
                                Text(value, fontSize = 12.sp)
                            }
                        }
                    } else {
                        val lines = if (selectedTab == 0) state.logs else state.frames
                        if (lines.isEmpty()) item {
                            Column(
                                Modifier.fillMaxWidth().padding(vertical = 72.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                WorkbenchIcon(WorkbenchGlyph.FILE_TEXT, MiuixTheme.colorScheme.onSurfaceVariantSummary, Modifier.size(40.dp))
                                Text(tr(Res.string.no_records), color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 14.sp)
                            }
                        }
                        items(lines) { Text(it, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
                    }
                }
            }
            DesktopListScrollbar(
                diagnosticScroll,
                Modifier.align(Alignment.CenterEnd).fillMaxHeight().testTag("diagnostic-scrollbar"),
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}
