package cn.elonzh.hanppie.robot.session

import java.net.DatagramSocket
import java.net.DatagramPacket
import java.net.InetSocketAddress
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.NoRouteToHostException
import cn.elonzh.hanppie.robot.files.JvmRobotFtpClient
import javax.net.SocketFactory

/** Routes robot sockets without changing the process-wide route used by cloud requests. */
interface RobotNetwork : RobotTransport {
    fun datagram(): DatagramSocket = DatagramSocket(null)
    val socketFactory: SocketFactory get() = SocketFactory.getDefault()

    override fun openDatagram(): RobotDatagram = JvmRobotDatagram(datagram())
    override fun ftp(): RobotFtpClient = JvmRobotFtpClient(socketFactory)
    companion object { val Default: RobotNetwork = object : RobotNetwork {} }
}

private class JvmRobotDatagram(private val socket: DatagramSocket) : RobotDatagram {
    override var reuseAddress: Boolean get() = socket.reuseAddress; set(value) { socket.reuseAddress = value }
    override var broadcast: Boolean get() = socket.broadcast; set(value) { socket.broadcast = value }
    override var receiveBufferSize: Int get() = socket.receiveBufferSize; set(value) { socket.receiveBufferSize = value }
    override var soTimeout: Int get() = socket.soTimeout; set(value) { socket.soTimeout = value }
    override fun bind(ip: String, port: Int) = socket.bind(InetSocketAddress(ip, port))
    override fun send(bytes: ByteArray, ip: String, port: Int) {
        try { socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(ip), port)) }
        catch (error: NoRouteToHostException) { throw RobotRouteException(error.message.orEmpty(), error) }
    }
    override fun receive(maxBytes: Int): RobotDatagramPacket? {
        val packet = DatagramPacket(ByteArray(maxBytes), maxBytes)
        return try {
            socket.receive(packet)
            RobotDatagramPacket(packet.data.copyOf(packet.length), packet.address.hostAddress, packet.port)
        } catch (_: SocketTimeoutException) { null }
    }
    override fun close() = socket.close()
}

internal actual fun defaultRobotTransport(): RobotTransport = RobotNetwork.Default
