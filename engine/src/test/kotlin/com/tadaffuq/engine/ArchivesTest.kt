package com.tadaffuq.engine

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

class ArchivesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun zip(entries: Map<String, ByteArray>): File = tmp.newFile("x${Random.nextInt()}.zip").also { f ->
        ZipOutputStream(f.outputStream()).use { z -> entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
    }

    @Test fun createAndExtractZipRoundTrip() {
        val src = tmp.newFolder("src")
        val a = Random(1).nextBytes(300_000).also { File(src, "a.bin").writeBytes(it) }
        File(src, "sub").mkdirs(); File(src, "sub/ملف عربي.txt").writeText("سلام")
        val out = File(tmp.root, "out.zip")
        Archives.createZip(listOf(src), out)
        val dest = tmp.newFolder("dest")
        val r = Archives.extract(out, dest)
        assertEquals(2, r.files)
        assertArrayEquals(a, File(dest, "src/a.bin").readBytes())
        assertEquals("سلام", File(dest, "src/sub/ملف عربي.txt").readText())
    }

    @Test fun zipSlipIsBlocked() {
        val evil = zip(mapOf("../../escape.txt" to "x".toByteArray()))
        val dest = tmp.newFolder("d")
        try { Archives.extract(evil, dest); fail() } catch (e: ArchiveException) { assertEquals(ArchiveException.Reason.ZIP_SLIP, e.reason) }
        assertFalse(File(tmp.root.parentFile, "escape.txt").exists())
        val abs = zip(mapOf("/etc/passwd2" to "x".toByteArray()))
        Archives.extract(abs, dest) // المسار المطلق يُعامل نسبياً داخل الوجهة
        assertTrue(File(dest, "etc/passwd2").exists())
    }

    @Test fun compressionBombIsStopped() {
        val bomb = zip(mapOf("zeros.bin" to ByteArray(60 * 1024 * 1024))) // 60MB أصفار ← بضع عشرات ك.ب
        try { Archives.extract(bomb, tmp.newFolder("b")); fail() } catch (e: ArchiveException) { assertEquals(ArchiveException.Reason.RATIO, e.reason) }
        val big = zip(mapOf("r.bin" to Random(2).nextBytes(2_000_000)))
        try { Archives.extract(big, tmp.newFolder("c"), ArchiveLimits(maxTotalBytes = 1_000_000)); fail() } catch (e: ArchiveException) { assertEquals(ArchiveException.Reason.TOO_LARGE, e.reason) }
    }

    @Test fun existingFilesAreNotOverwritten() {
        val dest = tmp.newFolder("e"); File(dest, "a.txt").writeText("old")
        Archives.extract(zip(mapOf("a.txt" to "new".toByteArray())), dest)
        assertEquals("old", File(dest, "a.txt").readText())
        assertEquals("new", File(dest, "a (1).txt").readText())
    }

    @Test fun sevenZipAndTarGz() {
        val data = Random(3).nextBytes(100_000)
        val sz = File(tmp.root, "t.7z")
        SevenZOutputFile(sz).use { o -> o.putArchiveEntry(SevenZArchiveEntry().apply { name = "d/x.bin"; size = data.size.toLong() }); o.write(data); o.closeArchiveEntry() }
        val d1 = tmp.newFolder("7")
        Archives.extract(sz, d1)
        assertArrayEquals(data, File(d1, "d/x.bin").readBytes())

        val tgz = File(tmp.root, "t.tar.gz")
        TarArchiveOutputStream(GzipCompressorOutputStream(tgz.outputStream())).use { t ->
            t.putArchiveEntry(TarArchiveEntry("y.bin").apply { size = data.size.toLong() }); t.write(data); t.closeArchiveEntry()
            t.putArchiveEntry(TarArchiveEntry("link", TarArchiveEntry.LF_SYMLINK).apply { linkName = "/etc/passwd" }); t.closeArchiveEntry()
        }
        val d2 = tmp.newFolder("tg")
        val r = Archives.extract(tgz, d2)
        assertArrayEquals(data, File(d2, "y.bin").readBytes())
        assertEquals("symlinks are skipped", 1, r.skippedLinks)
        assertFalse(File(d2, "link").exists())
    }

    @Test fun unsupportedFormatIsReported() {
        try { Archives.extract(tmp.newFile("a.txt"), tmp.newFolder("u")); fail() } catch (e: ArchiveException) { assertEquals(ArchiveException.Reason.UNSUPPORTED, e.reason) }
    }
}
