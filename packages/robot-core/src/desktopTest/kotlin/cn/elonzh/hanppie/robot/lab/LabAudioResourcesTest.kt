package cn.elonzh.hanppie.robot.lab

import cn.elonzh.hanppie.robot.protocol.hex
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.*

@OptIn(ExperimentalEncodingApi::class)
class LabAudioResourcesTest {
    private fun clip(
        id: Int = 0,
        name: String = "beep",
        durationMillis: Long = 1_500,
        packets: ByteArray = byteArrayOf(1, 2, 3, 4),
    ) = LabAudioClip(id, name, durationMillis, packets)

    private fun parse(bytes: ByteArray) = DocumentBuilderFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }.newDocumentBuilder().parse(ByteArrayInputStream(bytes))

    @Test fun audioNodeCarriesTheClientFieldsAndTheBase64Body() {
        val clip = clip(packets = ByteArray(600) { it.toByte() })
        val document = parse(labAudioListXml(listOf(clip)).encodeToByteArray())
        val node = document.getElementsByTagName("audio").item(0)
        assertEquals("0", node.attributes.getNamedItem("id").nodeValue)
        assertEquals("beep", node.attributes.getNamedItem("name").nodeValue)
        assertEquals("opus", node.attributes.getNamedItem("type").nodeValue)
        assertEquals("2", node.attributes.getNamedItem("duration").nodeValue)
        assertEquals("true", node.attributes.getNamedItem("modify").nodeValue)
        val body = Base64.Default.encode(clip.packets)
        val expected = MessageDigest.getInstance("MD5").digest(body.encodeToByteArray()).hex().take(8)
        assertEquals(expected, node.attributes.getNamedItem("md5").nodeValue)
        assertEquals(body, document.getElementsByTagName("audio_data").item(0).textContent)
    }

    @Test fun emptyAudioListKeepsTheClientShape() {
        assertEquals(LabAudioClip.EMPTY_AUDIO_LIST, labAudioListXml(emptyList()))
    }

    @Test fun programEmbedsAudioAndKeepsPythonIntact() {
        val source = "def start():\n    media_ctrl.play_sound(rm_define.media_custom_audio_1)\n"
        val program = LabProgram(source, "a".repeat(32), "b".repeat(16))
        val document = parse(program.dsp("2026/09/16", labAudioListXml(listOf(clip(id = 1)))))
        assertEquals(source, document.getElementsByTagName("python_code").item(0).textContent)
        assertEquals("1", document.getElementsByTagName("audio").item(0).attributes.getNamedItem("id").nodeValue)
    }

    @Test fun audioNodesAreEscapedAndOrderedByResourceId() {
        val clips = listOf(clip(id = 2, name = "a & <b> \"c\""), clip(id = 0, name = "zero"))
        val document = parse(labAudioListXml(clips).encodeToByteArray())
        val nodes = document.getElementsByTagName("audio")
        assertEquals(listOf("0", "2"), (0 until nodes.length).map { nodes.item(it).attributes.getNamedItem("id").nodeValue })
        assertEquals("a & <b> \"c\"", nodes.item(1).attributes.getNamedItem("name").nodeValue)
    }

    @Test fun resourceIdMapsToTheLabConstant() {
        assertEquals("rm_define.media_custom_audio_3", clip(id = 3).soundConstant)
        assertEquals(800, clip(packets = ByteArray(600)).encodedBytes)
        assertEquals(800, LabAudioClip.totalEncodedBytes(listOf(clip(packets = ByteArray(600)))))
    }

    @Test fun rejectsUnusableResources() {
        assertFailsWith<IllegalArgumentException> { clip(id = 10) }
        assertFailsWith<IllegalArgumentException> { clip(id = -1) }
        assertFailsWith<IllegalArgumentException> { clip(name = " ") }
        assertFailsWith<IllegalArgumentException> { clip(name = "n".repeat(65)) }
        assertFailsWith<IllegalArgumentException> { clip(durationMillis = 0) }
        assertFailsWith<IllegalArgumentException> { labAudioListXml(listOf(clip(id = 1), clip(id = 1))) }
        assertFailsWith<IllegalArgumentException> { labAudioListXml((0..10).map { clip(id = it % LabAudioClip.MAX_CLIPS) }) }
    }

    @Test fun cachedAudioSlotOmitsAudioDataAndMarksModifyFalse() {
        val clip = clip(id = 0, packets = ByteArray(600) { it.toByte() })
        val digest = labAudioDigest(clip.packets)
        val xml = labAudioListXml(listOf(clip)) { slotId, md5 -> slotId == 0 && md5 == digest }
        val document = parse(xml.encodeToByteArray())
        val node = document.getElementsByTagName("audio").item(0)
        assertEquals("false", node.attributes.getNamedItem("modify").nodeValue)
        assertEquals(0, document.getElementsByTagName("audio_data").length)
    }
}
