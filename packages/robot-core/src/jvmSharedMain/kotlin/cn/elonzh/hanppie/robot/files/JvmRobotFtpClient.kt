package cn.elonzh.hanppie.robot.files

import cn.elonzh.hanppie.robot.session.RobotFtpClient
import cn.elonzh.hanppie.robot.session.RobotFtpEntry
import java.io.FilterInputStream
import java.time.Duration
import javax.net.SocketFactory
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.asInputStream
import kotlinx.io.asOutputStream
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient

/** JVM socket routing and Apache FTP adaptation only. */
internal class JvmRobotFtpClient(socketFactory: SocketFactory) : RobotFtpClient {
    private val ftp = FTPClient().apply {
        setSocketFactory(socketFactory)
        setAutodetectUTF8(false)
        controlEncoding = "UTF-8"
        connectTimeout = 5000
        defaultTimeout = 10000
        dataTimeout = Duration.ofSeconds(30)
        listHiddenFiles = true
    }
    override val replyCode get() = ftp.replyCode
    override val replyText get() = ftp.replyString?.trim().orEmpty().ifBlank { "code $replyCode" }
    override var transferredBytes = 0L; private set
    override fun connect(ip: String, port: Int) {
        ftp.connect(ip, port)
        check(replyCode in 200..299) { "FTP 连接失败：$replyText" }
        ftp.enterLocalPassiveMode()
    }
    override fun login() = ftp.login("anonymous", "")
    override fun binary() = ftp.setFileType(FTP.BINARY_FILE_TYPE)
    override fun changeWorkingDirectory(path: String) = ftp.changeWorkingDirectory(path)
    override fun listFiles(path: String): List<RobotFtpEntry> = ftp.listFiles(path).map {
        RobotFtpEntry(it.name, when {
            it.isDirectory -> RobotFileKind.DIRECTORY
            it.isFile -> RobotFileKind.FILE
            it.isSymbolicLink -> RobotFileKind.LINK
            else -> RobotFileKind.UNKNOWN
        }, it.size, it.timestampInstant?.toEpochMilli())
    }
    override fun storeFile(path: String, source: Source): Boolean {
        transferredBytes = 0
        val counted = object : FilterInputStream(source.asInputStream()) {
            override fun read(): Int = `in`.read().also { if (it >= 0) transferredBytes++ }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                `in`.read(bytes, offset, length).also { if (it > 0) transferredBytes += it }
        }
        return ftp.storeFile(path, counted)
    }
    override fun retrieveFile(path: String, destination: Sink) = ftp.retrieveFile(path, destination.asOutputStream())
    override fun makeDirectory(path: String) = ftp.makeDirectory(path)
    override fun rename(path: String, destination: String) = ftp.rename(path, destination)
    override fun removeDirectory(path: String) = ftp.removeDirectory(path)
    override fun deleteFile(path: String) = ftp.deleteFile(path)
    override fun close() {
        if (ftp.isConnected) ftp.disconnect()
    }
}
