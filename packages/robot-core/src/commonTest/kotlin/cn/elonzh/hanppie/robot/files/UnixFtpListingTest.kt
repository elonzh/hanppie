package cn.elonzh.hanppie.robot.files

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith

class UnixFtpListingTest {
    @Test fun preservesNamesAndOnlyStripsLinkTargets() {
        val file = parseUnixEntry("-rw-r--r-- 1 root root 123 Oct 2 11:30 a -> b.txt")!!
        assertEquals("a -> b.txt", file.name)
        assertEquals(123L, file.size)
        assertEquals(RobotFileKind.FILE, file.kind)
        val link = parseUnixEntry("lrwxrwxrwx 1 root root 4 Oct 2 11:30 current -> data")!!
        assertEquals("current", link.name)
        assertEquals(RobotFileKind.LINK, link.kind)
        assertEquals(RobotFileKind.DIRECTORY, parseUnixEntry("drwxr-xr-x 2 root root 4096 Oct 2 2026 audio")!!.kind)
        assertNull(parseUnixEntry("total 12"))
        assertNull(parseUnixEntry(""))
    }
    @Test fun rejectsUnrecognizableRowsAndInvalidSizes() {
        assertFailsWith<IllegalArgumentException> { parseUnixEntry("not a Unix listing") }
        assertFailsWith<IllegalArgumentException> { parseUnixEntry("-rw-r--r-- 1 root root -1 Oct 2 11:30 bad") }
        assertFailsWith<IllegalArgumentException> { parseUnixEntry("-rw-r--r-- 1 root root unknown Oct 2 11:30 bad") }
    }
}
