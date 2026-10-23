package cn.elonzh.hanppie.robot.maintenance.firmware

import cn.elonzh.hanppie.robot.protocol.hex
import cn.elonzh.hanppie.robot.session.robotMd5
import kotlinx.coroutines.*
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.test.*

class FirmwarePackageTest {
    @Test fun signedManifestRetainsHardwareVariantsAndReleasePolicyAsData() {
        val xml = manifest("<module id=\"0400\" version=\"00.01.12.69\" type=\"\" group=\"\" size=\"3\" md5=\"${digest()}\">gimbal.sig</module>" +
            "<module id=\"0400\" version=\"00.02.20.65\" type=\"hw01\" group=\"\" size=\"3\" md5=\"${digest()}\">gimbal-hw01.sig</module>")
        val m = FirmwareManifest.parse(xml)
        assertEquals("xw607", m.deviceId); assertEquals("00.06.0521", m.version); assertEquals(519, m.signaturePrefixBytes)
        assertEquals(listOf("", "hw01"), m.modules.map { it.hardwareType })
        assertEquals("cn:0", m.releaseAttributes["antirollback_ext"])
        assertEquals("2024/02/27", m.releaseAttributes["expire"])
    }

    @Test fun malformedDuplicateAndAmbiguousManifestsAreRejected() {
        val good = manifest().decodeToString()
        val bad = listOf("", "not xml", good.replace("md5=", "bad="), good.replace("module.sig", "../module.sig"),
            good.replace("00.06.0521\" antirollback", "00.x.0521\" antirollback"),
            good.replace("size=\"3\"", "size=\"-1\""), good.replace("id=\"0306\"", "id=\"xyz\""),
            good.replace("</release>", defaultModule() + "</release>"), good.replace("</device>", "</device><device id=\"other\"/>"),
            good.replace("<module", "<unexpected><module").replace("</module>", "</module></unexpected>"), good.replace("</dji>", ""),
            good.replace("</dji>", "</dji><dji/>"))
        for (b in bad) assertEquals(FirmwarePackageProblem.INVALID_MANIFEST,
            assertFailsWith<FirmwarePackageException> { FirmwareManifest.parse(b.encodeToByteArray()) }.problem)
    }

    @Test fun entityDeclarationsAndMalformedUtf8NeverReachMetadata() {
        for (xml in listOf(manifest().decodeToString().replace("<device", "<!DOCTYPE device [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><device"),
            manifest().decodeToString().replace("module.sig", "&undefined;")))
            assertFailsWith<FirmwarePackageException> { FirmwareManifest.parse(xml.encodeToByteArray()) }
        val bytes = manifest().apply { this[600] = 255.toByte() }
        assertFailsWith<FirmwarePackageException> { FirmwareManifest.parse(bytes) }
    }

    @Test fun zeroSizeOrEmptyDigestInDeviceMetadataDoesNotBecomeAValidPackage(): Unit = runBlocking {
        val cfg = manifest(defaultModule(size = 0, md5 = ""))
        assertEquals(0L, FirmwareManifest.parse(cfg).modules.single().size)
        packageFailure(tar(listOf(Entry("00.06.0521.cfg.sig", cfg), Entry("module.sig", "abc".encodeToByteArray()))), FirmwarePackageProblem.SIZE_MISMATCH)
    }

    @Test fun archiveChecksEveryPayloadAndHashesTheOriginalBytesWithoutExtracting(): Unit = runBlocking {
        val archive = tar(listOf(Entry("module.sig", "abc".encodeToByteArray()), Entry("00.06.0521.cfg.sig", manifest())))
        val counts = mutableListOf<Long>()
        val result = inspect(archive) { counts += it }
        assertEquals("00.06.0521", result.manifest.version)
        assertEquals(archive.size.toLong(), result.archiveSize); assertEquals(robotMd5(archive).hex(), result.archiveMd5)
        assertEquals(archive.size.toLong(), counts.last()); assertTrue(counts.zipWithNext().all { (a, b) -> b > a })
    }

    @Test fun localPaxTimestampsAreAcceptedButPathAndSizeOverridesAreNot(): Unit = runBlocking {
        val ordinary = listOf(Entry("00.06.0521.cfg.sig", manifest()), Entry("module.sig", "abc".encodeToByteArray()))
        assertEquals("xw607", inspect(tar(listOf(Entry("././@PaxHeader", pax("mtime", "123.25"), 'x')) + ordinary)).manifest.deviceId)
        for (key in listOf("path", "size", "GNU.sparse.size")) packageFailure(tar(listOf(Entry("././@PaxHeader", pax(key, "other"), 'x')) + ordinary), FirmwarePackageProblem.UNSUPPORTED_ARCHIVE)
        packageFailure(tar(listOf(Entry("././@PaxHeader", "wrong".encodeToByteArray(), 'x')) + ordinary), FirmwarePackageProblem.INVALID_ARCHIVE)
    }

    @Test fun missingExtraDuplicateAndLinkedEntriesCannotBeUsed(): Unit = runBlocking {
        val cfg = Entry("00.06.0521.cfg.sig", manifest()); val module = Entry("module.sig", "abc".encodeToByteArray())
        packageFailure(tar(listOf(cfg)), FirmwarePackageProblem.MISSING_FILE)
        packageFailure(tar(listOf(module)), FirmwarePackageProblem.MISSING_FILE)
        packageFailure(tar(listOf(cfg, module, Entry("extra.sig", byteArrayOf(1)))), FirmwarePackageProblem.UNEXPECTED_FILE)
        packageFailure(tar(listOf(cfg, module, module)), FirmwarePackageProblem.UNEXPECTED_FILE)
        packageFailure(tar(listOf(cfg, module.copy(name = "../module.sig"))), FirmwarePackageProblem.UNEXPECTED_FILE)
        packageFailure(tar(listOf(cfg, module.copy(type = '2'))), FirmwarePackageProblem.UNSUPPORTED_ARCHIVE)
    }

