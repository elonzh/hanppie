package cn.elonzh.hanppie.ui.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.design.WorkbenchGlyph
import cn.elonzh.hanppie.ui.design.WorkbenchIcon
import cn.elonzh.hanppie.ui.design.WorkbenchIconButton
import cn.elonzh.hanppie.ui.i18n.tr
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** The app navigation entry owns topic/step; reading and replay never mark device work complete. */
@Composable
internal fun UsageGuidePage(
    topic: GuideTopic?, stepIndex: Int, modifier: Modifier = Modifier,
    onBack: () -> Unit, onSelect: (GuideTopic?) -> Unit,
    onStep: (Int) -> Unit, onOpen: (GuideDestination) -> Unit,
) {
    Column(modifier.fillMaxSize().testTag("usage-guide-page"), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                WorkbenchIconButton(tr(if (topic == null) Res.string.back else Res.string.guide_index), WorkbenchGlyph.BACK,
                    { if (topic == null) onBack() else onSelect(null) }, tag = "usage-guide-back")
                Text(tr(topic?.title ?: Res.string.guide_title), Modifier.weight(1f), fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                if (topic != null && stepIndex > 0) WorkbenchIconButton(tr(Res.string.guide_restart), WorkbenchGlyph.REFRESH,
                    { onStep(0) }, tag = "usage-guide-restart")
            }
            key(topic, stepIndex) {
                val scroll = rememberScrollState()
                val scope = rememberCoroutineScope()
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val fullBodyHeight = with(LocalDensity.current) { maxHeight.roundToPx() }
                    // Measuring against the full viewport prevents the hint from creating its own overflow.
                    val overflowsBody = scroll.maxValue.toLong() + scroll.viewportSize > fullBodyHeight
                    Column(Modifier.fillMaxSize()) {
                        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).testTag("usage-guide-body"),
                            verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            if (topic == null) {
                                Text(tr(Res.string.guide_intro), fontSize = 16.sp, lineHeight = 24.sp)
                                Button({ onSelect(GuideTopic.FIRST_CONNECTION) }, Modifier.fillMaxWidth().heightIn(min = 52.dp)
                                    .testTag("usage-guide-topic-FIRST_CONNECTION"), colors = ButtonDefaults.buttonColorsPrimary()) {
                                    Text(tr(Res.string.guide_start))
                                }
                                GuideTopic.entries.filter { it != GuideTopic.FIRST_CONNECTION }.forEach { item ->
                                    Column {
                                        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("usage-guide-topic-${item.name}")
                                            .clickable(role = Role.Button) { onSelect(item) }.padding(vertical = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text(tr(item.title), fontSize = 18.sp, fontWeight = FontWeight.Medium)
                                                Text(tr(item.summary), fontSize = 14.sp, lineHeight = 20.sp,
                                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                            }
                                            WorkbenchIcon(WorkbenchGlyph.CHEVRON_RIGHT, MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                        }
                                        Box(Modifier.fillMaxWidth().height(1.dp).background(MiuixTheme.colorScheme.outline.copy(alpha = .25f)))
                                    }
                                }
                            } else {
                                val step = topic.steps[stepIndex]
                                Text(tr(Res.string.guide_progress, stepIndex + 1, topic.steps.size), Modifier.testTag("usage-guide-progress"),
                                    fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                Text(tr(step.title), Modifier.testTag("usage-guide-step-title"), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                                Text(tr(step.body), Modifier.testTag("usage-guide-step-text"), fontSize = 16.sp, lineHeight = 26.sp)
                                step.destination?.let { target ->
                                    Button({ onOpen(target) }, Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("usage-guide-open-${target.name}")) {
                                        Text(tr(target.label))
                                    }
                                }
                            }
                        }
                        if (topic != null && overflowsBody && scroll.canScrollForward) {
                            Spacer(Modifier.height(8.dp))
                            Button({ scope.launch { scroll.animateScrollTo(scroll.maxValue) } },
                                Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("usage-guide-more")) {
                                Text(tr(Res.string.guide_continue_reading))
                                Spacer(Modifier.width(8.dp))
                                WorkbenchIcon(WorkbenchGlyph.ARROW_DOWN, MiuixTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            }
            if (topic != null) Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button({ onStep(stepIndex - 1) }, Modifier.weight(1f).heightIn(min = 48.dp).testTag("usage-guide-previous"), enabled = stepIndex > 0) {
                    Text(tr(Res.string.guide_previous))
                }
                Button({ if (stepIndex + 1 < topic.steps.size) onStep(stepIndex + 1) else onSelect(null) },
                    Modifier.weight(1f).heightIn(min = 48.dp).testTag("usage-guide-next"), colors = ButtonDefaults.buttonColorsPrimary()) {
                    Text(tr(if (stepIndex + 1 < topic.steps.size) Res.string.guide_next else Res.string.guide_index))
                }
            }
        }
    }
}
