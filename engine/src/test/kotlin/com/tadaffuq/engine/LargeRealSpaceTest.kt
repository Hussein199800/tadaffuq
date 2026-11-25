package com.tadaffuq.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * ملفات 1.38 غ.ب و5 غ.ب إلى مجلد غير موجود، بفحص المساحة **الحقيقي** لوحدة التخزين (لا قيمة محاكاة).
 * خادم HTTP بسيط يولّد البايتات أثناء البث فلا يحتاج ذاكرة بحجم الملف؛ نتوقف بعد كتابة بيانات فعلية
 * ونتحقق من مواضعها، لأن التنزيل الكامل لا يضيف شيئاً لاختبار فحص المساحة.
 */
class LargeRealSpaceTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun byteAt(p: Long) = ((p * 2654435761L) ushr 13).toByte()

    private fun streamingServer(total: Long): Pair<ServerSocket, String> {
        val ss = ServerSocket(0)
        thread(isDaemon = true) {
            while (!ss.isClosed) {
                val c = runCatching { ss.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    runCatching {
                        c.use { sock ->
                            val inp = sock.getInputStream().bufferedReader()
                            var range: String? = null
                            while (true) { val l = inp.readLine() ?: break; if (l.isEmpty()) break; if (l.startsWith("Range:", true)) range = l.substringAfter(':').trim() }
                            val spec = range!!.removePrefix("bytes=")
                            val s = spec.substringBefore('-').toLong(); val e = minOf(spec.substringAfter('-').toLong(), total - 1)
                            val out = sock.getOutputStream().buffered(256 * 1024)
                            out.write("HTTP/1.1 206 Partial Content\r\nETag: \"big\"\r\nContent-Range: bytes $s-$e/$total\r\nContent-Length: ${e - s + 1}\r\nConnection: close\r\n\r\n".toByteArray())
                            val buf = ByteArray(64 * 1024); var p = s
                            while (p <= e) { val n = minOf(buf.size.toLong(), e - p + 1).toInt(); for (i in 0 until n) buf[i] = byteAt(p + i); out.write(buf, 0, n); p += n }
                            out.flush()
                        }
                    }
                }
            }
        }
        return ss to "http://127.0.0.1:${ss.localPort}/big.bin"
    }

    private fun startAndStop(total: Long) = runBlocking {
        val (ss, url) = streamingServer(total)
        try {
            val f = File(tmp.root, "never/created/before/${total}.part")
            assertTrue(!f.parentFile.exists())
            val written = AtomicLong()
            val job = CoroutineScope(Dispatchers.IO).async {
                HttpDownloader(HttpClients.create(), RequestSpec(url), f, DownloadConfig(connections = 4),
                    listener = object : DownloadListener { override fun onBytes(count: Long) { written.addAndGet(count) } }).run()
            }
            withTimeout(30_000) { while (written.get() < 8L * 1024 * 1024 && !job.isCompleted) delay(50) }
            if (job.isCompleted) job.await() // يرمي InsufficientSpace إن عاد الخلل
            job.cancel(); runCatching { job.await() }
            assertEquals("preallocated to full size", total, f.length())
            RandomAccessFile(f, "r").use { r -> r.seek(0); assertEquals(byteAt(0), r.readByte()); r.seek(1000); assertEquals(byteAt(1000), r.readByte()) }
        } finally { ss.close() }
    }

    @Test fun gigabyte138IntoMissingFolderWithRealSpaceCheck() = startAndStop(1_380_000_000L)
    @Test fun fiveGigabytesIntoMissingFolderWithRealSpaceCheck() = startAndStop(5L * 1024 * 1024 * 1024 + 7)
}
