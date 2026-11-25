package com.tadaffuq.engine

import com.github.junrar.Archive
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

class ArchiveException(val reason: Reason, message: String = reason.name) : IOException(message) {
    enum class Reason { UNSUPPORTED, ZIP_SLIP, TOO_LARGE, RATIO, TOO_MANY_ENTRIES, NO_SPACE, ENCRYPTED, CORRUPT }
}

data class ArchiveLimits(
    /** حد مجموع ما يُفك (يمنع ملء الذاكرة بقنبلة ضغط). */
    val maxTotalBytes: Long = 64L * 1024 * 1024 * 1024,
    /** أقصى نسبة غير مضغوط ÷ مضغوط لملف المصدر كله. */
    val maxRatio: Long = 200,
    val maxEntries: Int = 100_000,
)

data class ExtractResult(val files: Int, val bytes: Long, val skippedLinks: Int)

/**
 * فك الأرشيفات وإنشاء ZIP. حمايات: كل مسار يُتحقق أنه داخل مجلد الوجهة (Zip Slip)،
 * والروابط الرمزية تُتجاهل، ومجموع المفكوك ونسبته وعدد العناصر محدودة (قنابل الضغط)،
 * والمساحة الحرة تُفحص أثناء الكتابة. ملف موجود لا يُكتب فوقه (اسم (1)).
 */
object Archives {
    fun isArchive(name: String) = kind(name) != null

    private fun kind(name: String): String? {
        val n = name.lowercase()
        return when {
            n.endsWith(".zip") || n.endsWith(".jar") || n.endsWith(".apks") || n.endsWith(".xapk") -> "zip"
            n.endsWith(".7z") -> "7z"
            n.endsWith(".rar") -> "rar"
            n.endsWith(".tar") -> "tar"
            n.endsWith(".tar.gz") || n.endsWith(".tgz") -> "tgz"
            n.endsWith(".tar.xz") || n.endsWith(".txz") -> "txz"
            n.endsWith(".tar.bz2") || n.endsWith(".tbz2") -> "tbz"
            else -> null
        }
    }

    fun extract(archive: File, dest: File, limits: ArchiveLimits = ArchiveLimits(), usableSpace: (File) -> Long = { it.usableSpace }, onProgress: (Long) -> Unit = {}): ExtractResult {
        dest.mkdirs()
        val root = dest.canonicalFile
        val guard = Guard(archive.length().coerceAtLeast(1), root, limits, usableSpace, onProgress)
        when (kind(archive.name) ?: throw ArchiveException(ArchiveException.Reason.UNSUPPORTED)) {
            "zip" -> ZipFile.builder().setFile(archive).get().use { z ->
                for (e in z.entries) {
                    if (e.generalPurposeBit.usesEncryption()) throw ArchiveException(ArchiveException.Reason.ENCRYPTED)
                    if (e.isUnixSymlink) { guard.skipped++; continue }
                    guard.entry(e.name, e.isDirectory) { out -> z.getInputStream(e).use { guard.copy(it, out) } }
                }
            }
            "7z" -> SevenZFile.builder().setFile(archive).get().use { z ->
                var e = z.nextEntry
                while (e != null) {
                    if (!e.isAntiItem) guard.entry(e.name, e.isDirectory) { out -> z.getInputStream(e).use { guard.copy(it, out) } }
                    e = z.nextEntry
                }
            }
            "rar" -> try {
                Archive(archive).use { r ->
                    if (r.isEncrypted) throw ArchiveException(ArchiveException.Reason.ENCRYPTED)
                    r.fileHeaders.forEach { h ->
                        if (h.isEncrypted) throw ArchiveException(ArchiveException.Reason.ENCRYPTED)
                        guard.entry(h.fileName.replace('\\', '/'), h.isDirectory) { out -> r.getInputStream(h).use { guard.copy(it, out) } }
                    }
                }
            } catch (e: com.github.junrar.exception.RarException) {
                throw ArchiveException(if (e is com.github.junrar.exception.UnsupportedRarV5Exception) ArchiveException.Reason.UNSUPPORTED else ArchiveException.Reason.CORRUPT, e.message ?: "rar")
            }
            "tar" -> tarStream(archive.inputStream().buffered(), guard)
            "tgz" -> tarStream(GzipCompressorInputStream(archive.inputStream().buffered()), guard)
            "txz" -> tarStream(XZCompressorInputStream(archive.inputStream().buffered()), guard)
            "tbz" -> tarStream(BZip2CompressorInputStream(archive.inputStream().buffered()), guard)
        }
        return ExtractResult(guard.files, guard.total, guard.skipped)
    }

