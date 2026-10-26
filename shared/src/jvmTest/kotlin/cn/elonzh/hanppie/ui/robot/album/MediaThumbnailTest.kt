package cn.elonzh.hanppie.ui.robot.album

import cn.elonzh.hanppie.robot.media.album.RobotMediaEntry
import kotlin.test.*

class MediaThumbnailTest {
    @Test fun nv16KeepsRowsAndHorizontalChromaPairsAndConvertsLimitedRangeColors() {
        val bytes = ByteArray(160 * 120 * 2)
        for (y in 0 until 120) for (x in 0 until 160) {
            bytes[y * 160 + x] = (if (y < 60) { if (x < 80) 16 else 235 } else 81).toByte()
            bytes[19200 + y * 160 + x] = (if (y < 60) 128 else if (x % 2 == 0) 90 else 240).toByte()
        }
        val preview = assertIs<MediaThumbnail.Ready>(decodeMediaThumbnail(RobotMediaEntry(1, 100, 3), bytes)).image
        assertEquals(160, preview.width); assertEquals(120, preview.height)
        val pixels = IntArray(160 * 120); preview.readPixels(pixels)
        assertEquals(0xff000000.toInt(), pixels[0])
        assertEquals(0xffffffff.toInt(), pixels[159])
        assertEquals(0xffff0000.toInt(), pixels[119 * 160])
        assertEquals(pixels[119 * 160], pixels[119 * 160 + 1])
    }

    @Test fun unknownRawLayoutsAndCorruptImagesDoNotProduceAnImage() {
        assertEquals(MediaThumbnail.Unavailable, decodeMediaThumbnail(RobotMediaEntry(1, 1, 3), ByteArray(38399)))
        assertEquals(MediaThumbnail.Unavailable, decodeMediaThumbnail(RobotMediaEntry(1, 1, 0), ByteArray(38400)))
        assertEquals(MediaThumbnail.Unavailable, decodeMediaThumbnail(RobotMediaEntry(1, 1, 3), byteArrayOf(1, 2, 3)))
    }
}
