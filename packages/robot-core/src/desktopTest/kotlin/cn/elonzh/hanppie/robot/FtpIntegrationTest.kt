package cn.elonzh.hanppie.robot

import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.*

/** Local protocol fixture: never talks to physical hardware. */
class FtpIntegrationTest {
    @Test fun passiveBinaryUploadIsByteExact() {
        val received = CompletableFuture<ByteArray>()
        val listener = ServerSocket(0)
        val server = thread(isDaemon = true) {
            try {
                listener.use {
                    it.accept().use { control ->
                        control.soTimeout = 5000
                        val reader = control.getInputStream().bufferedReader()
                        val writer = control.getOutputStream().bufferedWriter()
                        fun reply(value: String) { writer.write("$value\r\n"); writer.flush() }
                        var data: ServerSocket? = null
                        try {
                            reply("220 fixture")
                            while (true) {
                                val command = reader.readLine() ?: break
                                when {
                                    command.startsWith("USER ") -> reply("331 password")
                                    command.startsWith("PASS") -> reply("230 logged in")
                                    command == "CWD python" -> reply("250 directory")
                                    command == "TYPE I" -> reply("200 binary")
                                    command == "PASV" -> {
                                        data = ServerSocket(0).apply { soTimeout = 5000 }
                                        val port = data.localPort
                                        reply("227 Entering Passive Mode (127,0,0,1,${port / 256},${port % 256})")
                                    }
                                    command == "STOR python_raw.dsp" -> {
                                        reply("150 ready")
                                        val bytes = checkNotNull(data).accept().use { socket -> socket.getInputStream().readBytes() }
                                        data.close(); data = null
                                        received.complete(bytes)
                                        reply("226 complete")
                                    }
                                    command == "QUIT" -> { reply("221 bye"); break }
                                    else -> reply("500 unexpected command")
                                }
                            }
                        } finally { data?.close() }
                    }
                }
            } catch (error: Throwable) { received.completeExceptionally(error) }
        }
        val bytes = LabProgram("def start():\n    print('你好')", "a".repeat(32), "b".repeat(16)).dsp("2026/09/07")
        var routedSockets = 0
        val factory = object : javax.net.SocketFactory() {
            override fun createSocket() = java.net.Socket().also { routedSockets++ }
            override fun createSocket(host: String, port: Int) = error("Expected unconnected socket")
            override fun createSocket(host: String, port: Int, local: java.net.InetAddress, localPort: Int) = error("Expected unconnected socket")
            override fun createSocket(host: java.net.InetAddress, port: Int) = error("Expected unconnected socket")
            override fun createSocket(host: java.net.InetAddress, port: Int, local: java.net.InetAddress, localPort: Int) = error("Expected unconnected socket")
        }
        val network = object : RobotNetwork { override val socketFactory = factory }
        try {
            LabController.transfer("127.0.0.1", bytes, listener.localPort, network)
            assertContentEquals(bytes, received.get(5, TimeUnit.SECONDS))
            assertEquals(2, routedSockets, "FTP control and passive data must use the selected network")
        } finally { listener.close(); server.join(5000) }
    }

    @Test fun rejectedLoginMustFail() {
        ServerSocket(0).use { listener ->
            val server = thread(isDaemon = true) {
                listener.accept().use { control ->
                    val output = control.getOutputStream().bufferedWriter()
                    output.write("220 fixture\r\n"); output.flush()
                    control.getInputStream().bufferedReader().readLine()
                    output.write("530 denied\r\n"); output.flush()
                }
            }
            assertFailsWith<IllegalStateException> { LabController.transfer("127.0.0.1", byteArrayOf(1), listener.localPort) }
            server.join(5000)
        }
    }
}
