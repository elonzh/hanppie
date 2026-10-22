package cn.elonzh.hanppie.robot.product

import cn.elonzh.hanppie.robot.protocol.*
import cn.elonzh.hanppie.robot.session.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlin.concurrent.Volatile

/** Native programming-sleep policy. Changing it does not switch robot modes. */
data class LowPowerPolicy(val enabled: Boolean, val delaySeconds: Int)
enum class RobotPowerAction { ENTER_STANDBY, EXIT_STANDBY, REBOOT, SHUTDOWN }

interface RobotPowerService {
    suspend fun readPolicy(): LowPowerPolicy
    suspend fun setPolicy(policy: LowPowerPolicy, onDispatch: () -> Unit)
    suspend fun perform(action: RobotPowerAction, onDispatch: () -> Unit)
}

/** Configuration and actions are distinct native operations; success is an ACK, never physical proof. */
internal class PowerCommands(private val request: suspend (Int, Int, Int, ByteArray) -> ByteArray) : RobotPowerService {
    private val access = Mutex()
    @Volatile private var closed = false

    private suspend fun command(node: Int, set: Int, id: Int, payload: ByteArray = byteArrayOf(), onDispatch: () -> Unit = {}): ByteArray {
        currentCoroutineContext().ensureActive()
        if (closed) throw RobotRequestException(RequestProblem.DISCONNECTED)
        onDispatch()
        return request(node, set, id, payload)
    }
    override suspend fun readPolicy(): LowPowerPolicy = task {
        val p = command(9, 0x3f, 0x4b)
        if (p.size < 4) throw RobotRequestException(RequestProblem.INVALID_RESPONSE)
        LowPowerPolicy(p.u8(1) != 0, p.u16(2))
    }
    override suspend fun setPolicy(policy: LowPowerPolicy, onDispatch: () -> Unit) = task {
        require(policy.delaySeconds in 0..65535)
        command(9, 0x3f, 0x4a, ByteArray(3).apply {
            this[0] = if (policy.enabled) 1 else 0; put16(1, policy.delaySeconds)
        }, onDispatch)
        Unit
    }
    override suspend fun perform(action: RobotPowerAction, onDispatch: () -> Unit) = task {
        when (action) {
            RobotPowerAction.ENTER_STANDBY, RobotPowerAction.EXIT_STANDBY -> command(9, 0x3f, 0x4c,
                byteArrayOf(if (action == RobotPowerAction.ENTER_STANDBY) 1 else 0), onDispatch)
            RobotPowerAction.REBOOT -> command(0x0b, 0, 0x0b, byteArrayOf(), onDispatch)
            RobotPowerAction.SHUTDOWN -> command(0x0b, 0, 0x0b, ByteArray(14).apply { this[1] = 2 }, onDispatch)
        }
        Unit
    }
    private suspend fun <T> task(action: suspend () -> T): T {
        check(access.tryLock()) { "电源请求正在进行" }
        try { return action() } finally { access.unlock() }
    }
    fun close() { closed = true }
}