    @Test fun byteCountsDigestAndSignatureContainerMustMatch(): Unit = runBlocking {
        val module = Entry("module.sig", "abc".encodeToByteArray())
        packageFailure(tar(listOf(Entry("00.06.0521.cfg.sig", manifest(defaultModule(size = 4))), module)), FirmwarePackageProblem.SIZE_MISMATCH)
        packageFailure(tar(listOf(Entry("00.06.0521.cfg.sig", manifest(defaultModule(md5 = "0".repeat(32)))), module)), FirmwarePackageProblem.CHECKSUM_MISMATCH)
        packageFailure(tar(listOf(Entry("00.06.0521.cfg.sig", manifest(prefix = 0)), module)), FirmwarePackageProblem.INVALID_MANIFEST)
        packageFailure(tar(listOf(Entry("other.cfg.sig", manifest()), module)), FirmwarePackageProblem.INVALID_MANIFEST)
    }

    @Test fun invalidHeadersTruncationAndDataAfterTheTarTerminatorAreRejected(): Unit = runBlocking {
        val archive = tar(listOf(Entry("00.06.0521.cfg.sig", manifest()), Entry("module.sig", "abc".encodeToByteArray())))
        packageFailure(archive.copyOf().apply { this[0] = 'x'.code.toByte() }, FirmwarePackageProblem.INVALID_ARCHIVE)
        packageFailure(archive.copyOf().apply { this[lastIndex] = 1 }, FirmwarePackageProblem.INVALID_ARCHIVE)
        packageFailure(archive.copyOf(archive.size - 1), FirmwarePackageProblem.INVALID_ARCHIVE)
        val error = assertFailsWith<FirmwarePackageException> { FirmwareArchive.inspect(Buffer().apply { write(archive.copyOf(archive.size - 512)) }, archive.size.toLong()) }
        assertEquals(FirmwarePackageProblem.SIZE_MISMATCH, error.problem)
    }

    @Test fun cancellationStopsReadingBeforeAResultIsPublished(): Unit = runBlocking {
        val archive = tar(listOf(Entry("00.06.0521.cfg.sig", manifest()), Entry("module.sig", "abc".encodeToByteArray())))
        var count = 0L
        assertFailsWith<CancellationException> { inspect(archive) { count = it; throw CancellationException() } }
        assertEquals(512L, count)
    }
}

private fun digest() = "900150983cd24fb0d6963f7d28e17f72"
private fun defaultModule(size: Int = 3, md5: String = digest()) =
    "<module id=\"0306\" version=\"01.03.32.00\" type=\"hw02\" group=\"\" size=\"$size\" md5=\"$md5\">module.sig</module>"
private fun manifest(modules: String = defaultModule(), prefix: Int = 519): ByteArray = ByteArray(prefix) +
    "<dji><device id=\"xw607\"><firmware formal=\"00.06.0521\"><release version=\"00.06.0521\" antirollback=\"0\" antirollback_ext=\"cn:0\" expire=\"2024/02/27\">$modules</release></firmware></device></dji>\u0000\u0000\u0000".encodeToByteArray()
private data class Entry(val name: String, val data: ByteArray, val type: Char = '0')
private fun tar(entries: List<Entry>): ByteArray = Buffer().apply {
    for (entry in entries) {
        val h = ByteArray(512)
        entry.name.encodeToByteArray().copyInto(h)
        "0000644\u0000".encodeToByteArray().copyInto(h, 100)
        (entry.data.size.toString(8).padStart(11, '0') + '\u0000').encodeToByteArray().copyInto(h, 124)
        h.fill(32, 148, 156); h[156] = entry.type.code.toByte()
        "ustar\u000000".encodeToByteArray().copyInto(h, 257)
        (h.sumOf { it.toInt() and 255 }.toString(8).padStart(6, '0') + "\u0000 ").encodeToByteArray().copyInto(h, 148)
        write(h); write(entry.data); write(ByteArray((512 - entry.data.size % 512) % 512))
    }
    write(ByteArray(1024))
}.let { it.readByteArray() }
private fun pax(key: String, value: String): ByteArray {
    val body = "$key=$value\n"; var count = body.length + 3
    while (count != body.length + count.toString().length + 1) count = body.length + count.toString().length + 1
    return "$count $body".encodeToByteArray()
}
private suspend fun inspect(bytes: ByteArray, progress: (Long) -> Unit = {}) = FirmwareArchive.inspect(Buffer().apply { write(bytes) }, bytes.size.toLong(), progress)
private suspend fun packageFailure(bytes: ByteArray, problem: FirmwarePackageProblem) {
    assertEquals(problem, assertFailsWith<FirmwarePackageException> { inspect(bytes) }.problem)
}

internal data class FirmwarePackageFixture(val archive: ByteArray, val inspection: FirmwarePackageInspection, val configuration: ByteArray)
internal suspend fun firmwarePackageFixture(): FirmwarePackageFixture {
    val configuration = manifest()
    val archive = tar(listOf(Entry("00.06.0521.cfg.sig", configuration), Entry("module.sig", "abc".encodeToByteArray())))
    return FirmwarePackageFixture(archive, inspect(archive), configuration)
}
