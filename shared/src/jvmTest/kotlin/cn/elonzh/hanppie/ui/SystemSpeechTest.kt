package cn.elonzh.hanppie.ui

import kotlin.test.*

class SystemSpeechTest {
    @Test fun macUsesSystemEngineAndMissingBinaryIsUnavailable() {
        assertEquals(listOf("/usr/bin/say"), speechCommand("Mac OS X") { true })
        assertNull(speechCommand("Mac OS X") { false })
    }
    @Test fun linuxRequiresInstalledSpeechDispatcher() {
        assertEquals(listOf("/usr/bin/spd-say", "--wait", "--pipe-mode"), speechCommand("Linux") { true })
        assertNull(speechCommand("Linux") { false })
        assertNull(speechCommand("unknown") { true })
    }
    @Test fun windowsUsesConstantScriptAndStdinNotTextInterpolation() {
        val command = assertNotNull(speechCommand("Windows 11") { true })
        assertEquals("powershell.exe", command.first())
        assertTrue(command.last().contains("[Console]::In.ReadToEnd()"))
        assertTrue(command.contains("-NoProfile"))
    }
}
