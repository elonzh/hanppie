package cn.elonzh.hanppie.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.elonzh.hanppie.resources.Res
import cn.elonzh.hanppie.resources.data_directory
import cn.elonzh.hanppie.resources.open_data_directory
import cn.elonzh.hanppie.resources.open_data_directory_failed
import cn.elonzh.hanppie.ui.design.HanppieDesignTokens
import cn.elonzh.hanppie.ui.design.WorkbenchGlyph
import cn.elonzh.hanppie.ui.design.WorkbenchIcon
import cn.elonzh.hanppie.ui.design.WorkbenchIconButton
import cn.elonzh.hanppie.ui.i18n.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal class DataDirectoryAccess(val path: String, val open: suspend () -> Unit)

internal val LocalDataDirectoryAccess = staticCompositionLocalOf<DataDirectoryAccess?> { null }

@Composable
internal fun DataDirectorySetting() {
    val directory = LocalDataDirectoryAccess.current ?: return
    val scope = rememberCoroutineScope()
    var opening by remember(directory) { mutableStateOf(false) }
    var failed by remember(directory) { mutableStateOf(false) }
    val colors = MiuixTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(HanppieDesignTokens.CardRadius)),
        colors = CardDefaults.defaultColors(color = colors.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                WorkbenchIcon(
                    WorkbenchGlyph.FOLDER,
                    colors.onSurfaceVariantSummary,
                    Modifier.size(20.dp)
                )
                Text(
                    tr(Res.string.data_directory), fontSize = 15.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                WorkbenchIconButton(
                    label = tr(Res.string.open_data_directory),
                    glyph = WorkbenchGlyph.OPEN,
                    enabled = !opening,
                    tag = "open-data-directory",
                    onClick = {
                        opening = true
                        failed = false
                        scope.launch {
                            try {
                                directory.open()
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                failed = true
                            } finally {
                                opening = false
                            }
                        }
                    },
                )
            }
            SelectionContainer {
                Text(
                    directory.path,
                    modifier = Modifier.fillMaxWidth().testTag("data-directory-path"),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colors.onSurfaceVariantSummary
                )
            }
            if (failed) Text(
                tr(Res.string.open_data_directory_failed), fontSize = 12.sp,
                color = colors.error
            )
        }
    }
}
