package cn.elonzh.hanppie.robot.files

import cn.elonzh.hanppie.robot.session.IosSocket
import cn.elonzh.hanppie.robot.session.RobotFtpClient
import cn.elonzh.hanppie.robot.session.RobotFtpEntry
import cn.elonzh.hanppie.robot.session.RobotLock
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.readByteArray
import platform.posix.SOCK_STREAM

/** Bounded passive FTP over platform TCP sockets. PASV always uses the robot's control peer. */
internal class IosRobotFtpClient : RobotFtpClient {
    private val lock = RobotLock()
    private var control: IosSocket? = null
    private var data: IosSocket? = null
    private var peer = ""
    private var pending = ""
    private var closed = false
    override var replyCode = 0; private set
    override var replyText = ""; private set
    override var transferredBytes = 0L; private set
    override fun connect(ip: String, port: Int) {
        val socket = IosSocket(SOCK_STREAM)
        lock.withLock { check(!closed); control = socket }
        peer = ip
        socket.timeout(10_000)
        socket.connect(ip, port)
        check(response() in 200..299) { "FTP 连接失败：$replyText" }
    }
    override fun login(): Boolean {
        val code = command("USER anonymous")
        return code == 230 || code == 331 && command("PASS ") == 230
    }
    override fun binary() = command("TYPE I") in 200..299
    override fun changeWorkingDirectory(path: String) = command("CWD ${safe(path)}") in 200..299
    override fun makeDirectory(path: String) = command("MKD ${safe(path)}") in 200..299
    override fun rename(path: String, destination: String) =
        command("RNFR ${safe(path)}") == 350 && command("RNTO ${safe(destination)}") in 200..299
    override fun removeDirectory(path: String) = command("RMD ${safe(path)}") in 200..299
    override fun deleteFile(path: String) = command("DELE ${safe(path)}") in 200..299
    override fun listFiles(path: String): List<RobotFtpEntry> {
        val output = kotlinx.io.Buffer()
        val success = transfer("LIST -a ${safe(path)}") { socket ->
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = socket.read(buffer)
                if (count == 0) break
                total += count
                check(total <= 4 * 1024 * 1024) { "FTP 目录过大" }
                output.write(buffer, 0, count)
            }
        }
        check(success) { "FTP 目录读取失败：$replyText" }
        return output.readByteArray().decodeToString().lineSequence().mapNotNull(::parseUnixEntry).toList()
    }
    override fun storeFile(path: String, source: Source): Boolean {
        transferredBytes = 0
        return transfer("STOR ${safe(path)}") { socket ->
            while (!source.exhausted()) {
                val chunk = kotlinx.io.Buffer()
                if (source.readAtMostTo(chunk, 64 * 1024L) <= 0) break
                val bytes = chunk.readByteArray()
                socket.write(bytes)
                transferredBytes += bytes.size
            }
        }
    }
    override fun retrieveFile(path: String, destination: Sink) = transfer("RETR ${safe(path)}") { socket ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = socket.read(buffer)
            if (count == 0) break
            destination.write(buffer, 0, count)
        }
        destination.flush()
    }
    private fun transfer(request: String, action: (IosSocket) -> Unit): Boolean {
        check(command("PASV") == 227) { "FTP 被动模式不可用：$replyText" }
        val parts = replyText.substringAfter('(').substringBefore(')').split(',').map { it.trim().toInt() }
        require(parts.size == 6 && parts.all { it in 0..255 }) { "Invalid FTP passive response" }
        val socket = IosSocket(SOCK_STREAM)
        lock.withLock { check(!closed); data = socket }
        try {
            socket.timeout(30_000)
            socket.connect(peer, parts[4] * 256 + parts[5])
            if (command(request) !in 100..199) return false
            action(socket)
            socket.close()
            return response() in 200..299
        } finally {
            socket.close()
            lock.withLock { data = null }
        }
    }
    private fun safe(value: String): String {
        require(value.none { it == '\r' || it == '\n' || it == '\u0000' })
        return value
    }
    private fun command(value: String): Int {
        checkNotNull(control).write((value + "\r\n").encodeToByteArray())
        return response()
    }
    private fun line(): String {
        while ('\n' !in pending) {
            check(pending.length <= 65536) { "FTP response too long" }
            val bytes = ByteArray(1024)
            val count = checkNotNull(control).read(bytes)
            check(count > 0) { "FTP connection closed" }
            pending += bytes.copyOf(count).decodeToString()
        }
        val result = pending.substringBefore('\n').trimEnd('\r')
        pending = pending.substringAfter('\n')
        return result
    }
    private fun response(): Int {
        val first = line()
        val code = first.take(3).toIntOrNull() ?: error("Invalid FTP response")
        val lines = mutableListOf(first)
        if (first.getOrNull(3) == '-') {
            while (true) {
                check(lines.size < 100) { "FTP response too long" }
                val next = line(); lines += next
                if (next.startsWith("$code ")) break
            }
        }
        replyCode = code; replyText = lines.joinToString("\n")
        return code
    }
    override fun close() = lock.withLock {
        closed = true
        data?.close(); control?.close()
        data = null; control = null
    }
}
