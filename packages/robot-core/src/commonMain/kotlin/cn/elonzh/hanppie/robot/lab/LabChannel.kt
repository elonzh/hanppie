package cn.elonzh.hanppie.robot.lab

/** The Lab lifecycle needs a persistent command channel, not a host Python interpreter. */
interface LabChannel {
    val connected: Boolean
    fun send(receiver: Int, attr: Int, set: Int, id: Int, payload: ByteArray = byteArrayOf(),
             sender: Int = 2, flags: ByteArray = byteArrayOf(attr.toByte(), 0)): Int
    /** Correlated scratch_sys reply, retaining its raw selector-specific response bytes. */
    suspend fun requestLab(id: Int, payload: ByteArray): ByteArray
    /** Null retires run-scoped UI and sensor subscriptions; a new owner precedes the execute packet. */
    fun setLabRun(runId: String?)
    fun labMode(running: Boolean = false)
}
