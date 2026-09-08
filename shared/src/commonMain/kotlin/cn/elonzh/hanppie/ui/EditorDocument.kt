package cn.elonzh.hanppie.ui

internal data class EditorDocument(
    val source: String = "def start():\n    print('Hello from Hanppie')\n",
    val savedSource: String = source,
    val path: String? = null,
    val busy: Boolean = false,
) {
    val dirty: Boolean get() = source != savedSource
    fun saved(snapshot: String, destination: String): EditorDocument =
        copy(savedSource = snapshot, path = destination, busy = false)
}
