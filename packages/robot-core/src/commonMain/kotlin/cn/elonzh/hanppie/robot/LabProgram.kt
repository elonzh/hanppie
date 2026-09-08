package cn.elonzh.hanppie.robot

data class LabProgram(val source: String, val guid: String, val sign: String, val title: String = "Hanppie-Lab") {
    init {
        require(Regex("[a-f0-9]{32}").matches(guid))
        require(Regex("[a-f0-9]{16}").matches(sign))
        require(source.isNotBlank())
    }

    fun dsp(date: String): ByteArray {
        require(Regex("[0-9]{4}/[0-9]{2}/[0-9]{2}").matches(date))
        val escapedTitle = title.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val cdata = source.replace("]]>", "]]]]><![CDATA[>")
        return ("<dji><attribute><creation_date>$date</creation_date><modify_time>$date</modify_time>" +
            "<sign>$sign</sign><guid>$guid</guid><creator>Hanppie</creator>" +
            "<firmware_version_dependency>00.00.0000</firmware_version_dependency>" +
            "<title>$escapedTitle</title><code_type>python</code_type>" +
            "<app_min_version></app_min_version><app_max_version></app_max_version>" +
            "</attribute><audio-list /><code><python_code><![CDATA[$cdata]]></python_code></code></dji>").encodeToByteArray()
    }

    fun metadata(marker: Int): ByteArray = byteArrayOf(marker.toByte()) + (guid + sign).encodeToByteArray()
    fun guidMetadata(): ByteArray = byteArrayOf(0x2d) + guid.encodeToByteArray() + byteArrayOf(0, 0)
}
