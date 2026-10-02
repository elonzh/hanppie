package cn.elonzh.hanppie.agent.skills

import ai.koog.rag.base.files.FileMetadata
import ai.koog.rag.base.files.FileSystemProvider
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

internal object IosSkillFileSystem : FileSystemProvider.ReadOnly<Path> {
    override fun toAbsolutePathString(path: Path): String = SystemFileSystem.resolve(path).toString()
    override fun fromAbsolutePathString(path: String): Path = Path(path).also { require(it.isAbsolute) }
    override fun joinPath(base: Path, vararg parts: String): Path = parts.fold(base) { path, part ->
        require(!Path(part).isAbsolute); Path(path, part)
    }
    override fun name(path: Path): String = path.name
    override fun extension(path: Path): String = name(path).substringAfterLast('.', "")
    override fun parent(path: Path): Path? = path.parent
    override fun relativize(root: Path, path: Path): String? {
        val base = root.toString().trimEnd('/')
        val target = path.toString()
        return when { target == base -> ""; target.startsWith("$base/") -> target.removePrefix("$base/"); else -> null }
    }
    override suspend fun metadata(path: Path): FileMetadata? = SystemFileSystem.metadataOrNull(path)?.let {
        when { it.isRegularFile -> FileMetadata(FileMetadata.FileType.File, false)
            it.isDirectory -> FileMetadata(FileMetadata.FileType.Directory, false); else -> null }
    }
    override suspend fun list(directory: Path): List<Path> = SystemFileSystem.list(directory)
        .filter { SystemFileSystem.resolve(it) == it }.sortedBy(::name)
    override suspend fun exists(path: Path) = SystemFileSystem.exists(path)
    override suspend fun getFileContentType(path: Path): FileMetadata.FileContentType =
        if (extension(path) == "md") FileMetadata.FileContentType.Text else FileMetadata.FileContentType.Binary
    override suspend fun readBytes(path: Path): ByteArray = inputStream(path).use { it.readByteArray() }
    override suspend fun inputStream(path: Path): Source = SystemFileSystem.source(path).buffered()
    override suspend fun size(path: Path): Long = checkNotNull(SystemFileSystem.metadataOrNull(path)).size
}
