package cn.elonzh.hanppie.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.app.HanppieBuildInfo
import cn.elonzh.hanppie.ui.app.getPlatformBuildInfo
import cn.elonzh.hanppie.ui.design.HanppieDesignTokens
import cn.elonzh.hanppie.ui.design.WorkbenchGlyph
import cn.elonzh.hanppie.ui.design.WorkbenchIcon
import cn.elonzh.hanppie.ui.i18n.tr
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AboutSettingsContent(
    modifier: Modifier = Modifier,
    buildInfo: HanppieBuildInfo = remember { getPlatformBuildInfo() },
) {
    val uriHandler = LocalUriHandler.current
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth().testTag("about-settings-content"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 1. Hero Brand Card
        Card(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(HanppieDesignTokens.CardRadius)),
            colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Image(
                    painter = painterResource(Res.drawable.hanppie_app_icon),
                    contentDescription = "Hanppie Icon",
                    modifier = Modifier
                        .size(76.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .border(1.dp, MiuixTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(20.dp)),
                )

                Text(
                    text = buildInfo.appName,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onSurface,
                )

                Text(
                    text = tr(Res.string.about_subtitle),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    textAlign = TextAlign.Center,
                )

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MiuixTheme.colorScheme.secondaryContainer)
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = "v${buildInfo.versionName}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MiuixTheme.colorScheme.primary,
                    )
                }
            }
        }

        // 2. Build & System Info Card
        Card(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(HanppieDesignTokens.CardRadius)),
            colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = tr(Res.string.about_build_info),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onSurface,
                )

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AboutInfoRow(label = tr(Res.string.about_app_version), value = "v${buildInfo.versionName}")
                    AboutInfoRow(label = tr(Res.string.about_platform), value = buildInfo.platformName)
                    AboutInfoRow(label = tr(Res.string.about_runtime), value = buildInfo.runtimeVersion)
                    AboutInfoRow(label = tr(Res.string.about_framework), value = buildInfo.frameworkVersion)
                    AboutInfoRow(label = tr(Res.string.about_protocol), value = buildInfo.protocols)
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MiuixTheme.colorScheme.dividerLine.copy(alpha = 0.4f)),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Button(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(buildInfo.formatDiagnosticReport()))
                            copied = true
                            coroutineScope.launch {
                                delay(2500)
                                copied = false
                            }
                        },
                        modifier = Modifier.heightIn(min = 40.dp).testTag("copy-diagnostic-info"),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            WorkbenchIcon(WorkbenchGlyph.COPY, MiuixTheme.colorScheme.onSurface, Modifier.size(16.dp))
                            Text(tr(Res.string.about_copy_system_info), fontSize = 13.sp)
                        }
                    }

                    AnimatedVisibility(
                        visible = copied,
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        Text(
                            text = tr(Res.string.about_info_copied),
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }

        // 3. Project & Open Source Card
        Card(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(HanppieDesignTokens.CardRadius)),
            colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = tr(Res.string.about_project_info),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onSurface,
                )

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Clickable repository link
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                try {
                                    uriHandler.openUri(buildInfo.repositoryUrl)
                                } catch (_: Throwable) {
                                    clipboardManager.setText(AnnotatedString(buildInfo.repositoryUrl))
                                }
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = tr(Res.string.about_repository),
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "GitHub",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = MiuixTheme.colorScheme.primary,
                            )
                            WorkbenchIcon(WorkbenchGlyph.OPEN, MiuixTheme.colorScheme.primary, Modifier.size(14.dp))
                        }
                    }

                    AboutInfoRow(label = tr(Res.string.about_license), value = buildInfo.license)
                    AboutInfoRow(label = tr(Res.string.about_developer), value = "elonzh")
                }
            }
        }

        // 4. Footer
        Text(
            text = "${buildInfo.copyright} · Released under ${buildInfo.license}",
            fontSize = 11.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp),
        )
    }
}

@Composable
private fun AboutInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}