    private fun tarStream(input: InputStream, guard: Guard) {
        TarArchiveInputStream(input).use { t ->
            var e = t.nextEntry
            while (e != null) {
                if (e.isSymbolicLink || e.isLink) guard.skipped++
                else guard.entry(e.name, e.isDirectory) { out -> guard.copy(t, out) }
                e = t.nextEntry
            }
        }
    }

    private class Guard(val compressed: Long, val root: File, val limits: ArchiveLimits, val usableSpace: (File) -> Long, val onProgress: (Long) -> Unit) {
        var total = 0L; var files = 0; var entries = 0; var skipped = 0

        /** يمنع الخروج من مجلد الوجهة: ../ ومسارات مطلقة وأسماء أقراص. */
        fun target(name: String): File {
            val clean = name.replace('\\', '/').trimStart('/')
            val f = File(root, clean).canonicalFile
            if (f != root && !f.path.startsWith(root.path + File.separator)) throw ArchiveException(ArchiveException.Reason.ZIP_SLIP, name)
            return f
        }

        fun entry(name: String, dir: Boolean, write: (java.io.OutputStream) -> Unit) {
            if (++entries > limits.maxEntries) throw ArchiveException(ArchiveException.Reason.TOO_MANY_ENTRIES)
            val f = target(name)
            if (dir) { f.mkdirs(); return }
            f.parentFile?.mkdirs()
            val out = if (f.exists()) File(f.parentFile, FileNames.uniqueIn(f.parentFile!!, f.name)) else f
            try {
                out.outputStream().buffered().use(write)
                files++
            } catch (e: Exception) {
                out.delete(); throw e
            }
        }

        fun copy(input: InputStream, out: java.io.OutputStream) {
            val buf = ByteArray(64 * 1024)
            var sinceCheck = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n; sinceCheck += n
                if (total > limits.maxTotalBytes) throw ArchiveException(ArchiveException.Reason.TOO_LARGE)
                if (total > 10L * 1024 * 1024 && total / compressed > limits.maxRatio) throw ArchiveException(ArchiveException.Reason.RATIO)
                if (sinceCheck > 8L * 1024 * 1024) {
                    sinceCheck = 0
                    if (usableSpace(root) < 16L * 1024 * 1024) throw ArchiveException(ArchiveException.Reason.NO_SPACE)
                }
                out.write(buf, 0, n)
                onProgress(total)
            }
        }
    }

    /** ينشئ ZIP من ملفات ومجلدات (المجلدات بمحتواها). */
    fun createZip(sources: List<File>, out: File) {
        ZipArchiveOutputStream(out).use { zip ->
            fun add(f: File, base: String) {
                val name = if (base.isEmpty()) f.name else "$base/${f.name}"
                if (f.isDirectory) f.listFiles()?.sortedBy { it.name }?.forEach { add(it, name) }
                else {
                    zip.putArchiveEntry(ZipArchiveEntry(f, name))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeArchiveEntry()
                }
            }
            sources.forEach { add(it, "") }
        }
    }

    @Suppress("unused") private fun ArchiveInputStream<*>.entries(): Sequence<ArchiveEntry> = generateSequence { nextEntry }
}
