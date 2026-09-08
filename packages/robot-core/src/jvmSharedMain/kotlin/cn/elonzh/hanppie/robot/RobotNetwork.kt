package cn.elonzh.hanppie.robot

import java.net.DatagramSocket
import javax.net.SocketFactory

/** Routes robot sockets without changing the process-wide route used by cloud requests. */
interface RobotNetwork {
    fun datagram(): DatagramSocket = DatagramSocket(null)
    val socketFactory: SocketFactory get() = SocketFactory.getDefault()

    companion object { val Default: RobotNetwork = object : RobotNetwork {} }
}
