package cn.elonzh.hanppie.agent.skills

import ai.koog.rag.base.files.FileMetadata
import ai.koog.rag.base.files.FileSystemProvider
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.files.SystemFileSystem

/** Koog's Android artifact omits its JVM provider; both targets can use NIO from API 26. */
internal object SkillFileSystem : FileSystemProvider.ReadOnly<Path> {
    override fun toAbsolutePathString(path: Path): String = path.toAbsolutePath().normalize().toString()
    override fun fromAbsolutePathString(path: String): Path = java.nio.file.Paths.get(path).also {
        require(it.isAbsolute) { "Expected an absolute path" }
    }
    override fun joinPath(base: Path, vararg parts: String): Path = parts.fold(base) { path, part ->
        require(!java.nio.file.Paths.get(part).isAbsolute) { "Expected a relative path" }
        path.resolve(part)
    }
    override fun name(path: Path): String = path.fileName?.toString().orEmpty()
    override fun extension(path: Path): String = name(path).substringAfterLast('.', "")
    override fun parent(path: Path): Path? = path.parent
    override fun relativize(root: Path, path: Path): String? =
        if (root.root == path.root) root.relativize(path).toString().replace('\\', '/') else null

    override suspend fun metadata(path: Path): FileMetadata? = when {
        Files.isRegularFile(path) -> FileMetadata(FileMetadata.FileType.File, false)
        Files.isDirectory(path) -> FileMetadata(FileMetadata.FileType.Directory, false)
        else -> null
    }
    override suspend fun list(directory: Path): List<Path> = Files.newDirectoryStream(directory).use { stream ->
        // Skill trees never traverse symlinks, including during Koog discovery.
        stream.filterNot { Files.isSymbolicLink(it) }.sortedBy(::name)
    }
    override suspend fun exists(path: Path): Boolean = Files.exists(path)
    override suspend fun getFileContentType(path: Path): FileMetadata.FileContentType =
        if (extension(path) == "md") FileMetadata.FileContentType.Text else FileMetadata.FileContentType.Binary
    override suspend fun readBytes(path: Path): ByteArray = Files.readAllBytes(path)
    override suspend fun inputStream(path: Path): Source =
        SystemFileSystem.source(kotlinx.io.files.Path(path.toString())).buffered()
    override suspend fun size(path: Path): Long = Files.size(path)
}
