package cn.elonzh.hanppie.robot.session

import cn.elonzh.hanppie.robot.files.RobotFileKind
import kotlinx.io.Sink
import kotlinx.io.Source

/** Platform sockets; session state, framing and safety remain shared. */
interface RobotTransport {
    fun openDatagram(): RobotDatagram
    fun ftp(): RobotFtpClient
    companion object { val Default: RobotTransport get() = defaultRobotTransport() }
}

data class RobotDatagramPacket(val bytes: ByteArray, val ip: String, val port: Int)

interface RobotDatagram : AutoCloseable {
    var reuseAddress: Boolean
    var broadcast: Boolean
    var receiveBufferSize: Int
    var soTimeout: Int
    fun bind(ip: String, port: Int)
    fun send(bytes: ByteArray, ip: String, port: Int)
    /** Returns null when the bounded receive times out. */
    fun receive(maxBytes: Int = 65535): RobotDatagramPacket?
}

class RobotRouteException(message: String, cause: Throwable? = null) : Exception(message, cause)
internal class RobotReceiveTimeout : Exception()

data class RobotFtpEntry(val name: String, val kind: RobotFileKind, val size: Long, val modifiedAtEpochMillis: Long? = null)

interface RobotFtpClient : AutoCloseable {
    val replyCode: Int
    val replyText: String
    val transferredBytes: Long
    fun connect(ip: String, port: Int)
    fun login(): Boolean
    fun binary(): Boolean
    fun changeWorkingDirectory(path: String): Boolean
    fun listFiles(path: String): List<RobotFtpEntry>
    fun storeFile(path: String, source: Source): Boolean
    fun retrieveFile(path: String, destination: Sink): Boolean
    fun makeDirectory(path: String): Boolean
    fun rename(path: String, destination: String): Boolean
    fun removeDirectory(path: String): Boolean
    fun deleteFile(path: String): Boolean
}

internal expect class RobotLock() { fun <T> withLock(action: () -> T): T }
private val monotonicOrigin = kotlin.time.TimeSource.Monotonic.markNow()
internal fun robotNanoTime(): Long = monotonicOrigin.elapsedNow().inWholeNanoseconds
internal expect fun robotSleep(millis: Long)
internal expect fun robotMd5(bytes: ByteArray): ByteArray
internal expect fun robotRandomBytes(size: Int): ByteArray
internal expect fun robotDate(): String

internal expect fun defaultRobotTransport(): RobotTransport
