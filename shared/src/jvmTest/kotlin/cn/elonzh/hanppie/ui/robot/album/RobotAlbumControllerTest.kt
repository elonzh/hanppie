package cn.elonzh.hanppie.ui.robot.album

import cn.elonzh.hanppie.robot.media.album.*
import cn.elonzh.hanppie.robot.protocol.DussFrame
import cn.elonzh.hanppie.ui.app.testConsoleModel
import io.github.vinceglb.filekit.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.io.Sink
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import kotlin.test.*

internal class AlbumFixture : RobotAlbumService {
    val photo: ByteArray = ByteArrayOutputStream().use { out ->
        val image = BufferedImage(160, 90, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply {
            color = Color(28, 36, 42); fillRect(0, 0, 160, 90)
            color = Color(224, 120, 58); fillRect(35, 20, 90, 55)
            color = Color(242, 245, 247); fillRect(60, 35, 40, 25); dispose()
        }
        ImageIO.write(image, "jpg", out); out.toByteArray()
    }
    override val busy = MutableStateFlow(false)
    val entries = listOf(RobotMediaEntry(1, photo.size.toLong(), 0), RobotMediaEntry(2, photo.size.toLong(), 3))
    @Volatile var listCalls = 0
    @Volatile var listError = false
    @Volatile var thumbnailError = false
    @Volatile var originalError = false
    var listGate: CompletableDeferred<Unit>? = null
    var fileGate: CompletableDeferred<Unit>? = null
    val downloads = CopyOnWriteArrayList<RobotMediaPart>()
    override suspend fun list(): List<RobotMediaEntry> {
        busy.value = true; listCalls++
        try {
            withContext(NonCancellable) { listGate?.await() }
            if (listError) error("list failed")
            return entries
        } finally { busy.value = false }
    }
    override suspend fun download(entry: RobotMediaEntry, part: RobotMediaPart, destination: Sink, onProgress: (Long, Long) -> Unit) {
        busy.value = true; downloads += part
        try {
            if (part == RobotMediaPart.THUMBNAIL) {
                if (thumbnailError) error("thumbnail failed")
                destination.write(photo)
            } else {
                destination.write(photo, 0, 10); onProgress(10, photo.size.toLong())
                withContext(NonCancellable) { fileGate?.await() }
                if (originalError) error("original failed")
                destination.write(photo, 10, photo.size); onProgress(photo.size.toLong(), photo.size.toLong())
            }
        } finally { busy.value = false }
    }
}

class RobotAlbumControllerTest {
    @Test fun completedDownloadsPersistIndependentlyAndAreNeverReusedByIndexAcrossConnections(): Unit = runBlocking {
        val root = Files.createTempDirectory("hanppie-album").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val service = AlbumFixture()
            val controller = RobotAlbumController(scope) { PlatformFile(root) }
            controller.attach(service); controller.refresh(); controller.settled()
            controller.download(service.entries[0]); controller.settled()
            val saved = controller.state.value.downloads.getValue(1)
            assertContentEquals(service.photo, saved.file.readBytes())
            assertEquals(MediaContent.JPEG, saved.content); assertEquals(1, saved.sourceIndex)
            assertTrue(saved.file.name.endsWith(".jpg")); assertTrue(root.listFiles()!!.none { it.name.endsWith(".part") })
            controller.clear()
            assertEquals(listOf(saved), controller.state.value.local)
            assertTrue(controller.state.value.downloads.isEmpty())
            controller.attach(service); controller.refresh(); controller.settled()
            assertTrue(controller.state.value.downloads.isEmpty())
            val other = RobotAlbumController(scope) { PlatformFile(root) }
            other.loadLocal()
            withTimeout(3_000) { other.state.first { !it.localLoading } }
            assertEquals(1, other.state.value.local.size)
            assertEquals(1, other.state.value.local.single().sourceIndex)
        } finally { scope.cancel(); scope.coroutineContext[Job]?.join(); root.deleteRecursively() }
    }

    @Test fun failedAndCancelledDownloadsNeverPublishPartialFiles(): Unit = runBlocking {
        val root = Files.createTempDirectory("hanppie-album").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gate = CompletableDeferred<Unit>()
        try {
            val service = AlbumFixture().apply { originalError = true }
            val controller = RobotAlbumController(scope) { PlatformFile(root) }
            controller.attach(service); controller.refresh(); controller.settled()
            controller.download(service.entries[0]); controller.settled()
            assertNotNull(controller.state.value.error)
            assertTrue(root.listFiles().orEmpty().isEmpty()); assertTrue(controller.state.value.local.isEmpty())
            service.originalError = false; service.fileGate = gate
            controller.download(service.entries[0])
            withTimeout(3_000) { controller.state.first { it.received == 10L } }
            controller.cancel(); gate.complete(Unit); controller.settled()
            assertTrue(root.listFiles().orEmpty().isEmpty()); assertTrue(controller.state.value.downloads.isEmpty())
        } finally { gate.complete(Unit); scope.cancel(); scope.coroutineContext[Job]?.join(); root.deleteRecursively() }
    }

