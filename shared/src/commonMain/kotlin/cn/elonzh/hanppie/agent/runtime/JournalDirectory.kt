package cn.elonzh.hanppie.agent.runtime

/** Durable file operations and a stable directory lock are platform capabilities. */
internal interface JournalDirectory {
    fun names(): List<String>
    fun read(name: String): ByteArray?
    fun append(name: String, bytes: ByteArray)
    fun truncate(name: String, size: Long)
    fun delete(name: String)
    fun <T> locked(action: () -> T): T
}
