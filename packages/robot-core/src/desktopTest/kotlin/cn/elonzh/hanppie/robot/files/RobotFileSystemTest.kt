package cn.elonzh.hanppie.robot.files

import cn.elonzh.hanppie.robot.session.RobotNetwork
import cn.elonzh.hanppie.robot.session.RobotTarget
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import javax.net.SocketFactory
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.readByteArray

/** Local FTP fixture only; no test in this class talks to a physical robot. */
class RobotFileSystemTest {
    private var fixture: FtpFileFixture? = null

    @AfterTest fun closeFixture() { fixture?.close() }

    @Test fun browsesAndTransfersWithoutOverwritingExistingFiles() = runBlocking {
        val server = FtpFileFixture().also { fixture = it }
        server.directories += "/audio"
        server.directories += "/python"
        server.files["/audio/tone.opus"] = byteArrayOf(1, 2, 3)
        server.files["/python/python_raw.dsp"] = byteArrayOf(9)
        val sockets = CountingSocketFactory()
        val network = object : RobotNetwork { override val socketFactory: SocketFactory = sockets }
        val storage = RobotFileSystem(RobotTarget("127.0.0.1", "01020304"), network, server.port)

        assertEquals(listOf("audio", "python"), storage.list("/").map { it.name })
        val audio = storage.list("/audio").single()
        assertEquals(RobotFileKind.FILE, audio.kind)
        assertEquals(3, audio.size)

        val uploaded = storage.upload("/audio", "tone.opus", Buffer().apply { write(byteArrayOf(4, 5, 6, 7)) })
        assertEquals("tone-2.opus", uploaded.name)
        assertEquals(4, uploaded.size)
        assertContentEquals(byteArrayOf(4, 5, 6, 7), server.files.getValue(uploaded.path))

        val portable = storage.upload("/audio", "录音.wav", Buffer().apply { write(byteArrayOf(8)) })
        assertEquals("upload.wav", portable.name)
        assertContentEquals(byteArrayOf(8), server.files.getValue(portable.path))

        val downloaded = Buffer()
        storage.download(uploaded.path, downloaded)
        assertContentEquals(byteArrayOf(4, 5, 6, 7), downloaded.readByteArray())

        val renamed = storage.rename(uploaded.path, "voice.opus")
        assertEquals("/audio/voice.opus", renamed)
        assertTrue(server.files.containsKey(renamed))
        val directory = storage.createDirectory("/audio", "clips")
        assertTrue(directory.path in server.directories)
        storage.delete(RobotFileEntry(renamed, "voice.opus", RobotFileKind.FILE))
        storage.delete(directory)
        assertFalse(server.files.containsKey(renamed))
        assertFalse(directory.path in server.directories)
        assertTrue(sockets.created >= 13, "Control and passive data sockets must use the selected robot network")
        server.assertHealthy()
    }

    @Test fun protectsTheActiveLabUploadSlotBeforeOpeningFtp() {
        runBlocking {
            val target = RobotTarget("127.0.0.1", "01020304")
            val storage = RobotFileSystem(target, port = 1)
            val slot = RobotFileEntry("/python/python_raw.dsp", "python_raw.dsp", RobotFileKind.FILE)
            assertFailsWith<IllegalStateException> { storage.delete(slot) }
            assertFailsWith<IllegalStateException> { storage.rename(slot.path, "old.dsp") }
        }
    }

    @Test fun uploadNeverClaimsTheReservedLabSlot() = runBlocking {
        val server = FtpFileFixture().also { fixture = it }
        server.directories += "/python"
        val storage = RobotFileSystem(RobotTarget("127.0.0.1", "01020304"), port = server.port)
        val uploaded = storage.upload("/python", "python_raw.dsp", Buffer().apply { write(byteArrayOf(7)) })
        assertEquals("/python/python_raw-2.dsp", uploaded.path)
        assertFalse(server.files.containsKey("/python/python_raw.dsp"))
        assertContentEquals(byteArrayOf(7), server.files.getValue(uploaded.path))
    }
}

private class CountingSocketFactory : SocketFactory() {
    var created = 0
    override fun createSocket(): Socket = Socket().also { created++ }
    override fun createSocket(host: String, port: Int): Socket = error("Expected unconnected socket")
    override fun createSocket(host: String, port: Int, localHost: java.net.InetAddress, localPort: Int): Socket = error("Expected unconnected socket")
    override fun createSocket(host: java.net.InetAddress, port: Int): Socket = error("Expected unconnected socket")
    override fun createSocket(address: java.net.InetAddress, port: Int, localAddress: java.net.InetAddress, localPort: Int): Socket = error("Expected unconnected socket")
}

private class FtpFileFixture : AutoCloseable {
    private val listener = ServerSocket(0)
    val port: Int get() = listener.localPort
    val files = ConcurrentHashMap<String, ByteArray>()
    val directories = ConcurrentHashMap.newKeySet<String>().apply { add("/") }
    private val errors = ConcurrentLinkedQueue<Throwable>()
    @Volatile private var closed = false
    private val server = thread(name = "robot-files-ftp-fixture", isDaemon = true) {
        while (!closed) {
            try {
                listener.accept().use(::handle)
            } catch (error: SocketException) {
                if (!closed) errors += error
            } catch (error: Throwable) {
                errors += error
            }
        }
    }

