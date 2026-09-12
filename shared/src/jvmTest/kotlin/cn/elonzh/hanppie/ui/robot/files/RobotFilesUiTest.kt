package cn.elonzh.hanppie.ui.robot.files

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import cn.elonzh.hanppie.robot.files.RobotFileEntry
import cn.elonzh.hanppie.robot.files.RobotFileKind
import cn.elonzh.hanppie.ui.app.Console
import cn.elonzh.hanppie.ui.app.testConsoleModel
import cn.elonzh.hanppie.ui.design.WorkbenchGlyph
import cn.elonzh.hanppie.ui.design.WorkbenchTheme
import cn.elonzh.hanppie.ui.design.icon
import cn.elonzh.hanppie.ui.i18n.Localization
import cn.elonzh.hanppie.ui.scripts.EditorDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class RobotFilesUiTest {
    private val entries = listOf(
        RobotFileEntry("/audio", "audio", RobotFileKind.DIRECTORY),
        RobotFileEntry("/audio/tone.opus", "tone.opus", RobotFileKind.FILE, 1536, 1_788_860_400_000),
        RobotFileEntry("/logic.py", "logic.py", RobotFileKind.FILE, 420),
        RobotFileEntry("/notes.txt", "notes.txt", RobotFileKind.FILE, 20),
        RobotFileEntry("/python/python_raw.dsp", "python_raw.dsp", RobotFileKind.FILE, 2048),
    )

    @Test fun filtersKeepFoldersReachableAndFormatSizes() {
        assertEquals("1.5 KB", formatRobotFileSize(1536))
        assertEquals("20 B", formatRobotFileSize(20))
        assertEquals(listOf("audio", "tone.opus"),
            filteredRobotFiles(entries, "", RobotFileFilter.AUDIO).map { it.name })
        assertEquals(listOf("python_raw.dsp"),
            filteredRobotFiles(entries, "python", RobotFileFilter.PROGRAM).map { it.name })
    }

    @Test fun allCommonStaticIconsUseTheLucideWorkbenchMapping() {
        val expected = mapOf(
            WorkbenchGlyph.BACK to "arrow-left",
            WorkbenchGlyph.ADD to "plus",
            WorkbenchGlyph.IMPORT to "file-input",
            WorkbenchGlyph.EXPORT to "file-output",
            WorkbenchGlyph.UPLOAD to "upload",
            WorkbenchGlyph.DOWNLOAD to "download",
            WorkbenchGlyph.SAVE to "save",
            WorkbenchGlyph.EDIT to "pencil",
            WorkbenchGlyph.DELETE to "trash-2",
            WorkbenchGlyph.STOP to "square",
            WorkbenchGlyph.ACTIVITY to "activity",
            WorkbenchGlyph.CHEVRON_RIGHT to "chevron-right",
            WorkbenchGlyph.FILE to "file",
            WorkbenchGlyph.FILE_TEXT to "file-text",
            WorkbenchGlyph.FOLDER to "folder",
            WorkbenchGlyph.REFRESH to "refresh-cw",
            WorkbenchGlyph.MORE to "ellipsis",
            WorkbenchGlyph.OPEN to "external-link",
            WorkbenchGlyph.VIDEO to "video",
            WorkbenchGlyph.VIDEO_OFF to "video-off",
            WorkbenchGlyph.SPEAKER to "volume-2",
            WorkbenchGlyph.MUTED to "volume-x",
            WorkbenchGlyph.CAMERA to "camera",
            WorkbenchGlyph.RECORD to "circle",
            WorkbenchGlyph.CROSSHAIR to "crosshair",
            WorkbenchGlyph.SEARCH to "search",
            WorkbenchGlyph.CONNECT to "plug",
            WorkbenchGlyph.BATTERY to "battery-medium",
            WorkbenchGlyph.SIGNAL to "chart-no-axes-column-increasing",
            WorkbenchGlyph.PACKETS to "arrow-left-right",
            WorkbenchGlyph.MICROPHONE to "mic",
            WorkbenchGlyph.SEND to "send-horizontal",
            WorkbenchGlyph.ROBOT to "bot",
            WorkbenchGlyph.CODE to "code-xml",
            WorkbenchGlyph.CHAT to "message-square",
            WorkbenchGlyph.SETTINGS to "sliders-horizontal",
        )

        assertEquals(WorkbenchGlyph.entries.toSet(), expected.keys)
        expected.forEach { (glyph, name) -> assertEquals(name, glyph.icon.name, glyph.name) }
    }

    @Test fun phoneFileManagerSupportsFilteringSelectionAndProtectedSlot() =
        runDesktopComposeUiTest(width = 393, height = 740) {
            Localization.initialize("zh", null)
            val model = testConsoleModel()
            var downloaded: RobotFileEntry? = null
            var opened: RobotFileEntry? = null
            try {
                model.robotFiles.state.value = RobotFilesState(path = "/", entries = entries)
                setContent { WorkbenchTheme { RobotFilesPage(model, compact = true,
                    onUpload = {}, onDownload = { downloaded = it }, onOpen = { opened = it }) } }
                onNodeWithTag("robot-files-page").assertIsDisplayed()
                onNodeWithText("音频").performClick()
                onNodeWithText("tone.opus").assertIsDisplayed().performClick()
                onNodeWithText("logic.py").assertDoesNotExist()
                onNodeWithTag("robot-file-open").assertIsDisplayed().performClick()
                runOnIdle { assertEquals("tone.opus", opened?.name) }
                onNodeWithTag("robot-file-download").assertIsDisplayed().performClick()
                runOnIdle { assertEquals("tone.opus", downloaded?.name) }
                onNodeWithText("程序").performClick()
                onNodeWithText("python_raw.dsp").assertIsDisplayed().performClick()
                onNodeWithTag("robot-file-rename").assertIsNotEnabled()
                onNodeWithTag("robot-file-delete").assertIsNotEnabled()
                onNodeWithText("Lab 保留槽位").assertIsDisplayed()
                saveRobotFilesSnapshot("robot-files-phone", onRoot(), 393, 740)
            } finally { model.close() }
        }

    @Test fun desktopFileManagerKeepsActionsAndScrollbarAvailable() =
        runDesktopComposeUiTest(width = 1040, height = 700) {
            Localization.initialize("en", null)
            val model = testConsoleModel()
            try {
                model.robotFiles.state.value = RobotFilesState(path = "/audio", entries = entries +
                    (1..80).map { RobotFileEntry("/audio/clip-$it.opus", "clip-$it.opus", RobotFileKind.FILE, it * 1024L) })
                setContent { WorkbenchTheme { RobotFilesPage(model, compact = false,
                    onUpload = {}, onDownload = {}, onOpen = {}) } }
                onNodeWithTag("robot-files-scrollbar").assertIsDisplayed()
                onNodeWithText("tone.opus").performClick()
                onNodeWithTag("robot-file-actions").assertIsDisplayed()
                onNodeWithTag("robot-file-open").assertIsEnabled()
                onNodeWithTag("robot-file-download").assertIsEnabled()
                onNodeWithTag("robot-file-rename").assertIsEnabled()
                onNodeWithTag("robot-file-delete").assertIsEnabled()
                val upload = onNodeWithTag("robot-files-upload").fetchSemanticsNode().boundsInRoot
                assertTrue(upload.width >= 48f && upload.height >= 48f)
                saveRobotFilesSnapshot("robot-files-desktop", onRoot(), 1040, 700)
            } finally { model.close(); Localization.initialize("zh", null) }
        }

    @Test fun debugTabContainsTheConnectedRobotFileManager() =
        runDesktopComposeUiTest(width = 393, height = 740) {
            Localization.initialize("zh", null)
            val model = testConsoleModel()
            try {
                model.state.value = model.state.value.copy(connected = true, connectedAddress = "192.0.2.1")
                model.robotFiles.state.value = RobotFilesState(entries = entries)
                setContent { WorkbenchTheme { Console(model, mutableStateOf(EditorDocument()),
                    onRobotFileUpload = {}, onRobotFileDownload = {}, onRobotFileOpen = {}) } }
                onNodeWithContentDescription("诊断").performClick()
                onNodeWithTag("debug-tabs").assertIsDisplayed()
                onNodeWithText("FTP").performClick()
                runOnIdle { model.robotFiles.state.value = RobotFilesState(entries = entries) }
                onNodeWithTag("robot-files-page").assertIsDisplayed()
                onNodeWithTag("bottom-navigation").assertIsDisplayed()
                onNodeWithTag("robot-file-/logic.py").assertIsDisplayed()
                saveRobotFilesSnapshot("debug-files-phone", onRoot(), 393, 740)
            } finally { model.close() }
        }
}

private fun saveRobotFilesSnapshot(name: String, node: SemanticsNodeInteraction, width: Int, height: Int) {
    val image = node.captureToImage()
    assertEquals(width, image.width)
    assertEquals(height, image.height)
    val pixels = IntArray(width * height)
    image.readPixels(pixels)
    val buffered = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
    buffered.setRGB(0, 0, width, height, pixels, 0, width)
    val output = java.io.File("build/reports/ui/$name.png")
    output.parentFile.mkdirs()
    javax.imageio.ImageIO.write(buffered, "png", output)
}
