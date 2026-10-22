package cn.elonzh.hanppie.ui.robot.camera

import cn.elonzh.hanppie.resources.*
import cn.elonzh.hanppie.robot.media.CameraStorageStatus
import org.jetbrains.compose.resources.StringResource

internal fun cameraStorageStatusText(connected: Boolean, storage: CameraStorageStatus?): StringResource = when {
    !connected -> Res.string.camera_storage_connect_first
    storage == null -> Res.string.camera_storage_waiting
    !storage.inserted -> Res.string.camera_storage_absent
    storage.formatting -> Res.string.camera_storage_formatting
    storage.recording -> Res.string.camera_storage_recording
    storage.shooting -> Res.string.camera_storage_shooting
    storage.hasError -> Res.string.camera_storage_error
    storage.full -> Res.string.camera_storage_full
    storage.stateCode == 0 -> Res.string.camera_storage_ready
    else -> Res.string.camera_storage_unknown
}
