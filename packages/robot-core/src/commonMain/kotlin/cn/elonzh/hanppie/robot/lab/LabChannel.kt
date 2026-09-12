package cn.elonzh.hanppie.robot.lab

/** The Lab lifecycle needs a persistent command channel, not a host Python interpreter. */
interface LabChannel {
    val connected: Boolean
    fun send(receiver: Int, attr: Int, set: Int, id: Int, payload: ByteArray = byteArrayOf(),
             sender: Int = 2, flags: ByteArray = byteArrayOf(attr.toByte(), 0)): Int
    fun labMode(running: Boolean = false)
}