    @Test fun storageInvalidationAndRebindingRejectLateListsWhileKeepingLocalFiles(): Unit = runBlocking {
        val root = Files.createTempDirectory("hanppie-album").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gate = CompletableDeferred<Unit>()
        try {
            val service = AlbumFixture().apply { listGate = gate }
            val controller = RobotAlbumController(scope) { PlatformFile(root) }
            controller.attach(service); controller.refresh()
            withTimeout(3_000) { service.busy.first { it } }
            val oldBindings = scope.coroutineContext[Job]!!.children.toList()
            val version = controller.state.value.connection
            controller.invalidateStorage()
            gate.complete(Unit); service.listGate = null
            oldBindings.forEach { it.join() }
            assertTrue(controller.state.value.connection > version)
            assertTrue(controller.state.value.entries.isEmpty())
            controller.refresh(); controller.settled()
            assertEquals(service.entries, controller.state.value.entries)
            controller.clear()
            assertFalse(controller.state.value.connected); assertTrue(controller.state.value.entries.isEmpty())
        } finally { gate.complete(Unit); scope.cancel(); scope.coroutineContext[Job]?.join(); root.deleteRecursively() }
    }

    @Test fun thumbnailFailureIsIndependentAndExplicitRetryCanLoadARealImage(): Unit = runBlocking {
        val root = Files.createTempDirectory("hanppie-album").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val service = AlbumFixture().apply { thumbnailError = true }
            val controller = RobotAlbumController(scope) { PlatformFile(root) }
            controller.attach(service); controller.refresh(); controller.settled()
            controller.select(service.entries[0]); controller.settled()
            assertEquals(MediaThumbnail.Failed, controller.state.value.thumbnails[1])
            assertNull(controller.state.value.error)
            service.thumbnailError = false
            controller.thumbnail(service.entries[0]); controller.settled()
            assertIs<MediaThumbnail.Ready>(controller.state.value.thumbnails[1])
            controller.download(service.entries[0]); controller.settled()
            assertEquals(1, controller.state.value.local.size)
        } finally { scope.cancel(); scope.coroutineContext[Job]?.join(); root.deleteRecursively() }
    }

    @Test fun localRemovalNeverCallsTheRobotAndIgnoresIncompleteFiles(): Unit = runBlocking {
        val root = Files.createTempDirectory("hanppie-album").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val service = AlbumFixture()
            val controller = RobotAlbumController(scope) { PlatformFile(root) }
            controller.attach(service); controller.refresh(); controller.settled()
            controller.download(service.entries[0]); controller.settled()
            val saved = controller.state.value.local.single()
            root.resolve("sd-incomplete.part").writeText("partial")
            root.resolve("unrelated.jpg").writeBytes(service.photo)
            root.resolve("sd-999999999999999999999999999-1-abcd.jpg").writeBytes(service.photo)
            root.resolve("sd-100-0-abcd.jpg").writeBytes(service.photo)
            controller.clear(); controller.loadLocal()
            withTimeout(3_000) { controller.state.first { !it.localLoading } }
            assertEquals(1, controller.state.value.local.size)
            controller.removeLocal(saved)
            withTimeout(3_000) { controller.state.first { it.local.isEmpty() } }
            assertFalse(saved.file.exists()); assertTrue(root.resolve("unrelated.jpg").exists())
            assertEquals(listOf(RobotMediaPart.ORIGINAL), service.downloads.toList())
        } finally { scope.cancel(); scope.coroutineContext[Job]?.join(); root.deleteRecursively() }
    }

    @Test fun fileTypeUsesDownloadedBytesAndDiagnosticsRetainNoMediaContent() {
        assertEquals(MediaContent.PNG, mediaContent(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 13, 10, 26, 10)))
        // A container-prefix classifier vector, not a playable video fixture.
        assertEquals(MediaContent.VIDEO, mediaContent(byteArrayOf(0, 0, 0, 16) + "ftypisom".encodeToByteArray()))
        assertEquals(MediaContent.UNKNOWN, mediaContent(byteArrayOf(0, 0, 0, 16) + "ftypavif".encodeToByteArray()))
        assertEquals(MediaContent.UNKNOWN, mediaContent("not media".encodeToByteArray()))
        val model = testConsoleModel()
        try {
            val secret = "private-media-content".encodeToByteArray()
            model.receive(DussFrame(0, 1, 2, 1, 0, 0, 0x27, ByteArray(10) + secret, true))
            val line = model.state.value.frames.single()
            assertTrue(line.contains("media=${10 + secret.size} B"))
            assertFalse(line.contains(secret.joinToString("") { "%02x".format(it) }))
        } finally { model.close() }
    }
}

internal suspend fun RobotAlbumController.settled() {
    withTimeout(5_000) { state.first { !it.busy } }
}
