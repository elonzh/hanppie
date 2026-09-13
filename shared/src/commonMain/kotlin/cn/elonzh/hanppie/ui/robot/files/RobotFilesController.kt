package cn.elonzh.hanppie.ui.robot.files

import cn.elonzh.hanppie.resources.Res
import cn.elonzh.hanppie.resources.creating_folder_value
import cn.elonzh.hanppie.resources.deleting_file_value
import cn.elonzh.hanppie.resources.downloading_file_value
import cn.elonzh.hanppie.resources.file_count_value
import cn.elonzh.hanppie.resources.file_deleted_value
import cn.elonzh.hanppie.resources.file_downloaded_value
import cn.elonzh.hanppie.resources.file_ready_to_open_value
import cn.elonzh.hanppie.resources.file_renamed_value
import cn.elonzh.hanppie.resources.file_uploaded_as_value
import cn.elonzh.hanppie.resources.file_uploaded_value
import cn.elonzh.hanppie.resources.folder_created_value
import cn.elonzh.hanppie.resources.only_regular_files_can_be_downloaded
import cn.elonzh.hanppie.resources.opening_file_value
import cn.elonzh.hanppie.resources.refreshing_robot_files
import cn.elonzh.hanppie.resources.renaming_file_value
import cn.elonzh.hanppie.resources.robot_file_service_unavailable
import cn.elonzh.hanppie.resources.robot_is_not_connected
import cn.elonzh.hanppie.resources.uploading_file_value
import cn.elonzh.hanppie.robot.files.RobotFileEntry
import cn.elonzh.hanppie.robot.files.RobotFilePath
import cn.elonzh.hanppie.robot.files.RobotFileService
import cn.elonzh.hanppie.ui.i18n.UiText
import cn.elonzh.hanppie.ui.i18n.tr
import cn.elonzh.hanppie.ui.i18n.uiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.io.Sink
import kotlinx.io.Source

internal class RobotFilesController(
    private val scope: CoroutineScope,
    private val available: () -> Boolean,
) : AutoCloseable {
    val state = MutableStateFlow(RobotFilesState())

    private var files: RobotFileService? = null
    private var job: Job? = null

    fun attach(files: RobotFileService) {
        clear()
        this.files = files
    }

    fun refresh(path: String = state.value.path) = work(uiText(Res.string.refreshing_robot_files)) { service ->
        val normalized = RobotFilePath.normalize(path)
        val entries = service.list(normalized)
        state.value.copy(
            path = normalized,
            entries = entries,
            selectedPath = null,
            message = uiText(Res.string.file_count_value, entries.size),
        )
    }

    fun select(entry: RobotFileEntry?) {
        if (!state.value.busy) state.update { it.copy(selectedPath = entry?.path) }
    }

    fun openDirectory(entry: RobotFileEntry) {
        if (entry.isDirectory) refresh(entry.path)
    }

    fun openParentDirectory() {
        val path = state.value.path
        if (path != "/") refresh(path.substringBeforeLast('/', "").ifEmpty { "/" })
    }

    fun upload(directory: String, name: String, source: () -> Source) =
        work(uiText(Res.string.uploading_file_value, name)) { service ->
            val uploaded = source().use { service.upload(directory, name, it) }
            val entries = service.list(directory)
            state.value.copy(
                path = directory,
                entries = entries,
                selectedPath = uploaded.path,
                message = if (uploaded.name == name) {
                    uiText(Res.string.file_uploaded_value, uploaded.name)
                } else {
                    uiText(Res.string.file_uploaded_as_value, uploaded.name)
                },
            )
        }

    fun download(entry: RobotFileEntry, destination: () -> Sink) =
        work(uiText(Res.string.downloading_file_value, entry.name)) { service ->
            check(entry.isRegularFile) { tr(Res.string.only_regular_files_can_be_downloaded) }
            destination().use { service.download(entry.path, it) }
            state.value.copy(
                selectedPath = entry.path,
                message = uiText(Res.string.file_downloaded_value, entry.name),
            )
        }

    fun open(entry: RobotFileEntry, destination: () -> Sink, onDownloaded: () -> Unit) =
        work(uiText(Res.string.opening_file_value, entry.name)) { service ->
            check(entry.isRegularFile) { tr(Res.string.only_regular_files_can_be_downloaded) }
            destination().use { service.download(entry.path, it) }
            onDownloaded()
            state.value.copy(
                selectedPath = entry.path,
                message = uiText(Res.string.file_ready_to_open_value, entry.name),
            )
        }

    fun createDirectory(name: String) = work(uiText(Res.string.creating_folder_value, name)) { service ->
        val current = state.value.path
        val created = service.createDirectory(current, name)
        state.value.copy(
            entries = service.list(current),
            selectedPath = created.path,
            message = uiText(Res.string.folder_created_value, created.name),
        )
    }

    fun rename(entry: RobotFileEntry, newName: String) =
        work(uiText(Res.string.renaming_file_value, entry.name)) { service ->
            val current = state.value.path
            val renamed = service.rename(entry.path, newName)
            state.value.copy(
                entries = service.list(current),
                selectedPath = renamed,
                message = uiText(Res.string.file_renamed_value, newName),
            )
        }

    fun delete(entry: RobotFileEntry) = work(uiText(Res.string.deleting_file_value, entry.name)) { service ->
        val current = state.value.path
        service.delete(entry)
        state.value.copy(
            entries = service.list(current),
            selectedPath = null,
            message = uiText(Res.string.file_deleted_value, entry.name),
        )
    }

    private fun work(operation: UiText, action: suspend (RobotFileService) -> RobotFilesState) {
        if (!available()) {
            state.update { it.copy(error = tr(Res.string.robot_is_not_connected)) }
            return
        }
        val service = files ?: run {
            state.update { it.copy(error = tr(Res.string.robot_file_service_unavailable)) }
            return
        }
        if (job?.isActive == true) return
        state.update { it.copy(busy = true, operation = operation, error = null, message = null) }
        job = scope.launch {
            try {
                val next = action(service)
                if (files === service && available()) {
                    state.value = next.copy(busy = false, operation = null, error = null)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (files === service) {
                    state.update {
                        it.copy(
                            busy = false,
                            operation = null,
                            error = error.message ?: error::class.simpleName ?: "Unknown error",
                        )
                    }
                }
            }
        }
    }

    fun clear() {
        val current = files
        files = null
        job?.cancel()
        job = null
        current?.close()
        state.value = RobotFilesState()
    }

    override fun close() = clear()
}
