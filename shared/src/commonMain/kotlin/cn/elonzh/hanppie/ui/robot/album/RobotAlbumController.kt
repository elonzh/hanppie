@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package cn.elonzh.hanppie.ui.robot.album

import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.robot.media.album.RobotAlbumService
import cn.elonzh.hanppie.robot.media.album.RobotMediaEntry
import cn.elonzh.hanppie.robot.media.album.RobotMediaPart
import cn.elonzh.hanppie.robot.session.RobotRequestException
import cn.elonzh.hanppie.robot.session.RequestProblem
import cn.elonzh.hanppie.ui.i18n.UiText
import cn.elonzh.hanppie.ui.i18n.uiText
import io.github.vinceglb.filekit.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import kotlin.concurrent.atomics.AtomicLong
import kotlin.time.Clock
import kotlin.uuid.Uuid

internal enum class MediaContent(val extension: String) { JPEG("jpg"), PNG("png"), VIDEO("mp4"), UNKNOWN("bin") }
internal data class LocalRobotMedia(val file: PlatformFile, val content: MediaContent, val size: Long, val sourceIndex: Long, val downloadedAt: Long,
    val captured: Boolean = false, val createdAt: Long? = null)
internal enum class AlbumOperation { REFRESH, DOWNLOAD }
internal data class RobotAlbumState(
    val connection: Long = 0,
    val connected: Boolean = false,
    val entries: List<RobotMediaEntry> = emptyList(),
    val listed: Boolean = false,
    val selected: RobotMediaEntry? = null,
    val thumbnails: Map<Long, MediaThumbnail> = emptyMap(),
    val downloads: Map<Long, LocalRobotMedia> = emptyMap(),
    val local: List<LocalRobotMedia> = emptyList(),
    val localThumbnails: Map<String, MediaThumbnail> = emptyMap(),
    val transferring: Long? = null,
    val operation: AlbumOperation? = null,
    val nativeBusy: Boolean = false,
    val received: Long = 0,
    val total: Long = 0,
    val error: UiText? = null,
    val localError: UiText? = null,
    val localLoading: Boolean = false,
) {
    val busy: Boolean get() = operation != null
}

