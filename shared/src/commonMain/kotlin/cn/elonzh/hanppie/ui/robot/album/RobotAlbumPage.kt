package cn.elonzh.hanppie.ui.robot.album

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.robot.media.album.RobotMediaEntry
import cn.elonzh.hanppie.ui.app.ConsoleController
import cn.elonzh.hanppie.ui.design.*
import cn.elonzh.hanppie.ui.i18n.DateTimeStyle
import cn.elonzh.hanppie.ui.i18n.formatLocalDateTime
import cn.elonzh.hanppie.ui.i18n.tr
import cn.elonzh.hanppie.ui.robot.files.formatRobotFileSize
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun RobotAlbumPage(model: ConsoleController, modifier: Modifier = Modifier, onBack: () -> Unit,
    onOpen: ((LocalRobotMedia) -> Unit)? = null, onExport: ((LocalRobotMedia) -> Unit)? = null) {
    val controller = model.robotAlbum
    val state by controller.state.collectAsState()
    var localTab by remember { mutableStateOf(!state.connected) }
    var selectedLocal by remember { mutableStateOf<LocalRobotMedia?>(null) }
    var remove by remember { mutableStateOf<LocalRobotMedia?>(null) }
    LaunchedEffect(state.connection) { if (state.connected) controller.refresh(); controller.loadLocal() }
    WorkbenchDialog(show = remove != null, onDismissRequest = { remove = null },
        title = tr(Res.string.album_remove_question), summary = tr(Res.string.album_remove_summary)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ remove = null }, Modifier.weight(1f).heightIn(min = 48.dp)) { Text(tr(Res.string.cancel)) }
            Button({ remove?.let(controller::removeLocal); remove = null; selectedLocal = null },
                Modifier.weight(1f).heightIn(min = 48.dp).testTag("album-remove-confirm"),
                colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.errorContainer,
                    contentColor = MiuixTheme.colorScheme.onErrorContainer)) { Text(tr(Res.string.delete)) }
        }
    }
    Column(modifier.fillMaxSize().testTag("robot-album-page"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WorkbenchIconButton(tr(Res.string.back), WorkbenchGlyph.BACK, onBack, tag = "album-back")
            Text(tr(Res.string.robot_album), Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            WorkbenchIconButton(tr(Res.string.refresh), WorkbenchGlyph.REFRESH,
                { if (localTab) controller.loadLocal() else controller.refresh() },
                enabled = if (localTab) !state.localLoading else state.connected && !state.busy, tag = "album-refresh")
        }
        TabRow(listOf(tr(Res.string.album_onboard), tr(Res.string.album_downloaded)), if (localTab) 1 else 0,
            { localTab = it == 1; if (localTab) controller.loadLocal() },
            modifier = Modifier.fillMaxWidth().testTag("album-tabs"), height = 48.dp)
        (if (localTab) state.localError else state.error)?.let { Text(it.resolve(), Modifier.testTag("album-error"),
            fontSize = 13.sp, color = MiuixTheme.colorScheme.error) }
        if (state.busy) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(when (state.operation) {
                    AlbumOperation.REFRESH -> tr(Res.string.album_reading)
                    AlbumOperation.THUMBNAIL -> tr(Res.string.album_thumbnail_loading)
                    else -> tr(Res.string.album_download_progress, formatRobotFileSize(state.received), formatRobotFileSize(state.total))
                }, Modifier.weight(1f).testTag("album-progress"), fontSize = 13.sp)
                Button(controller::cancel, Modifier.heightIn(min = 48.dp).testTag("album-cancel")) { Text(tr(Res.string.cancel)) }
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val wide = maxWidth >= 720.dp * LocalDensity.current.fontScale
            val previewHeight = (maxHeight * .5f).coerceIn(80.dp, 240.dp)
            val local = selectedLocal?.takeIf { it in state.local }
            val selected = if (localTab) local != null else state.selected != null
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (wide || !selected) {
                    LazyColumn((if (wide) Modifier.width(280.dp * LocalDensity.current.fontScale) else Modifier.weight(1f))
                        .fillMaxHeight().testTag("album-list"), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
                        if (localTab) {
                            if (state.local.isEmpty()) item { Text(tr(if (state.localLoading) Res.string.album_local_reading else Res.string.album_local_empty), fontSize = 14.sp) }
                            items(state.local, key = { it.file.path }) { file ->
                                AlbumRow(localTitle(file), formatLocalDateTime(file.downloadedAt, DateTimeStyle.SHORT),
                                    null, file.content == MediaContent.VIDEO, local == file, true, "album-local-${file.file.hashCode()}") { selectedLocal = file }
                            }
                        } else {
                            if (!state.connected) item { Text(tr(Res.string.album_connect_first), fontSize = 14.sp) }
                            else if (state.entries.isEmpty() && !state.busy) item { Text(tr(Res.string.album_onboard_empty), fontSize = 14.sp) }
                            items(state.entries, key = { it.index }) { entry ->
                                AlbumRow(remoteTitle(entry), formatRobotFileSize(entry.size),
                                    (state.thumbnails[entry.index] as? MediaThumbnail.Ready)?.image, false,
                                    state.selected == entry, !state.busy, "album-entry-${entry.index}") { controller.select(entry) }
                            }
                        }
                    }
                }
                if (wide || selected) Column(Modifier.weight(1f).fillMaxHeight()
                    .testTag("album-detail"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!wide) Button({ if (localTab) selectedLocal = null else controller.deselect() },
                        Modifier.heightIn(min = 48.dp).testTag("album-list-back")) { Text(tr(Res.string.album_back_to_list)) }
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (localTab && local != null) {
                        LocalMediaDetail(local, previewHeight) { remove = local }
                    } else if (!localTab && state.selected != null) {
                        val entry = state.selected!!
                        Text(remoteTitle(entry), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        when (val thumbnail = state.thumbnails[entry.index]) {
                            is MediaThumbnail.Ready -> Image(thumbnail.image, remoteTitle(entry), Modifier.fillMaxWidth().height(previewHeight)
                                .testTag("album-thumbnail"), contentScale = ContentScale.Fit)
                            MediaThumbnail.Failed -> Text(tr(Res.string.album_thumbnail_failed), Modifier.testTag("album-thumbnail-error"), fontSize = 14.sp)
                            null -> Text(tr(Res.string.album_thumbnail_loading), fontSize = 14.sp)
                        }
                        Text(formatRobotFileSize(entry.size), fontSize = 14.sp)
                        if (state.thumbnails[entry.index] is MediaThumbnail.Failed) Button({ controller.thumbnail(entry) },
                            Modifier.heightIn(min = 48.dp).testTag("album-thumbnail-retry"), enabled = !state.busy) { Text(tr(Res.string.album_retry)) }
                        if (state.downloads[entry.index] != null) {
                            Text(tr(Res.string.album_saved_locally), Modifier.testTag("album-downloaded"), fontSize = 14.sp)
                        }
                    } else Text(tr(Res.string.album_select_media), fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                    if (localTab && local != null) MediaFileActions(local, onOpen, onExport)
                    else if (!localTab) state.selected?.let { entry ->
                        val downloaded = state.downloads[entry.index]
                        if (downloaded == null) Button({ controller.download(entry) }, Modifier.heightIn(min = 48.dp).testTag("album-download"),
                            enabled = state.connected && !state.busy) { Text(tr(Res.string.album_download_original)) }
                        else MediaFileActions(downloaded, onOpen, onExport)
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumRow(title: String, subtitle: String, thumbnail: ImageBitmap?, video: Boolean,
    selected: Boolean, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().testTag(tag).semantics { this.selected = selected }.clickable(enabled = enabled, onClick = onClick),
        colors = CardDefaults.defaultColors(color = if (selected) MiuixTheme.colorScheme.secondaryContainer else MiuixTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.padding(12.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (thumbnail != null) Image(thumbnail, title, Modifier.size(56.dp), contentScale = ContentScale.Crop)
            else WorkbenchIcon(if (video) WorkbenchGlyph.VIDEO else WorkbenchGlyph.FILE, MiuixTheme.colorScheme.onSurfaceVariantSummary, Modifier.size(32.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontSize = 15.sp)
                Text(subtitle, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
    }
}

@Composable
private fun LocalMediaDetail(file: LocalRobotMedia, previewHeight: Dp, onRemove: () -> Unit) {
    val photo = file.content in listOf(MediaContent.JPEG, MediaContent.PNG)
    val preview by produceState<Result<ImageBitmap>?>(null, file) {
        if (photo) value = withContext(Dispatchers.IO) {
            try {
                check(file.size <= 32L * 1024 * 1024)
                Result.success(file.file.readBytes().decodeToImageBitmap())
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { Result.failure(error) }
        }
    }
    Text(localTitle(file), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    preview?.getOrNull()?.let { Image(it, localTitle(file), Modifier.fillMaxWidth().height(previewHeight).testTag("album-local-preview"), contentScale = ContentScale.Fit) }
    if (photo && preview?.isSuccess != true) Text(tr(if (preview == null) Res.string.album_preview_loading else Res.string.album_preview_failed), fontSize = 14.sp)
    Text(tr(Res.string.album_downloaded_at, formatLocalDateTime(file.downloadedAt, DateTimeStyle.SHORT)), fontSize = 14.sp)
    Text(formatRobotFileSize(file.size), fontSize = 14.sp)
    Button(onRemove, Modifier.heightIn(min = 48.dp).testTag("album-remove")) { Text(tr(Res.string.album_remove_local)) }
}

@Composable
private fun MediaFileActions(file: LocalRobotMedia, onOpen: ((LocalRobotMedia) -> Unit)?, onExport: ((LocalRobotMedia) -> Unit)?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Button({ onOpen?.invoke(file) }, Modifier.heightIn(min = 48.dp).testTag("album-open"), enabled = onOpen != null) {
        Text(tr(if (file.content == MediaContent.VIDEO) Res.string.album_play else Res.string.album_open))
    }
    Button({ onExport?.invoke(file) }, Modifier.heightIn(min = 48.dp).testTag("album-export"), enabled = onExport != null) { Text(tr(Res.string.album_export)) }
    }
}

@Composable private fun remoteTitle(entry: RobotMediaEntry) = tr(if (entry.isPhoto) Res.string.album_photo_name else Res.string.album_media_name, entry.index)
@Composable private fun localTitle(file: LocalRobotMedia) = tr(when (file.content) {
    MediaContent.JPEG, MediaContent.PNG -> Res.string.album_photo_name
    MediaContent.VIDEO -> Res.string.album_video_name
    MediaContent.UNKNOWN -> Res.string.album_media_name
}, file.sourceIndex)
