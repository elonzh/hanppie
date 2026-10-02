@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package cn.elonzh.hanppie.robot.session

import cn.elonzh.hanppie.robot.files.IosRobotFtpClient
import kotlinx.cinterop.*
import platform.posix.*
import cn.elonzh.hanppie.robot.native.*

internal actual fun defaultRobotTransport(): RobotTransport = object : RobotTransport {
    override fun openDatagram(): RobotDatagram = IosRobotDatagram()
    override fun ftp(): RobotFtpClient = IosRobotFtpClient()
}

/** IPv4 is required by the robot's existing App protocol. No process-wide route changes. */
internal class IosSocket(type: Int) : AutoCloseable {
    private val lifecycleLock = RobotLock()
    private var descriptor = socket(AF_INET, type, 0).also { check(it >= 0) { "Cannot open robot socket" } }
    private var users = 0
    private var retired = -1
    private var timeoutMillis = 10_000
    private fun <T> withDescriptor(action: (Int) -> T): T {
        val fd = lifecycleLock.withLock { check(descriptor >= 0) { "Robot socket is closed" }; users++; descriptor }
        try { return action(fd) } finally {
            lifecycleLock.withLock {
                users--
                if (users == 0 && retired >= 0) { platform.posix.close(retired); retired = -1 }
            }
        }
    }
    init { option(SO_NOSIGPIPE, 1) }
    fun option(option: Int, value: Int) = withDescriptor { fd -> memScoped {
        val number = alloc<IntVar>(); number.value = value
        check(setsockopt(fd, SOL_SOCKET, option, number.ptr, sizeOf<IntVar>().toUInt()) == 0) { "Socket option failed: $errno" }
    }
    }
    fun timeout(millis: Int) = withDescriptor { fd -> memScoped {
        timeoutMillis = millis
        val timeout = alloc<timeval>()
        timeout.tv_sec = (millis / 1000).toLong()
        timeout.tv_usec = (millis % 1000 * 1000)
        check(setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, timeout.ptr, sizeOf<timeval>().toUInt()) == 0)
        check(setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, timeout.ptr, sizeOf<timeval>().toUInt()) == 0)
    }
    }
    private fun <T> address(ip: String, port: Int, action: (CPointer<sockaddr_in>) -> T): T = memScoped {
        val address = alloc<sockaddr_in>()
        memset(address.ptr, 0, sizeOf<sockaddr_in>().toULong())
        address.sin_len = sizeOf<sockaddr_in>().toUByte()
        address.sin_family = AF_INET.toUByte()
        address.sin_port = hanppie_htons(port.toUShort())
        require(hanppie_inet_pton(AF_INET, ip, address.sin_addr.ptr) == 1) { "Invalid robot IPv4 address" }
        action(address.ptr)
    }
    fun bind(ip: String, port: Int) = withDescriptor { fd -> address(ip, port) {
        check(platform.posix.bind(fd, it.reinterpret(), sizeOf<sockaddr_in>().toUInt()) == 0) { "Robot port unavailable: $errno" }
    } }
    fun connect(ip: String, port: Int) = withDescriptor { fd -> address(ip, port) { endpoint ->
        val flags = fcntl(fd, F_GETFL)
        check(flags >= 0 && fcntl(fd, F_SETFL, flags or O_NONBLOCK) == 0)
        try {
            val status = platform.posix.connect(fd, endpoint.reinterpret(), sizeOf<sockaddr_in>().toUInt())
            if (status != 0) {
                if (errno != EINPROGRESS && errno != EINTR) throw RobotRouteException("机器人 TCP 连接失败：$errno")
                memScoped {
                    val entry = alloc<pollfd>(); entry.fd = fd; entry.events = POLLOUT.toShort()
                    val deadline = robotNanoTime() + timeoutMillis * 1_000_000L
                    while (true) {
                        val remaining = ((deadline - robotNanoTime()) / 1_000_000).toInt().coerceAtLeast(0)
                        if (remaining == 0) throw RobotRouteException("机器人 TCP 连接超时")
                        val count = poll(entry.ptr, 1u, minOf(remaining, 100))
                        if (count < 0 && errno == EINTR) continue
                        if (lifecycleLock.withLock { descriptor != fd }) throw RobotRouteException("机器人 TCP 连接已取消")
                        if (count == 0) continue
                        if (count < 0) throw RobotRouteException("机器人 TCP 连接失败：$errno")
                        val error = alloc<IntVar>(); val size = alloc<UIntVar>(); size.value = sizeOf<IntVar>().toUInt()
                        check(getsockopt(fd, SOL_SOCKET, SO_ERROR, error.ptr, size.ptr) == 0)
                        if (error.value != 0) throw RobotRouteException("无法访问机器人；请检查本地网络权限与 Wi-Fi（${error.value}）")
                        break
                    }
                }
            }
        } finally { fcntl(fd, F_SETFL, flags) }
    } }
    fun send(bytes: ByteArray, ip: String, port: Int) = withDescriptor { fd -> address(ip, port) { address ->
        bytes.usePinned {
            if (sendto(fd, it.addressOf(0), bytes.size.toULong(), 0, address.reinterpret(), sizeOf<sockaddr_in>().toUInt()) != bytes.size.toLong())
                throw RobotRouteException("无法访问机器人；请检查本地网络权限与 Wi-Fi（$errno）")
        }
    }
    }
    fun receive(maxBytes: Int): RobotDatagramPacket? = withDescriptor { fd -> memScoped {
        val bytes = ByteArray(maxBytes)
        val sender = alloc<sockaddr_in>()
        val length = alloc<UIntVar>(); length.value = sizeOf<sockaddr_in>().toUInt()
        val count = bytes.usePinned { recvfrom(fd, it.addressOf(0), maxBytes.toULong(), 0, sender.ptr.reinterpret(), length.ptr) }
        if (count < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) return@withDescriptor null
            error("Robot receive failed: $errno")
        }
        val text = allocArray<ByteVar>(INET_ADDRSTRLEN)
        checkNotNull(hanppie_inet_ntop(AF_INET, sender.sin_addr.ptr, text, INET_ADDRSTRLEN.toUInt()))
        RobotDatagramPacket(bytes.copyOf(count.toInt()), text.toKString(), hanppie_ntohs(sender.sin_port).toInt())
    }
    }
    fun write(bytes: ByteArray) = withDescriptor { fd ->
        bytes.usePinned { pinned ->
            var offset = 0
            while (offset < bytes.size) {
                val count = platform.posix.send(fd, pinned.addressOf(offset), (bytes.size - offset).toULong(), 0)
                check(count > 0) { "FTP write failed: $errno" }
                offset += count.toInt()
            }
        }
    }
    fun read(bytes: ByteArray): Int = withDescriptor { fd -> bytes.usePinned {
        val count = recv(fd, it.addressOf(0), bytes.size.toULong(), 0)
        check(count >= 0) { "FTP read failed: $errno" }
        count.toInt()
    }
    }
    override fun close() = lifecycleLock.withLock {
        val old = descriptor
        descriptor = -1
        if (old >= 0) {
            shutdown(old, SHUT_RDWR)
            if (users == 0) platform.posix.close(old) else retired = old
        }
    }
}

private class IosRobotDatagram : RobotDatagram {
    private val socket = IosSocket(SOCK_DGRAM)
    override var reuseAddress = false; set(value) { socket.option(SO_REUSEADDR, if (value) 1 else 0); field = value }
    override var broadcast = false; set(value) { socket.option(SO_BROADCAST, if (value) 1 else 0); field = value }
    override var receiveBufferSize = 65535; set(value) { socket.option(SO_RCVBUF, value); field = value }
    override var soTimeout = 200; set(value) { socket.timeout(value); field = value }
    override fun bind(ip: String, port: Int) = socket.bind(ip, port)
    override fun send(bytes: ByteArray, ip: String, port: Int) = socket.send(bytes, ip, port)
    override fun receive(maxBytes: Int) = socket.receive(maxBytes)
    override fun close() = socket.close()
}
