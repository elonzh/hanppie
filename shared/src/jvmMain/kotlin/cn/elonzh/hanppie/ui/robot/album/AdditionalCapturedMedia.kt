package cn.elonzh.hanppie.ui.robot.album

import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.createdAt
import java.io.File

internal actual fun additionalCapturedMedia(): List<LocalRobotMedia> = listOf("Pictures", "Movies").flatMap { folder ->
    File(System.getProperty("user.home"), "$folder/Hanppie").listFiles().orEmpty().filter {
        it.isFile && it.name.matches(Regex("Hanppie-[0-9-]+\\.(jpg|mp4)"))
    }.map { file ->
        LocalRobotMedia(PlatformFile(file), if (file.extension == "mp4") MediaContent.VIDEO else MediaContent.JPEG,
            file.length(), 0, file.lastModified(), captured = true, createdAt = PlatformFile(file).createdAt()?.toEpochMilliseconds())
    }
}
