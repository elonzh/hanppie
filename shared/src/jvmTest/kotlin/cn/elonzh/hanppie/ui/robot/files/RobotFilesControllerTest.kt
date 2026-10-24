package cn.elonzh.hanppie.ui.robot.files

import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.robot.files.*
import cn.elonzh.hanppie.ui.i18n.*
import kotlinx.coroutines.*
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlin.test.*

class RobotFilesControllerTest {
    @Test fun admissionFailuresKeepTheirResourceWhenLanguageChanges() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var available = false
        var blocked: UiText? = null
        val controller = RobotFilesController(scope, { blocked }, { available })
        try {
            controller.refresh()
            assertEquals(uiText(Res.string.robot_is_not_connected), controller.state.value.error)
            available = true
            controller.refresh()
            assertEquals(uiText(Res.string.robot_file_service_unavailable), controller.state.value.error)
            val service = FileFeedbackFixture()
            controller.attach(service)
            blocked = uiText(Res.string.firmware_finish_first)
            controller.refresh()
            val feedback = assertNotNull(controller.state.value.error)
            for (language in AppLanguage.entries) {
                Localization.initialize(systemLanguage = language.languageTag, saved = language.preference)
                assertEquals(tr(Res.string.firmware_finish_first), feedback.resolve())
                assertSame(feedback, controller.state.value.error)
            }
            assertEquals(0, service.lists)
            assertFalse(controller.state.value.busy)
        } finally { controller.close(); scope.cancel(); Localization.initialize("zh", "zh") }
    }

    @Test fun nonRegularDownloadsAndOpensDoNotCreateHostFiles() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = RobotFilesController(scope, available = { true })
        val service = FileFeedbackFixture()
        var destinations = 0
        var opens = 0
        try {
            controller.attach(service)
            controller.download(RobotFileEntry("/audio", "audio", RobotFileKind.DIRECTORY)) {
                destinations++; error("No destination may be created")
            }
            assertEquals(uiText(Res.string.only_regular_files_can_be_downloaded), controller.state.value.error)
            controller.open(RobotFileEntry("/link", "link", RobotFileKind.LINK), {
                destinations++; error("No destination may be created")
            }, { opens++ })
            val feedback = assertNotNull(controller.state.value.error)
            for (language in AppLanguage.entries) {
                Localization.initialize(systemLanguage = language.languageTag, saved = language.preference)
                assertEquals(tr(Res.string.only_regular_files_can_be_downloaded), feedback.resolve())
            }
            assertEquals(0, destinations); assertEquals(0, opens); assertEquals(0, service.downloads)
            assertFalse(controller.state.value.busy)
        } finally { controller.close(); scope.cancel(); Localization.initialize("zh", "zh") }
    }

    @Test fun serviceFailureRetainsDataAndRawDetailsUntilExplicitSuccess() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = RobotFilesController(scope, available = { true })
        val entry = RobotFileEntry("/logic.py", "logic.py", RobotFileKind.FILE, 512)
        val service = FileFeedbackFixture(listOf(entry))
        val detail = "Synthetic FTP 550 EACCES /logic.py 原始诊断"
        try {
            controller.attach(service)
            controller.state.value = RobotFilesState(entries = listOf(entry), selectedPath = entry.path)
            service.onList = { error(detail) }
            controller.refresh()
            val failed = controller.state.value
            val feedback = assertNotNull(failed.error)
            assertEquals(listOf(entry), failed.entries); assertEquals(entry, failed.selected)
            assertNull(failed.operation); assertNull(failed.message); assertFalse(failed.busy)
            for (language in AppLanguage.entries) {
                Localization.initialize(systemLanguage = language.languageTag, saved = language.preference)
                assertEquals(tr(Res.string.robot_file_operation_failed_value, detail), feedback.resolve())
                assertContains(feedback.resolve(), detail)
                assertSame(failed, controller.state.value)
            }
            assertEquals(1, service.lists)
            service.onList = { listOf(entry) }
            controller.refresh()
            assertNull(controller.state.value.error)
            assertEquals(uiText(Res.string.file_count_value, 1), controller.state.value.message)
            assertEquals(listOf(entry), controller.state.value.entries)
            assertEquals(2, service.lists)
        } finally { controller.close(); scope.cancel(); Localization.initialize("zh", "zh") }
    }

    @Test fun replacingTheServiceIgnoresItsLateFailure() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = RobotFilesController(scope, available = { true })
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val old = FileFeedbackFixture().apply { onList = {
            withContext(NonCancellable) { entered.complete(Unit); release.await(); error("late old FTP failure") }
        } }
        val entry = RobotFileEntry("/fresh.py", "fresh.py", RobotFileKind.FILE, 20)
        val fresh = FileFeedbackFixture(listOf(entry))
        try {
            controller.attach(old); controller.refresh(); entered.await()
            controller.attach(fresh); controller.refresh()
            val current = controller.state.value
            release.complete(Unit); yield()
            assertSame(current, controller.state.value)
            assertEquals(listOf(entry), current.entries); assertNull(current.error)
            assertEquals(1, old.closes); assertEquals(1, fresh.lists)
            controller.clear()
            assertEquals(RobotFilesState(), controller.state.value); assertEquals(1, fresh.closes)
        } finally { release.complete(Unit); controller.close(); scope.cancel() }
    }
}

private class FileFeedbackFixture(entries: List<RobotFileEntry> = emptyList()) : RobotFileService {
    var lists = 0
    var downloads = 0
    var closes = 0
    var onList: suspend () -> List<RobotFileEntry> = { entries }
    override suspend fun list(path: String): List<RobotFileEntry> { lists++; return onList() }
    override suspend fun upload(directory: String, preferredName: String, source: Source): RobotFileEntry = error("unused")
    override suspend fun download(path: String, destination: Sink) { downloads++ }
    override suspend fun createDirectory(parent: String, name: String): RobotFileEntry = error("unused")
    override suspend fun rename(path: String, newName: String): String = error("unused")
    override suspend fun delete(entry: RobotFileEntry): Unit = error("unused")
    override fun close() { closes++ }
}
