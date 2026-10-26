package cn.elonzh.hanppie.ui.robot.album

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
import io.github.vinceglb.filekit.name
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun RobotAlbumPage(model: ConsoleController, modifier: Modifier = Modifier, onBack: () -> Unit,
    onOpen: ((LocalRobotMedia) -> Unit)? = null, onExport: ((LocalRobotMedia) -> Unit)? = null, showBack: Boolean = true) {
    val controller = model.robotAlbum
    val state by controller.state.collectAsState()
    val localListState = rememberLazyListState()
    val remoteListState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var localTab by remember { mutableStateOf(true) }
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
    LaunchedEffect(localTab, state.entries, state.local, state.selected) {
        snapshotFlow {
            if (localTab) localListState.layoutInfo.visibleItemsInfo.mapNotNull { (it.key as? String) }
            else remoteListState.layoutInfo.visibleItemsInfo.mapNotNull { (it.key as? Long) }
        }.distinctUntilChanged().collect { keys ->
            if (localTab) {
                controller.showRemotePreviews(emptyList())
                controller.showLocalPreviews(keys.filterIsInstance<String>())
            } else {
                controller.showLocalPreviews(emptyList())
                controller.showRemotePreviews(listOfNotNull(state.selected?.index) + keys.filterIsInstance<Long>())
            }
        }
    }
    DisposableEffect(controller) { onDispose { controller.showRemotePreviews(emptyList()); controller.showLocalPreviews(emptyList()) } }
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().testTag("robot-album-page"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (showBack) WorkbenchIconButton(tr(Res.string.back), WorkbenchGlyph.BACK, onBack, tag = "album-back")
                Text(tr(Res.string.robot_album), Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                val refreshing = if (localTab) state.localLoading else state.operation == AlbumOperation.REFRESH
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    if (refreshing) CircularProgressIndicator(Modifier.size(24.dp).testTag("album-refresh-loading"))
                    else WorkbenchIconButton(tr(Res.string.refresh), WorkbenchGlyph.REFRESH,
                        { if (localTab) controller.loadLocal() else controller.refresh() },
                        enabled = localTab || state.connected && !state.busy && !state.nativeBusy, tag = "album-refresh")
                }
            }
            TabRow(listOf(tr(Res.string.album_onboard), tr(Res.string.album_downloaded)), if (localTab) 1 else 0,
                { localTab = it == 1; if (localTab) controller.loadLocal() },
                modifier = Modifier.fillMaxWidth().testTag("album-tabs"), height = 48.dp)
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val wide = maxWidth >= 720.dp * LocalDensity.current.fontScale
                val local = selectedLocal?.takeIf { it in state.local }
                val selected = if (localTab) local != null else state.selected != null
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (wide || !selected) {
                        LazyColumn((if (wide) Modifier.width(280.dp * LocalDensity.current.fontScale) else Modifier.weight(1f))
                            .fillMaxHeight().testTag("album-list"), state = if (localTab) localListState else remoteListState, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
                            if (localTab) {
                                if (state.local.isEmpty()) item {
                                    if (state.localLoading) Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
                                    else Text(tr(Res.string.album_local_empty), fontSize = 14.sp)
                                }
                                items(state.local, key = { it.file.path }) { file ->
                                    AlbumRow(localTitle(file), creationTime(file),
                                        state.localThumbnails[file.file.path], file.content == MediaContent.VIDEO, local == file, true, "album-local-${file.file.hashCode()}") { selectedLocal = file }
                                }
                            } else {
                                if (!state.connected) item { Text(tr(Res.string.album_connect_first), fontSize = 14.sp) }
                                else if (state.entries.isEmpty() && state.operation == AlbumOperation.REFRESH) item {
                                    Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
                                }
                                else if (state.entries.isEmpty() && !state.busy) item {
                                    if (state.listed) Text(tr(Res.string.album_onboard_empty), fontSize = 14.sp)
                                    else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(tr(Res.string.album_request_failed), fontSize = 14.sp)
                                        Button(controller::refresh, Modifier.heightIn(min = 48.dp)) { Text(tr(Res.string.album_retry)) }
                                    }
                                }
                                items(state.entries, key = { it.index }) { entry ->
                                    AlbumRow(remoteTitle(entry), creationTime(entry),
                                        state.thumbnails[entry.index], entry.isVideo,
                                        state.selected == entry, true, "album-entry-${entry.index}") { controller.select(entry) }
                                }
                            }
                        }
                    }
                    if (wide || selected) Column(Modifier.weight(1f).fillMaxHeight()
                        .testTag("album-detail"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (!wide) Button({ if (localTab) selectedLocal = null else controller.deselect() },
                            Modifier.heightIn(min = 48.dp).testTag("album-list-back")) { Text(tr(Res.string.album_back_to_list)) }
                        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                            val showMetadata = maxHeight >= 200.dp * LocalDensity.current.fontScale
                            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (localTab && local != null) {
                                    if (showMetadata) {
                                        Text(localTitle(local), fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(creationTime(local), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(formatRobotFileSize(local.size), fontSize = 13.sp)
                                    }
                                    if (local.content == MediaContent.VIDEO) LocalMediaPlayback(local, Modifier.weight(1f).fillMaxWidth())
                                    else LocalPhotoPreview(local, Modifier.weight(1f).fillMaxWidth())
                                } else if (!localTab && state.selected != null) {
                                    val entry = state.selected!!
                                    Text(remoteTitle(entry), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Box(Modifier.fillMaxWidth().weight(1f).testTag("album-preview-area"), contentAlignment = Alignment.Center) {
                                        val downloaded = state.downloads[entry.index]
                                        if (downloaded?.content in listOf(MediaContent.JPEG, MediaContent.PNG)) LocalPhotoPreview(downloaded!!, Modifier.fillMaxSize())
                                        else if (!entry.isPhoto && !entry.isVideo || state.thumbnails[entry.index] == MediaThumbnail.Unavailable) Column(Modifier.testTag("album-preview-download"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            WorkbenchIcon(if (entry.isVideo) WorkbenchGlyph.VIDEO else WorkbenchGlyph.FILE, MiuixTheme.colorScheme.onSurfaceVariantSummary, Modifier.size(40.dp))
                                            Text(tr(if (entry.isVideo) Res.string.album_video_preview else Res.string.album_file_preview), fontSize = 14.sp)
                                        } else when (val thumbnail = state.thumbnails[entry.index]) {
                                            is MediaThumbnail.Ready -> Image(thumbnail.image, remoteTitle(entry), Modifier.fillMaxSize()
                                                .testTag("album-thumbnail"), contentScale = ContentScale.Fit)
                                            MediaThumbnail.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text(tr(Res.string.album_thumbnail_failed), Modifier.testTag("album-thumbnail-error"), fontSize = 14.sp)
                                                Button({ controller.thumbnail(entry) }, Modifier.heightIn(min = 48.dp).testTag("album-thumbnail-retry"), enabled = !state.busy) { Text(tr(Res.string.album_retry)) }
                                            }
                                            MediaThumbnail.Unavailable -> Unit
                                            MediaThumbnail.Loading, null -> CircularProgressIndicator(Modifier.size(32.dp).testTag("album-preview-loading"))
                                        }
                                    }
                                    Text(if (state.downloads[entry.index] != null) tr(Res.string.album_saved_locally) else formatRobotFileSize(entry.size),
                                        Modifier.testTag(if (state.downloads[entry.index] != null) "album-downloaded" else "album-file-size"), fontSize = 13.sp, minLines = 1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                } else Text(tr(Res.string.album_select_media), fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            }
                        }
                        if (localTab && local != null) MediaFileActions(local, onOpen, onExport) { remove = local }
                        else if (!localTab) state.selected?.let { entry ->
                            val downloaded = state.downloads[entry.index]
                            if (downloaded == null) Button({ controller.download(entry) }, Modifier.heightIn(min = 48.dp).testTag("album-download"),
                                enabled = state.connected && !state.busy) { Text(tr(Res.string.album_download_original)) }
                            else {
                                if (downloaded.content == MediaContent.VIDEO) Button({
                                    localTab = true; selectedLocal = downloaded
                                    val index = state.local.indexOf(downloaded)
                                    if (index >= 0) scope.launch { localListState.scrollToItem(index) }
                                }, Modifier.heightIn(min = 48.dp).testTag("album-play-local")) { Text(tr(Res.string.album_play)) }
                                MediaFileActions(downloaded, onOpen, onExport)
                            }
                        }
                    }
                }
            }
            if (state.operation == AlbumOperation.DOWNLOAD) Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("album-transfer"),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Column(Modifier.weight(1f)) {
                    state.entries.firstOrNull { it.index == state.transferring }?.let { Text(remoteTitle(it), fontSize = 13.sp, maxLines = 1) }
                    Text(tr(Res.string.album_download_progress, formatRobotFileSize(state.received), formatRobotFileSize(state.total)), fontSize = 12.sp)
                }
                WorkbenchIconButton(tr(Res.string.cancel), WorkbenchGlyph.STOP, controller::cancel, tag = "album-cancel")
            }
        }
        WorkbenchToast((if (localTab) state.localError else state.error)?.resolve(), controller::dismissError)
    }
}

