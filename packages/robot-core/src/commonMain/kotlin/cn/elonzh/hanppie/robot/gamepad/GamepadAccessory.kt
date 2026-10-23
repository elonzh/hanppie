package cn.elonzh.hanppie.robot.gamepad

/** An ephemeral platform discovery handle, never a serial number or persisted identity. */
data class GamepadAccessory(val id: String, val label: String)

/** The official accessory has its own byte-stream link, separate from the robot connection. */
interface GamepadConnector {
    val available: Boolean
    fun accessories(): List<GamepadAccessory>
    suspend fun open(id: String): GamepadTransport
}

interface GamepadTransport : AutoCloseable {
    /** One chunk from the stream, not necessarily one frame; null means end of stream. */
    suspend fun read(): ByteArray?
    suspend fun write(bytes: ByteArray)
    /** Idempotently release the native stream, including any blocked read. */
    override fun close()
}

object NoGamepadConnector : GamepadConnector {
    override val available = false
    override fun accessories(): List<GamepadAccessory> = emptyList()
    override suspend fun open(id: String): GamepadTransport = error("Gamepad accessory link unavailable")
}

class GamepadPermissionDenied : Exception("Accessory permission denied")
