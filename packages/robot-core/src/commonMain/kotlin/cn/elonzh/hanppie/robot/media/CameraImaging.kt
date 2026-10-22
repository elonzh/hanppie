package cn.elonzh.hanppie.robot.media

enum class CameraPhotoSize(val code: Int, val width: Int, val height: Int) {
    R1080P(0, 1920, 1080), R720P(1, 1280, 720), R1440P(2, 2560, 1440);
    val label: String get() = "$width × $height"
}

/** Presets used by the native client's transmission-rate controls, in kilobits per second. */
enum class VideoTransmissionRate(val kbps: Int, val label: String) {
    BASIC(2400, "2.4 Mbps"), GOOD(3400, "3.4 Mbps"), BEST(6000, "6 Mbps")
}

/** Raw format codes remain visible as unknown when not defined by the native client. */
data class CameraImagingStatus(val videoFormat: Int, val photoSize: Int, val sources: Int, val cameraIndex: Int) {
    val resolution: VideoResolution? get() = when (videoFormat) { 0 -> VideoResolution.R720P; 1 -> VideoResolution.R1080P; else -> null }
    val photo: CameraPhotoSize? get() = CameraPhotoSize.entries.firstOrNull { it.code == photoSize }
    val availableCameras: List<Int> get() = buildList { if (sources != 2) add(0); if (sources != 1) add(1) }
}