@Composable
private fun AlbumRow(title: String, subtitle: String, thumbnail: MediaThumbnail?, video: Boolean,
    selected: Boolean, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().testTag(tag).semantics { this.selected = selected }
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        colors = CardDefaults.defaultColors(color = if (selected) MiuixTheme.colorScheme.secondaryContainer else MiuixTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.padding(12.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                if (thumbnail is MediaThumbnail.Ready) Image(thumbnail.image, null, Modifier.fillMaxSize().testTag("$tag-thumbnail"), contentScale = ContentScale.Crop)
                else if (thumbnail == MediaThumbnail.Loading) CircularProgressIndicator(Modifier.size(24.dp).testTag("$tag-loading"))
                else WorkbenchIcon(if (video) WorkbenchGlyph.VIDEO else WorkbenchGlyph.FILE, MiuixTheme.colorScheme.onSurfaceVariantSummary, Modifier.size(32.dp))
                if (video && thumbnail is MediaThumbnail.Ready) Box(Modifier.align(Alignment.BottomEnd).padding(2.dp)
                    .size(20.dp).background(Color.Black.copy(alpha = .65f), CircleShape).testTag("$tag-video"), contentAlignment = Alignment.Center) {
                    WorkbenchIcon(WorkbenchGlyph.PLAY, Color.White, Modifier.size(12.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontSize = 15.sp, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(subtitle, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, minLines = 1, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun LocalPhotoPreview(file: LocalRobotMedia, modifier: Modifier) = key(file) {
    val photo = file.content in listOf(MediaContent.JPEG, MediaContent.PNG)
    val preview by produceState<Result<ImageBitmap>?>(null) {
        if (photo) value = withContext(Dispatchers.IO) {
            try {
                check(file.size <= 32L * 1024 * 1024)
                Result.success(file.file.readBytes().decodeToImageBitmap())
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { Result.failure(error) }
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        preview?.getOrNull()?.let { Image(it, localTitle(file), Modifier.fillMaxSize().testTag("album-local-preview"), contentScale = ContentScale.Fit) }
        if (photo && preview == null) CircularProgressIndicator(Modifier.size(32.dp))
        else if (photo && preview?.isFailure == true) Text(tr(Res.string.album_preview_failed), fontSize = 14.sp)
        else if (!photo) WorkbenchIcon(WorkbenchGlyph.FILE, MiuixTheme.colorScheme.onSurfaceVariantSummary, Modifier.size(40.dp))
    }
}

@Composable
private fun MediaFileActions(file: LocalRobotMedia, onOpen: ((LocalRobotMedia) -> Unit)?, onExport: ((LocalRobotMedia) -> Unit)?, onRemove: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    if (file.content != MediaContent.VIDEO) Button({ onOpen?.invoke(file) }, Modifier.heightIn(min = 48.dp).testTag("album-open"), enabled = onOpen != null) {
        Text(tr(if (file.content == MediaContent.VIDEO) Res.string.album_play else Res.string.album_open))
    }
    Button({ onExport?.invoke(file) }, Modifier.heightIn(min = 48.dp).testTag("album-export"), enabled = onExport != null) { Text(tr(Res.string.album_export)) }
    if (onRemove != null) Button(onRemove, Modifier.heightIn(min = 48.dp).testTag("album-remove")) { Text(tr(Res.string.album_remove_local)) }
    }
}

@Composable private fun remoteTitle(entry: RobotMediaEntry) = tr(when {
    entry.isPhoto -> Res.string.album_photo_name
    entry.isVideo -> Res.string.album_video_name
    else -> Res.string.album_media_name
}, entry.index)
private fun localTitle(file: LocalRobotMedia) = file.file.name

@Composable
private fun creationTime(file: LocalRobotMedia): String = file.createdAt?.let {
    tr(Res.string.media_created_at, formatLocalDateTime(it, DateTimeStyle.SHORT))
} ?: tr(Res.string.media_creation_unknown)

@Composable
private fun creationTime(entry: RobotMediaEntry): String = entry.createdAt?.let { date ->
    fun Int.two() = toString().padStart(2, '0')
    tr(Res.string.media_created_at, "${date.year}-${date.month.two()}-${date.day.two()} ${date.hour.two()}:${date.minute.two()}:${date.second.two()}")
} ?: tr(Res.string.media_creation_unknown)
