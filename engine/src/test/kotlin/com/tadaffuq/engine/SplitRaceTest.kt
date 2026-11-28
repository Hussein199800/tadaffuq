package com.tadaffuq.engine

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * 1.4.0 (مراجعة Codex، مثبتة هنا): «الخادم لا يدعم تنزيل الأجزاء» قرب النهاية. طلب جزء يُرسل بنهايته،
 * وقبل وصول الرد يقسم اتصال فارغ الجزء نفسه فتقصر نهايته في الذاكرة؛ كان الرد الصحيح يُقارن بالنهاية الجديدة فيُرفض.
 */
class SplitRaceTest {
    @Test fun lateHeadersDuringSplitAreAccepted() = runBlocking {
        val data = Random(93).nextBytes(4 * 1024 * 1024)
        val s = RangeServer(data).apply { lateFrom = 2L * 1024 * 1024; lateHeaders.set(1); lateMs = 2_000 }
        try {
            val f = File.createTempFile("race", ".part").apply { deleteOnExit() }
            withTimeout(30_000) { HttpDownloader(HttpClients.create(), RequestSpec(s.url()), f, DownloadConfig(connections = 2, autoTune = false)).run() }
            assertArrayEquals(data, f.readBytes())
        } finally { s.close() }
    }
}

/** مراجعة Codex: خادم بلا مدى يعلن 8 بايت في الفحص ثم يعيد 24 في التنزيل؛ كان يُعلن نجاح ملف مقطوع. */
class ChangedLengthTest {
    private fun server(lengthHeader: Boolean) = okhttp3.mockwebserver.MockWebServer().apply {
        val n = java.util.concurrent.atomic.AtomicInteger()
        dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                val first = n.getAndIncrement() == 0
                val r = okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "application/octet-stream")
                // الفحص يعلن 8 بايت دائماً؛ التنزيل يعيد 24 بطول معلن أو مقسّماً بلا طول
                if (first) return r.setBody(okio.Buffer().write(ByteArray(8) { 1 }))
                val body = ByteArray(24) { 2 }
                return if (lengthHeader) r.setBody(okio.Buffer().write(body)) else r.setChunkedBody(okio.Buffer().write(body), 4)
            }
        }
        start()
    }

    private fun check(lengthHeader: Boolean) = runBlocking {
        val s = server(lengthHeader)
        try {
            val f = File.createTempFile("chg", ".part").apply { deleteOnExit() }
            val r = runCatching { HttpDownloader(HttpClients.create(), RequestSpec(s.url("/f.bin").toString()), f, DownloadConfig()).run() }
            val e = r.exceptionOrNull()
            org.junit.Assert.assertTrue("truncated file accepted: ${f.length()} bytes, $e", e is DownloadException && e.error == DownloadError.ChangedOnServer)
        } finally { s.shutdown() }
    }

    @Test fun longerBodyWithLength() = check(true)
    @Test fun longerChunkedBody() = check(false)
}

/** مراجعة Codex: استئناف FTP/SFTP فوق ملف محلي أقصر من التقدم المحفوظ كان يترك فجوة أصفار ويجتاز فحص الطول. */
class RemoteResumeTruncatedTest {
    @Test fun shortLocalFileRestartsInsteadOfLeavingAGap() = runBlocking {
        val data = ByteArray(10) { (it + 1).toByte() }
        val probe = ProbeResult("sftp://h/f", 10, true, null, "t1", "f", null)
        val opener = object : StreamOpener {
            override suspend fun probe() = probe
            override suspend fun open(offset: Long): Pair<java.io.InputStream, () -> Unit> =
                java.io.ByteArrayInputStream(data, offset.toInt(), data.size - offset.toInt()) to {}
        }
        val f = File.createTempFile("ftp", ".part").apply { deleteOnExit(); writeBytes(byteArrayOf(1, 2)) }
        val saved = ResumeState(probe, listOf(SegmentState(0, 0, 9, 6)))
        SingleStreamDownload(opener, f, DownloadConfig(), saved, RateLimiter(), RateLimiter(), object : DownloadListener {}).run()
        assertArrayEquals(data, f.readBytes())
    }
}