/** Connection-owned remote index; completed files live independently in the app's private media directory. */
internal class RobotAlbumController(private val scope: CoroutineScope, private val directory: () -> PlatformFile,
    private val additionalMedia: () -> List<LocalRobotMedia> = ::additionalCapturedMedia) {
    private class Binding(val id: Long, val service: RobotAlbumService, val scope: CoroutineScope) {
        var job: Job? = null
        val transfer = Mutex()
        val previews = Channel<Unit>(Channel.CONFLATED)
        var visible: List<Long> = emptyList()
        var requested: Long? = null
    }
    val state = MutableStateFlow(RobotAlbumState())
    private val revision = AtomicLong(0)
    private var binding: Binding? = null
    private var localJob: Job? = null
    private val localPreviews = Channel<Unit>(Channel.CONFLATED)
    private var visibleLocal: List<String> = emptyList()
    private val localRevision = AtomicLong(0)
    private val localLoadRevision = AtomicLong(0)

    init { scope.launch { for (signal in localPreviews) readLocalPreviews() } }

    fun attach(service: RobotAlbumService) {
        clear()
        val current = Binding(revision.addAndFetch(1), service,
            CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job])))
        binding = current
        current.scope.launch { for (signal in current.previews) readRemotePreviews(current) }
        state.update { it.copy(connection = current.id, connected = true) }
        current.scope.launch { service.busy.collect { busy -> update(current) { it.copy(nativeBusy = busy) } } }
    }

    fun loadLocal() {
        localJob?.cancel()
        val load = localLoadRevision.addAndFetch(1)
        val version = localRevision.load()
        state.update { it.copy(localLoading = true, localError = null) }
        localJob = scope.launch {
        try {
            val files = withContext(Dispatchers.IO) {
                val root = directory().apply { createDirectories() }
                val stored = root.list().filter { it.isRegularFile() && localName.matches(it.name) }.mapNotNull { file ->
                    val fields = file.name.split('-')
                    val captured = fields[0] == "capture"
                    val index = if (captured) 0L else fields[2].toLongOrNull()?.takeIf { it in 1..0xffffffffL } ?: return@mapNotNull null
                    val savedAt = fields[1].toLongOrNull() ?: return@mapNotNull null
                    LocalRobotMedia(file, MediaContent.entries.first { it.extension == file.extension }, file.size(), index, savedAt, captured,
                        file.createdAt()?.toEpochMilliseconds())
                }
                (stored + additionalMedia()).distinctBy { it.file.path }.sortedByDescending { it.downloadedAt }

            }
            if (localRevision.load() == version) state.update { old -> old.copy(local = files,
                localThumbnails = old.localThumbnails.filterKeys { path -> files.any { it.file.path == path && it in old.local } }) }
            loadLocalPreviews()
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { if (localLoadRevision.load() == load) state.update { it.copy(localError = uiText(Res.string.album_local_failed)) } }
        finally { if (localLoadRevision.load() == load) state.update { it.copy(localLoading = false) } }
    } }

    fun captureDestination(content: MediaContent): PlatformFile {
        require(content != MediaContent.UNKNOWN)
        val root = directory().apply { createDirectories() }
        return PlatformFile(root, "capture-${Clock.System.now().toEpochMilliseconds()}-${Uuid.random()}.${content.extension}")
    }

    fun refresh() = work(AlbumOperation.REFRESH) { current ->
        val before = state.value.entries.associateBy { it.index }
        val entries = current.service.list()
        currentCoroutineContext().ensureActive()
        update(current) { it.copy(entries = entries, listed = true, selected = it.selected?.takeIf { selected -> selected in entries },
            thumbnails = it.thumbnails.filterKeys { key -> entries.any { entry -> entry == before[key] } },
            downloads = it.downloads.filterKeys { key -> entries.any { entry -> entry == before[key] } }) }
    }

    fun select(entry: RobotMediaEntry) {
        val current = binding ?: return
        update(current) { if (entry !in it.entries) it else it.copy(selected = entry) }
        val snapshot = state.value
        if (snapshot.connection == current.id && snapshot.selected == entry && (entry.isPhoto || entry.isVideo) && entry.index !in snapshot.thumbnails) thumbnail(entry)
    }

    fun showRemotePreviews(indices: List<Long>) {
        val current = binding ?: return
        current.visible = indices.distinct().take(32)
        queueRemotePreviews(current)
    }

    fun thumbnail(entry: RobotMediaEntry) {
        val current = binding ?: return
        if (entry !in state.value.entries || (!entry.isPhoto && !entry.isVideo)) return
        if (state.value.thumbnails[entry.index] == MediaThumbnail.Loading) return
        update(current) { it.copy(thumbnails = it.thumbnails + (entry.index to MediaThumbnail.Loading)) }
        current.requested = entry.index
        queueRemotePreviews(current)
    }

    /** One camera transfer at a time. Finish an active preview before foreground work; never abort the wire transfer. */
    private fun queueRemotePreviews(current: Binding) {
        val wanted = (current.visible + listOfNotNull(current.requested)).toSet()
        update(current) { old -> old.copy(thumbnails = old.thumbnails + old.entries.filter {
            it.index in wanted && (it.isPhoto || it.isVideo) && it.index !in old.thumbnails
        }.associate { it.index to MediaThumbnail.Loading }) }
        current.previews.trySend(Unit)
    }

    private suspend fun readRemotePreviews(current: Binding) {
        while (currentCoroutineContext().isActive) {
            state.first { it.connection != current.id || !it.busy }
            if (state.value.connection != current.id) return
            val wanted = listOfNotNull(current.requested) + current.visible
            val entry = wanted.firstNotNullOfOrNull { index -> state.value.entries.firstOrNull {
                it.index == index && (it.isPhoto || it.isVideo) && state.value.thumbnails[index] == MediaThumbnail.Loading
            } } ?: break
            current.transfer.withLock {
                if (state.value.busy) return@withLock
                update(current) { it.copy(thumbnails = it.thumbnails + (entry.index to MediaThumbnail.Loading)) }
                val result = try {
                    val bytes = Buffer()
                    current.service.download(entry, RobotMediaPart.THUMBNAIL, bytes)
                    withContext(Dispatchers.IO) { decodeMediaThumbnail(entry, bytes.readByteArray()) }
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    if (error is RobotRequestException && error.problem == RequestProblem.REJECTED) MediaThumbnail.Unavailable else MediaThumbnail.Failed
                }
                currentCoroutineContext().ensureActive()
                if (current.requested == entry.index) current.requested = null
                update(current) { it.copy(thumbnails = (it.thumbnails + (entry.index to result)).entries.toList().takeLast(64).associate { item -> item.toPair() }) }
            }
        }
    }


    fun showLocalPreviews(paths: List<String>) {
        visibleLocal = paths.distinct().take(32)
        loadLocalPreviews()
    }

    private fun loadLocalPreviews() {
        state.update { old -> old.copy(localThumbnails = old.localThumbnails + old.local.filter {
            it.file.path in visibleLocal && it.file.path !in old.localThumbnails
        }.associate { it.file.path to MediaThumbnail.Loading }) }
        localPreviews.trySend(Unit)
    }

    private suspend fun readLocalPreviews() {
        while (currentCoroutineContext().isActive) {
            val file = visibleLocal.firstNotNullOfOrNull { path -> state.value.local.firstOrNull {
                it.file.path == path && state.value.localThumbnails[path] == MediaThumbnail.Loading
            } } ?: break
            val path = file.file.path
            state.update { it.copy(localThumbnails = it.localThumbnails + (path to MediaThumbnail.Loading)) }
            val result = try {
                withContext(Dispatchers.IO) { localMediaThumbnail(file) }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { MediaThumbnail.Unavailable }
            state.update { if (file !in it.local) it else it.copy(localThumbnails =
                (it.localThumbnails + (path to result)).entries.toList().takeLast(64).associate { item -> item.toPair() }) }
        }
    }


    fun download(entry: RobotMediaEntry) = work(AlbumOperation.DOWNLOAD, entry.index) { current ->
        check(entry in state.value.entries)
        val root = withContext(Dispatchers.IO) { directory().apply { createDirectories() } }
        val downloadedAt = Clock.System.now().toEpochMilliseconds()
        val name = "sd-$downloadedAt-${entry.index}-${Uuid.random()}"
        val partial = PlatformFile(root, "$name.part")
        try {
            withContext(Dispatchers.IO) {
                partial.sink().buffered().use { sink ->
                    current.service.download(entry, RobotMediaPart.ORIGINAL, sink) { received, total ->
                        update(current) { it.copy(received = received, total = total) }
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            val file = withContext(Dispatchers.IO) {
                val prefix = partial.source().buffered().use { it.readByteArray(minOf(64, partial.size()).toInt()) }
                val content = mediaContent(prefix)
                val ready = PlatformFile(root, "$name.${content.extension}")
                partial.atomicMove(ready)
                LocalRobotMedia(ready, content, ready.size(), entry.index, downloadedAt, createdAt = ready.createdAt()?.toEpochMilliseconds())
            }
            localRevision.addAndFetch(1)
            update(current) { it.copy(downloads = it.downloads + (entry.index to file), local = listOf(file) + it.local) }
        } finally { withContext(NonCancellable + Dispatchers.IO) { partial.delete(mustExist = false) } }
    }

    fun cancel() { binding?.job?.cancel() }
    fun deselect() { state.update { it.copy(selected = null) } }
    fun dismissError() { state.update { it.copy(error = null, localError = null) } }

    fun removeLocal(file: LocalRobotMedia) {
        if (file !in state.value.local) return
        scope.launch {
            try {
                withContext(Dispatchers.IO) { file.file.delete() }
                localRevision.addAndFetch(1)
                state.update { old -> old.copy(local = old.local.filter { it.file.path != file.file.path },
                    downloads = old.downloads.filterValues { it.file.path != file.file.path },
                    localThumbnails = old.localThumbnails - file.file.path, localError = null) }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { state.update { it.copy(localError = uiText(Res.string.album_local_failed)) } }
        }
    }

    fun invalidateStorage() {
        binding?.service?.let(::attach)
    }

    private fun work(operation: AlbumOperation, transferring: Long? = null, action: suspend (Binding) -> Unit) {
        val current = binding ?: return
        if (current.job?.isActive == true || state.value.busy) return
        update(current) { it.copy(operation = operation, transferring = transferring, received = 0, total = 0, error = null) }
        current.job = current.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { current.transfer.withLock { action(current) } }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { update(current) { it.copy(error = uiText(Res.string.album_request_failed)) } }
            finally { update(current) { it.copy(operation = null, transferring = null) }; queueRemotePreviews(current) }
        }
    }

    private fun update(current: Binding, transform: (RobotAlbumState) -> RobotAlbumState) {
        state.update { if (it.connection == current.id) transform(it) else it }
    }

    fun clear() {
        val old = binding
        binding = null
        state.update { RobotAlbumState(connection = revision.addAndFetch(1), local = it.local, localThumbnails = it.localThumbnails, localError = it.localError, localLoading = it.localLoading) }
        old?.scope?.cancel()
    }
}

private val localName = Regex("^(sd-[0-9]+-[0-9]+|capture-[0-9]+)-[0-9a-f-]+\\.(jpg|png|mp4|bin)$")

internal fun mediaContent(bytes: ByteArray): MediaContent = when {
    bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> MediaContent.JPEG
    bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)) -> MediaContent.PNG
    bytes.size >= 12 && bytes.copyOfRange(4, 8).decodeToString() == "ftyp" &&
        bytes.copyOfRange(8, 12).decodeToString() in setOf("isom", "iso2", "mp41", "mp42", "avc1", "qt  ") -> MediaContent.VIDEO
    else -> MediaContent.UNKNOWN
}

/** Existing platform capture folders, alongside app-managed captures and downloads. */
internal expect fun additionalCapturedMedia(): List<LocalRobotMedia>
