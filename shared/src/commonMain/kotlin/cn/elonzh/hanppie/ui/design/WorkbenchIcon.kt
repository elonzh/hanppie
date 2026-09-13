package cn.elonzh.hanppie.ui.design

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Activity
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.ArrowLeftRight
import com.composables.icons.lucide.BatteryMedium
import com.composables.icons.lucide.Bot
import com.composables.icons.lucide.Camera
import com.composables.icons.lucide.ChartNoAxesColumnIncreasing
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Circle
import com.composables.icons.lucide.CodeXml
import com.composables.icons.lucide.Crosshair
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Ellipsis
import com.composables.icons.lucide.ExternalLink
import com.composables.icons.lucide.File
import com.composables.icons.lucide.FileInput
import com.composables.icons.lucide.FileOutput
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.Folder
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageSquare
import com.composables.icons.lucide.Mic
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Plug
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Save
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.SendHorizontal
import com.composables.icons.lucide.SlidersHorizontal
import com.composables.icons.lucide.Square
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Upload
import com.composables.icons.lucide.Video
import com.composables.icons.lucide.VideoOff
import com.composables.icons.lucide.Volume2
import com.composables.icons.lucide.VolumeX
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal enum class WorkbenchGlyph {
    BACK, ADD, IMPORT, EXPORT, UPLOAD, DOWNLOAD, SAVE, EDIT, DELETE, STOP, ACTIVITY, CHEVRON_RIGHT,
    FILE, FILE_TEXT, FOLDER, REFRESH, MORE, OPEN, VIDEO, VIDEO_OFF, SPEAKER, MUTED, CAMERA, RECORD,
    CROSSHAIR, SEARCH, CONNECT, BATTERY, SIGNAL, PACKETS, MICROPHONE, SEND, ROBOT, CODE, CHAT, SETTINGS,
}

internal val WorkbenchGlyph.icon: ImageVector get() = when (this) {
    WorkbenchGlyph.BACK -> Lucide.ArrowLeft
    WorkbenchGlyph.ADD -> Lucide.Plus
    WorkbenchGlyph.IMPORT -> Lucide.FileInput
    WorkbenchGlyph.EXPORT -> Lucide.FileOutput
    WorkbenchGlyph.UPLOAD -> Lucide.Upload
    WorkbenchGlyph.DOWNLOAD -> Lucide.Download
    WorkbenchGlyph.SAVE -> Lucide.Save
    WorkbenchGlyph.EDIT -> Lucide.Pencil
    WorkbenchGlyph.DELETE -> Lucide.Trash2
    WorkbenchGlyph.STOP -> Lucide.Square
    WorkbenchGlyph.ACTIVITY -> Lucide.Activity
    WorkbenchGlyph.CHEVRON_RIGHT -> Lucide.ChevronRight
    WorkbenchGlyph.FILE -> Lucide.File
    WorkbenchGlyph.FILE_TEXT -> Lucide.FileText
    WorkbenchGlyph.FOLDER -> Lucide.Folder
    WorkbenchGlyph.REFRESH -> Lucide.RefreshCw
    WorkbenchGlyph.MORE -> Lucide.Ellipsis
    WorkbenchGlyph.OPEN -> Lucide.ExternalLink
    WorkbenchGlyph.VIDEO -> Lucide.Video
    WorkbenchGlyph.VIDEO_OFF -> Lucide.VideoOff
    WorkbenchGlyph.SPEAKER -> Lucide.Volume2
    WorkbenchGlyph.MUTED -> Lucide.VolumeX
    WorkbenchGlyph.CAMERA -> Lucide.Camera
    WorkbenchGlyph.RECORD -> Lucide.Circle
    WorkbenchGlyph.CROSSHAIR -> Lucide.Crosshair
    WorkbenchGlyph.SEARCH -> Lucide.Search
    WorkbenchGlyph.CONNECT -> Lucide.Plug
    WorkbenchGlyph.BATTERY -> Lucide.BatteryMedium
    WorkbenchGlyph.SIGNAL -> Lucide.ChartNoAxesColumnIncreasing
    WorkbenchGlyph.PACKETS -> Lucide.ArrowLeftRight
    WorkbenchGlyph.MICROPHONE -> Lucide.Mic
    WorkbenchGlyph.SEND -> Lucide.SendHorizontal
    WorkbenchGlyph.ROBOT -> Lucide.Bot
    WorkbenchGlyph.CODE -> Lucide.CodeXml
    WorkbenchGlyph.CHAT -> Lucide.MessageSquare
    WorkbenchGlyph.SETTINGS -> Lucide.SlidersHorizontal
}

internal val navigationIcons = listOf(
    WorkbenchGlyph.ROBOT.icon,
    WorkbenchGlyph.CODE.icon,
    WorkbenchGlyph.ACTIVITY.icon,
    WorkbenchGlyph.CHAT.icon,
    WorkbenchGlyph.SETTINGS.icon,
)

@Composable
internal fun WorkbenchIconButton(
    label: String,
    glyph: WorkbenchGlyph,
    onClick: () -> Unit,
    enabled: Boolean = true,
    primary: Boolean = false,
    danger: Boolean = false,
    tag: String? = null,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    val background = when {
        danger -> colors.errorContainer
        primary -> colors.primary
        else -> colors.surfaceContainerHigh
    }
    val ink = when {
        danger -> colors.onErrorContainer
        primary -> colors.onPrimary
        else -> colors.onSurfaceVariantSummary
    }.copy(alpha = if (enabled) 1f else .38f)
    val tagged = if (tag == null) modifier else modifier.testTag(tag)
    IconButton(
        onClick = onClick,
        enabled = enabled,
        cornerRadius = 14.dp,
        modifier = tagged.size(48.dp).semantics {
            contentDescription = label
            role = Role.Button
            if (!enabled) disabled()
        },
        backgroundColor = background.copy(alpha = if (enabled) 1f else .35f),
    ) {
        WorkbenchIcon(glyph, ink)
    }
}

@Composable
internal fun WorkbenchIcon(
    glyph: WorkbenchGlyph,
    color: Color = MiuixTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier,
) {
    Icon(glyph.icon, contentDescription = null, modifier = modifier.size(24.dp), tint = color)
}
