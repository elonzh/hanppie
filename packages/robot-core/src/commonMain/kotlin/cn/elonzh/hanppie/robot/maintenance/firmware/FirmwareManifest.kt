package cn.elonzh.hanppie.robot.maintenance.firmware

import nl.adaptivity.xmlutil.EventType
import nl.adaptivity.xmlutil.XmlReader
import nl.adaptivity.xmlutil.xmlStreaming

enum class FirmwarePackageProblem { INVALID_MANIFEST, INVALID_ARCHIVE, UNSUPPORTED_ARCHIVE, MISSING_FILE, UNEXPECTED_FILE, SIZE_MISMATCH, CHECKSUM_MISMATCH }
class FirmwarePackageException(val problem: FirmwarePackageProblem, val fileName: String? = null) :
    Exception("Firmware package: $problem${fileName?.let { " ($it)" }.orEmpty()}")

data class FirmwareModule(
    val id: String,
    val version: String,
    val hardwareType: String,
    val group: String,
    val size: Long,
    val md5: String,
    val fileName: String,
)

data class FirmwareManifest(
    val deviceId: String,
    val version: String,
    val releaseAttributes: Map<String, String>,
    val modules: List<FirmwareModule>,
    val signaturePrefixBytes: Int,
) {
    companion object {
        /** Parses metadata only. It does not authenticate the vendor's signature. */
        fun parse(bytes: ByteArray): FirmwareManifest {
            try { return parseManifest(bytes) } catch (error: FirmwarePackageException) { throw error }
            catch (_: Exception) { throw FirmwarePackageException(FirmwarePackageProblem.INVALID_MANIFEST) }
        }
    }
}

private fun parseManifest(bytes: ByteArray): FirmwareManifest {
    require(bytes.size in 1..1_048_576)
    val marker = "<dji>".encodeToByteArray()
    val start = (0..bytes.size - marker.size).firstOrNull { offset -> marker.indices.all { bytes[offset + it] == marker[it] } }
        ?: error("Missing manifest root")
    val xml = bytes.decodeToString(start, bytes.size, throwOnInvalidSequence = true).trimEnd('\u0000')
    // Reject declarations before the parser has any opportunity to process an entity or external DTD.
    require(!xml.contains("<!DOCTYPE", ignoreCase = true) && !xml.contains("<!ENTITY", ignoreCase = true))
    val reader = xmlStreaming.newGenericReader(xml, expandEntities = false)
    try {
        val stack = mutableListOf<String>()
        var device: String? = null
        var formal: String? = null
        var release: Map<String, String>? = null
        var module: Map<String, String>? = null
        val text = StringBuilder()
        val modules = mutableListOf<FirmwareModule>()
        var rootSeen = false
        while (reader.hasNext()) when (reader.next()) {
            EventType.START_ELEMENT -> {
                require(reader.namespaceURI.isEmpty())
                val name = reader.localName
                when (stack.size) {
                    0 -> { require(name == "dji" && !rootSeen); rootSeen = true }
                    1 -> { require(name == "device" && device == null); device = reader.attribute("id").also { require(it.matches(Regex("[A-Za-z0-9_-]{1,32}"))) } }
                    2 -> { require(name == "firmware" && formal == null); formal = reader.attribute("formal") }
                    3 -> { require(name == "release" && release == null); release = reader.attributes() }
                    4 -> { require(name == "module" && modules.size < 1024); module = reader.attributes(); text.clear() }
                    else -> error("Unexpected nested manifest content")
                }
                stack += name
            }
            EventType.END_ELEMENT -> {
                require(stack.isNotEmpty() && stack.last() == reader.localName)
                if (stack.last() == "module") {
                    val a = checkNotNull(module)
                    val fileName = text.toString().trim()
                    require(firmwareFileName(fileName) && fileName.endsWith(".sig"))
                    val id = a.getValue("id").lowercase().also { require(it.matches(Regex("[0-9a-f]{4}"))) }
                    val version = a.getValue("version").also { require(versionParts(it) != null) }
                    val type = a.getValue("type").also { require(it.length <= 32 && it.all { c -> c.isLetterOrDigit() || c == '_' || c == '-' }) }
                    val size = a.getValue("size").toLong().also { require(it in 0..0xffffffffL) }
                    val md5 = a.getValue("md5").lowercase().also { require(it.isEmpty() || it.matches(Regex("[0-9a-f]{32}"))) }
                    require(modules.none { it.fileName == fileName || it.id == id && it.hardwareType == type })
                    modules += FirmwareModule(id, version, type, a.getValue("group"), size, md5, fileName)
                    module = null
                }
                stack.removeAt(stack.lastIndex)
            }
            EventType.TEXT, EventType.CDSECT, EventType.IGNORABLE_WHITESPACE -> {
                if (stack.lastOrNull() == "module") text.append(reader.text) else require(reader.text.isBlank())
            }
            EventType.START_DOCUMENT, EventType.END_DOCUMENT, EventType.COMMENT -> Unit
            else -> error("Unsupported manifest event")
        }
        require(rootSeen && stack.isEmpty() && modules.isNotEmpty())
        val attributes = checkNotNull(release)
        val version = attributes.getValue("version").also { require(versionParts(it) != null && it == formal) }
        return FirmwareManifest(checkNotNull(device), version, attributes.toMap(), modules.toList(), start)
    } finally { reader.close() }
}

internal fun firmwareFileName(value: String): Boolean = value.length in 1..100 && value != "." && value != ".." &&
    value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "_.-" }
internal fun versionParts(value: String): List<Int>? {
    val parts = value.split('.')
    if (parts.size !in 3..4 || parts.any { it.isEmpty() || it.length > 5 || it.any { c -> c !in '0'..'9' } }) return null
    return parts.map { it.toInt() }
}
private fun XmlReader.attribute(name: String): String = checkNotNull(getAttributeValue("", name))
private fun XmlReader.attributes(): Map<String, String> = (0 until attributeCount).associate {
    require(getAttributeNamespace(it).isEmpty())
    getAttributeLocalName(it) to getAttributeValue(it)
}
