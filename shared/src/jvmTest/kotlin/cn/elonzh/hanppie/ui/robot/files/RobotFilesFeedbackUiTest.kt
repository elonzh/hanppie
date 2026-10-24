package cn.elonzh.hanppie.ui.robot.files

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import cn.elonzh.hanppie.robot.files.*
import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.ui.app.testConsoleModel
import cn.elonzh.hanppie.ui.app.Console
import cn.elonzh.hanppie.ui.scripts.EditorDocument
import cn.elonzh.hanppie.ui.design.TestWorkbenchTheme
import cn.elonzh.hanppie.ui.i18n.Localization
import cn.elonzh.hanppie.ui.i18n.AppLanguage
import cn.elonzh.hanppie.ui.i18n.tr
import cn.elonzh.hanppie.ui.settings.*
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class RobotFilesFeedbackUiTest {
    @Test fun compact() = scenario(844, 390, 1f, false)
    @Test fun tabletDark() = scenario(1024, 768, 1f, true)
    @Test fun desktop() = scenario(1280, 720, 1f, false)
    @Test fun large() = scenario(844, 390, 1.4f, true)

    private fun scenario(width: Int, height: Int, scale: Float, dark: Boolean) =
        runDesktopComposeUiTest(width = width, height = height) {
            Localization.initialize("en", "en")
            val model = testConsoleModel()
            val entry = RobotFileEntry("/logic.py", "logic.py", RobotFileKind.FILE, 512)
            val detail = "Synthetic FTP 550 EACCES /logic.py " + "raw diagnostic ".repeat(150)
            val service = object : RobotFileService {
                val fail = AtomicBoolean(true)
                val lists = AtomicInteger()
                override suspend fun list(path: String): List<RobotFileEntry> {
                    lists.incrementAndGet()
                    if (fail.get()) error(detail)
                    return listOf(entry)
                }
                override suspend fun upload(directory: String, preferredName: String, source: Source): RobotFileEntry = error("unused")
                override suspend fun download(path: String, destination: Sink): Unit = error("unused")
                override suspend fun createDirectory(parent: String, name: String): RobotFileEntry = error("unused")
                override suspend fun rename(path: String, newName: String): String = error("unused")
                override suspend fun delete(entry: RobotFileEntry): Unit = error("unused")
                override fun close() = Unit
            }
            fun picture(name: String) {
                val image = onRoot().captureToImage()
                val pixels = IntArray(image.width * image.height); image.readPixels(pixels)
                val bitmap = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
                bitmap.setRGB(0, 0, image.width, image.height, pixels, 0, image.width)
                val output = File("build/reports/ui/file-feedback-$width-$height-$scale-$name.png")
                output.parentFile.mkdirs(); ImageIO.write(bitmap, "png", output)
            }
            try {
                model.state.value = model.state.value.copy(connected = true)
                model.robotFiles.attach(service)
                model.robotFiles.state.value = RobotFilesState(entries = listOf(entry), selectedPath = entry.path)
                setContent { TestWorkbenchTheme(AppearanceController(AppearanceSettings(if (dark) NightMode.DARK else NightMode.LIGHT))) {
                    CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                        Console(model, mutableStateOf(EditorDocument()), onRobotFileUpload = {}, onRobotFileDownload = {}, onRobotFileOpen = {})
                    }
                } }
                onNodeWithTag("connection-status").performClick()
                onNodeWithTag("connection-diagnostics").performClick()
                onNodeWithText(tr(Res.string.ftp_files_tab)).performClick()
                waitUntil(timeoutMillis = 5_000) { !model.robotFiles.state.value.busy && model.robotFiles.state.value.error != null }
                val feedback = model.robotFiles.state.value.error
                onNodeWithTag("robot-files-error").assertIsDisplayed().assertTextEquals(tr(Res.string.robot_file_operation_failed_value, detail))
                assertTrue(onNodeWithTag("robot-files-error").getUnclippedBoundsInRoot().height <= 80.dp)
                picture("error")
                if (scale > 1f) for (language in AppLanguage.entries) {
                    runOnIdle { Localization.select(language.preference) }
                    onNodeWithTag("robot-files-error").assertIsDisplayed().assertTextEquals(tr(Res.string.robot_file_operation_failed_value, detail))
                    assertSame(feedback, model.robotFiles.state.value.error)
                    assertEquals(1, service.lists.get())
                    picture("error-${language.preference}")
                }
                onNodeWithTag("robot-files-list").performScrollToNode(hasTestTag("robot-file-/logic.py"))
                onNodeWithTag("robot-file-/logic.py").assertIsDisplayed()
                onNodeWithTag("robot-file-download").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
                onNodeWithTag("robot-files-refresh").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
                picture("actions")
                service.fail.set(false)
                onNodeWithTag("robot-files-refresh").performClick()
                waitUntil(timeoutMillis = 5_000) { service.lists.get() == 2 && !model.robotFiles.state.value.busy }
                onNodeWithTag("robot-files-error").assertDoesNotExist()
                onNodeWithTag("robot-files-list").performScrollToNode(hasTestTag("robot-file-/logic.py"))
                onNodeWithTag("robot-file-/logic.py").assertIsDisplayed()
                assertEquals(listOf(entry), model.robotFiles.state.value.entries)
                assertTrue(model.state.value.connected)
                picture("recovered")
            } finally { model.close(); Localization.initialize("zh", "zh") }
        }
}
