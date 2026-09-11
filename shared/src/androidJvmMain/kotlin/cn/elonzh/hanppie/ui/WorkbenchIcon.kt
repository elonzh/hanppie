package cn.elonzh.hanppie.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal enum class WorkbenchGlyph {
    BACK, ADD, IMPORT, EXPORT, SAVE, EDIT, DELETE, STOP, ACTIVITY, CHEVRON_RIGHT,
}

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
    Canvas(modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 2.dp.toPx()
        val outline = Stroke(stroke, cap = StrokeCap.Round)
        when (glyph) {
            WorkbenchGlyph.BACK -> {
                drawLine(color, Offset(w * .78f, h * .5f), Offset(w * .24f, h * .5f), stroke, StrokeCap.Round)
                drawLine(color, Offset(w * .24f, h * .5f), Offset(w * .47f, h * .25f), stroke, StrokeCap.Round)
                drawLine(color, Offset(w * .24f, h * .5f), Offset(w * .47f, h * .75f), stroke, StrokeCap.Round)
            }
            WorkbenchGlyph.ADD -> {
                drawLine(color, Offset(w * .5f, h * .2f), Offset(w * .5f, h * .8f), stroke, StrokeCap.Round)
                drawLine(color, Offset(w * .2f, h * .5f), Offset(w * .8f, h * .5f), stroke, StrokeCap.Round)
            }
            WorkbenchGlyph.IMPORT, WorkbenchGlyph.EXPORT -> {
                val importing = glyph == WorkbenchGlyph.IMPORT
                val tipY = if (importing) h * .66f else h * .16f
                val tailY = if (importing) h * .16f else h * .66f
                drawLine(color, Offset(w * .5f, tailY), Offset(w * .5f, tipY), stroke, StrokeCap.Round)
                val wingY = if (importing) h * .48f else h * .34f
                drawLine(color, Offset(w * .5f, tipY), Offset(w * .33f, wingY), stroke, StrokeCap.Round)
                drawLine(color, Offset(w * .5f, tipY), Offset(w * .67f, wingY), stroke, StrokeCap.Round)
                drawLine(color, Offset(w * .18f, h * .78f), Offset(w * .82f, h * .78f), stroke, StrokeCap.Round)
                drawLine(color, Offset(w * .18f, h * .78f), Offset(w * .18f, h * .64f), stroke, StrokeCap.Round)
                drawLine(color, Offset(w * .82f, h * .78f), Offset(w * .82f, h * .64f), stroke, StrokeCap.Round)
            }
            WorkbenchGlyph.SAVE -> {
                drawRoundRect(color, Offset(w * .18f, h * .14f), Size(w * .64f, h * .72f), CornerRadius(w * .05f), style = outline)
                drawRect(color, Offset(w * .3f, h * .14f), Size(w * .34f, h * .24f), style = outline)
                drawRoundRect(color, Offset(w * .32f, h * .57f), Size(w * .36f, h * .29f), CornerRadius(w * .04f), style = outline)
            }
            WorkbenchGlyph.EDIT -> {
                drawLine(color, Offset(w * .26f, h * .72f), Offset(w * .7f, h * .28f), stroke * 1.7f, StrokeCap.Round)
                drawLine(color, Offset(w * .2f, h * .8f), Offset(w * .34f, h * .76f), stroke, StrokeCap.Round)
                drawLine(color, Offset(w * .66f, h * .24f), Offset(w * .76f, h * .34f), stroke, StrokeCap.Round)
            }
            WorkbenchGlyph.DELETE -> {
                drawRoundRect(color, Offset(w * .28f, h * .3f), Size(w * .44f, h * .5f), CornerRadius(w * .04f), style = outline)
                drawLine(color, Offset(w * .22f, h * .24f), Offset(w * .78f, h * .24f), stroke, StrokeCap.Round)
                drawLine(color, Offset(w * .4f, h * .17f), Offset(w * .6f, h * .17f), stroke, StrokeCap.Round)
                drawLine(color, Offset(w * .42f, h * .42f), Offset(w * .42f, h * .68f), stroke, StrokeCap.Round)
                drawLine(color, Offset(w * .58f, h * .42f), Offset(w * .58f, h * .68f), stroke, StrokeCap.Round)
            }
            WorkbenchGlyph.STOP -> drawRoundRect(
                color,
                Offset(w * .27f, h * .27f),
                Size(w * .46f, h * .46f),
                CornerRadius(w * .08f),
            )
            WorkbenchGlyph.ACTIVITY -> {
                val path = Path().apply {
                    moveTo(w * .12f, h * .55f)
                    lineTo(w * .31f, h * .55f)
                    lineTo(w * .41f, h * .3f)
                    lineTo(w * .57f, h * .72f)
                    lineTo(w * .68f, h * .45f)
                    lineTo(w * .88f, h * .45f)
                }
                drawPath(path, color, style = outline)
            }
            WorkbenchGlyph.CHEVRON_RIGHT -> {
                drawLine(color, Offset(w * .38f, h * .24f), Offset(w * .64f, h * .5f), stroke, StrokeCap.Round)
                drawLine(color, Offset(w * .64f, h * .5f), Offset(w * .38f, h * .76f), stroke, StrokeCap.Round)
            }
        }
    }
}
