@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package cn.elonzh.hanppie.ui.robot.album

import cn.elonzh.hanppie.ui.platform.toByteArray
import platform.AVFoundation.AVAssetImageGenerator
import platform.AVFoundation.AVURLAsset
import platform.CoreGraphics.CGImageRelease
import platform.CoreMedia.CMTimeMake
import platform.Foundation.NSURL
import platform.UIKit.UIImage
import platform.UIKit.UIImagePNGRepresentation

internal actual suspend fun localVideoThumbnail(path: String): ByteArray {
    val asset = AVURLAsset.URLAssetWithURL(NSURL.fileURLWithPath(path), null)
    val generator = AVAssetImageGenerator(asset).apply { appliesPreferredTrackTransform = true }
    val image = checkNotNull(generator.copyCGImageAtTime(CMTimeMake(0, 1), null, null))
    try { return checkNotNull(UIImagePNGRepresentation(UIImage.imageWithCGImage(image))).toByteArray() }
    finally { CGImageRelease(image) }
}
