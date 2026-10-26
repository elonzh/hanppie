package cn.elonzh.hanppie.robot.media.album

import kotlinx.coroutines.flow.StateFlow
import kotlinx.io.Sink
import cn.elonzh.hanppie.robot.media.CameraDate

/** Calendar time has no timezone. Additional record bytes are not established filenames. */
data class RobotMediaEntry(val index: Long, val size: Long, val formatCode: Int, val createdAt: CameraDate? = null) {
    init {
        require(index in 1..0xffffffffL && size in 0..0xffffffffL && formatCode in 0..255)
    }
    val isPhoto: Boolean get() = formatCode == 0
    val isVideo: Boolean get() = formatCode == 3
}

enum class RobotMediaPart(internal val wireValue: Int) { ORIGINAL(0), THUMBNAIL(1) }

/** Read-only native camera album. A completed transfer validates byte count, not the media codec. */
interface RobotAlbumService {
    val busy: StateFlow<Boolean>
    suspend fun list(): List<RobotMediaEntry>
    suspend fun download(entry: RobotMediaEntry, part: RobotMediaPart, destination: Sink,
        onProgress: (received: Long, total: Long) -> Unit = { _, _ -> })
}
