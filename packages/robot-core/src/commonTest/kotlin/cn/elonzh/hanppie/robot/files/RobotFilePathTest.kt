package cn.elonzh.hanppie.robot.files

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RobotFilePathTest {
    @Test
    fun normalizesOnlyPathsInsideTheFtpRoot() {
        assertEquals("/", RobotFilePath.normalize("/"))
        assertEquals("/audio/tone.opus", RobotFilePath.normalize("/audio/tone.opus"))
        assertFailsWith<IllegalArgumentException> { RobotFilePath.normalize("audio") }
        assertFailsWith<IllegalArgumentException> { RobotFilePath.normalize("/audio/../python") }
        assertFailsWith<IllegalArgumentException> { RobotFilePath.normalize("/audio/") }
        assertFailsWith<IllegalArgumentException> { RobotFilePath.requireName("bad/name") }
        assertFailsWith<IllegalArgumentException> { RobotFilePath.requireName("中文.wav") }
        assertEquals("upload.wav", RobotFilePath.portableUploadName("中文.wav"))
        assertEquals("voice-1.wav", RobotFilePath.portableUploadName("语音voice-1.wav"))
        assertTrue(RobotFilePath.isProtected("/python/python_raw.dsp"))
        assertFalse(RobotFilePath.isProtected("/python/archive.dsp"))
    }
}