    private fun handle(control: Socket) {
        control.soTimeout = 5000
        val reader = control.getInputStream().bufferedReader()
        val writer = control.getOutputStream().bufferedWriter()
        fun reply(value: String) { writer.write("$value\r\n"); writer.flush() }
        var data: ServerSocket? = null
        var renameFrom: String? = null
        fun passive(): ServerSocket = checkNotNull(data) { "PASV must precede data command" }
        try {
            reply("220 Hanppie fixture")
            while (true) {
                val command = reader.readLine() ?: break
                when {
                    command.startsWith("USER ") -> reply("331 password")
                    command.startsWith("PASS") -> reply("230 logged in")
                    command == "SYST" -> reply("215 UNIX Type: L8")
                    command == "FEAT" -> {
                        writer.write("211-Features\r\n UTF8\r\n211 End\r\n")
                        writer.flush()
                    }
                    command == "OPTS UTF8 ON" -> reply("200 UTF8 enabled")
                    command == "TYPE I" -> reply("200 binary")
                    command == "PASV" -> {
                        data?.close()
                        data = ServerSocket(0).apply { soTimeout = 5000 }
                        val dataPort = data.localPort
                        reply("227 Entering Passive Mode (127,0,0,1,${dataPort / 256},${dataPort % 256})")
                    }
                    command.startsWith("LIST") -> {
                        val requested = command.removePrefix("LIST").trim().removePrefix("-a").trim().ifEmpty { "/" }
                        reply("150 opening data")
                        passive().accept().use { socket ->
                            socket.getOutputStream().bufferedWriter().use { listing ->
                                childDirectories(requested).forEach { name ->
                                    listing.write("drwxr-xr-x 1 root root 0 Sep 12 2026 $name\r\n")
                                }
                                childFiles(requested).forEach { (name, bytes) ->
                                    listing.write("-rw-r--r-- 1 root root ${bytes.size} Sep 12 2026 $name\r\n")
                                }
                            }
                        }
                        data?.close(); data = null
                        reply("226 complete")
                    }
                    command.startsWith("STOR ") -> {
                        val path = command.removePrefix("STOR ")
                        reply("150 opening data")
                        files[path] = passive().accept().use { it.getInputStream().readBytes() }
                        data?.close(); data = null
                        reply("226 complete")
                    }
                    command.startsWith("RETR ") -> {
                        val bytes = files[command.removePrefix("RETR ")]
                        if (bytes == null) reply("550 missing") else {
                            reply("150 opening data")
                            passive().accept().use { it.getOutputStream().use { output -> output.write(bytes) } }
                            data?.close(); data = null
                            reply("226 complete")
                        }
                    }
                    command.startsWith("RNFR ") -> {
                        val path = command.removePrefix("RNFR ")
                        if (files.containsKey(path) || path in directories) { renameFrom = path; reply("350 ready") }
                        else reply("550 missing")
                    }
                    command.startsWith("RNTO ") -> {
                        val source = renameFrom
                        val destination = command.removePrefix("RNTO ")
                        when {
                            source == null -> reply("503 RNFR required")
                            files.containsKey(destination) || destination in directories -> reply("550 occupied")
                            files.containsKey(source) -> { files[destination] = files.remove(source)!!; reply("250 renamed") }
                            source in directories -> { directories.remove(source); directories += destination; reply("250 renamed") }
                            else -> reply("550 missing")
                        }
                        renameFrom = null
                    }
                    command.startsWith("MKD ") -> {
                        val path = command.removePrefix("MKD ")
                        if (files.containsKey(path) || path in directories) reply("550 occupied")
                        else { directories += path; reply("257 created") }
                    }
                    command.startsWith("DELE ") -> {
                        if (files.remove(command.removePrefix("DELE ")) != null) reply("250 deleted") else reply("550 missing")
                    }
                    command.startsWith("RMD ") -> {
                        val path = command.removePrefix("RMD ")
                        val hasChildren = directories.any { it != path && parent(it) == path } || files.keys.any { parent(it) == path }
                        if (path in directories && !hasChildren) { directories.remove(path); reply("250 deleted") }
                        else reply("550 not empty")
                    }
                    command == "QUIT" -> { reply("221 bye"); break }
                    else -> reply("500 unexpected $command")
                }
            }
        } finally {
            data?.close()
        }
    }

    private fun childDirectories(parent: String) = directories.asSequence()
        .filter { it != "/" && parent(it) == parent }.map { it.substringAfterLast('/') }.sorted().toList()

    private fun childFiles(parent: String) = files.entries.asSequence()
        .filter { parent(it.key) == parent }.map { it.key.substringAfterLast('/') to it.value }.sortedBy { it.first }.toList()

    fun assertHealthy() { if (errors.isNotEmpty()) throw AssertionError("FTP fixture failed", errors.peek()) }

    override fun close() {
        closed = true
        listener.close()
        server.join(5000)
        assertHealthy()
    }

    private fun parent(path: String): String = path.substringBeforeLast('/', "").ifEmpty { "/" }
}
