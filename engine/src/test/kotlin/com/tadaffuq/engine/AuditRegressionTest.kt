package com.tadaffuq.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** اختبارات انحدار لعيوب P0 في وثيقة التدقيق؛ كُتبت قبل الإصلاح وفشلت عليه. */
class AuditRegressionTest {
    @get:Rule val tmp = TemporaryFolder()
    private val client = HttpClients.create(readTimeoutSec = 30)
    private val data = Random(77).nextBytes(1_500_000)
    private var server: RangeServer? = null
    private var raw: MockWebServer? = null
    @After fun tearDown() { runCatching { server?.close() }; runCatching { raw?.shutdown() } }

    private fun sha(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** P0-01: مجلد الوجهة غير موجود بعد → usableSpace يعيد 0 → رفض خاطئ لملف صالح. */
    @Test fun p0_01_missingFolderIsNotTreatedAsFullDisk() = runBlocking {
        server = RangeServer(data)
        val f = File(tmp.root, "new/deep/folder/a.bin.part") // لا يوجد أي مجلد منها
        HttpDownloader(client, RequestSpec(server!!.url()), f, DownloadConfig(connections = 2)).run()
        assertEquals(sha(data), sha(f.readBytes()))
    }

    /** P0-01: فحص المساحة الحقيقي يبقى فعالاً ويبيّن المطلوب والمتاح. */
    @Test fun p0_01_realShortageStillReported() = runBlocking {
        server = RangeServer(data)
        val f = File(tmp.root, "x/a.part")
        try {
            HttpDownloader(client, RequestSpec(server!!.url()), f, usableSpace = { 1000 }).run(); fail()
        } catch (e: DownloadException) {
            val err = e.error as DownloadError.InsufficientSpace
            assertEquals(data.size.toLong(), err.needed); assertEquals(1000L, err.available)
        }
    }

    /** P0-03: ETag قوي كان موجوداً ثم اختفى → لا دليل على أن المحتوى نفسه → بداية آمنة. */
    @Test fun p0_03_vanishedIdentifierForcesRestart() = runBlocking {
        server = RangeServer(data)
        val probe = Prober(client).probe(RequestSpec(server!!.url()))
        val f = tmp.newFile("v.part").also { it.writeBytes(ByteArray(data.size)) } // «تقدم» قديم بمحتوى آخر
        val saved = ResumeState(probe, listOf(SegmentState(0, 0, data.size - 1L, 700_000)))
        server!!.etag = null // الخادم لم يعد يعلن ETag، وLast-Modified ما زال
        var restarted = false
        HttpDownloader(client, RequestSpec(server!!.url()), f, DownloadConfig(connections = 2), saved,
            listener = object : DownloadListener { override fun onStart(probe: ProbeResult, restarted0: Boolean) { restarted = restarted0 } }).run()
        assertTrue(restarted)
        assertEquals(sha(data), sha(f.readBytes()))
    }

    /** P0-04: ملف جزئي مقتطع (أقصر من الحجم) لا يُقبل لمجرد اتساق جدول الأجزاء. */
    @Test fun p0_04_truncatedPartialIsNotTrusted() = runBlocking {
        server = RangeServer(data)
        val probe = Prober(client).probe(RequestSpec(server!!.url()))
        val f = tmp.newFile("t.part") // طوله صفر، بينما الجدول يقول نصفه منجز
        val half = data.size / 2L
        val saved = ResumeState(probe, listOf(SegmentState(0, 0, half - 1, half), SegmentState(1, half, data.size - 1L, 0)))
        HttpDownloader(client, RequestSpec(server!!.url()), f, DownloadConfig(connections = 2), saved).run()
        assertEquals("no zero-filled hole", sha(data), sha(f.readBytes()))
    }

    /** P0-06: الإيقاف أثناء بث متوقف ينتهي فوراً، لا بعد مهلة القراءة (30 ث). */
    @Test fun p0_06_cancelDuringStalledBodyIsImmediate() = runBlocking {
        raw = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val r = request.getHeader("Range")!!.removePrefix("bytes=")
                    val s = r.substringBefore('-').toLong(); val e = r.substringAfter('-').toLong()
                    return MockResponse().setResponseCode(206).setHeader("ETag", "\"s\"")
                        .setHeader("Content-Range", "bytes $s-$e/${data.size}")
                        .setBody(Buffer().write(data, s.toInt(), (e - s + 1).toInt()))
                        .apply { if (e > 0) throttleBody(1, 20, TimeUnit.SECONDS) } // بايت كل 20 ثانية
                }
            }
            start()
        }
        val job = CoroutineScope(Dispatchers.IO).async {
            HttpDownloader(client, RequestSpec(raw!!.url("/s").toString()), tmp.newFile("s.part"), DownloadConfig(connections = 2)).run()
        }
        delay(1500)
        val t0 = System.nanoTime()
        job.cancel()
        withTimeout(5_000) { runCatching { job.await() } }
        val secs = (System.nanoTime() - t0) / 1e9
        assertTrue("cancel took $secs s", secs < 3)
    }

    private fun rawServer(handler: (RecordedRequest) -> MockResponse) {
        raw = MockWebServer().apply { dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = handler(request) }; start() }
    }

    /** P0-07: 416 لملف غير فارغ لا يُفسَّر ملفاً فارغاً؛ يُنزَّل باتصال واحد كاملاً. */
    @Test fun p0_07_416OnNonEmptyFileIsNotEmpty() = runBlocking {
        rawServer { req ->
            if (req.getHeader("Range") != null) MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */${data.size}")
            else MockResponse().setBody(Buffer().write(data))
        }
        val f = tmp.newFile("416.part")
        val p = HttpDownloader(client, RequestSpec(raw!!.url("/f").toString()), f).run()
        assertEquals(data.size.toLong(), p.totalBytes)
        assertFalse(p.acceptRanges)
        assertEquals(sha(data), sha(f.readBytes()))
    }

    // P0-07: 416 مع «bytes */0» = ملف فارغ فعلاً.
    @Test fun p0_07_emptyFile() = runBlocking {
        rawServer { req ->
            if (req.getHeader("Range") != null) MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */0")
            else MockResponse().setBody("")
        }
        val f = tmp.newFile("empty.part")
        HttpDownloader(client, RequestSpec(raw!!.url("/e").toString()), f).run()
        assertEquals(0L, f.length())
    }

    /** P0-07: مدى يتجاوز المطلوب أو طول جسم لا يطابق Content-Range يُرفض ولا يُكتب. */
    @Test fun p0_07_rangeBeyondRequestedIsRejected() = runBlocking {
        rawServer { req ->
            val r = req.getHeader("Range")!!.removePrefix("bytes=")
            val s = r.substringBefore('-').toLong(); val e = r.substringAfter('-').toLong()
            if (s == 0L && e == 0L) MockResponse().setResponseCode(206).setHeader("ETag", "\"b\"").setHeader("Content-Range", "bytes 0-0/${data.size}").setBody(Buffer().write(data, 0, 1))
            else {
                val lie = minOf(e + 5000, data.size - 1L) // يدّعي مدى أطول من المطلوب
                MockResponse().setResponseCode(206).setHeader("ETag", "\"b\"").setHeader("Content-Range", "bytes $s-$lie/${data.size}")
                    .setBody(Buffer().write(data, s.toInt(), (lie - s + 1).toInt()))
            }
        }
        try {
            HttpDownloader(client, RequestSpec(raw!!.url("/b").toString()), tmp.newFile("b.part"), DownloadConfig(connections = 4)).run()
            fail("bad range accepted")
        } catch (e: DownloadException) {
            assertEquals(DownloadError.RangeNotSupported, e.error)
        }
    }

    // P0-07: 206 بلا إجمالي (bytes 0-0/*) → لا تقسيم؛ تنزيل كامل سليم.
    @Test fun p0_07_206WithoutTotal() = runBlocking {
        rawServer { req ->
            if (req.getHeader("Range") == "bytes=0-0") MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-0/*").setBody(Buffer().write(data, 0, 1))
            else MockResponse().setBody(Buffer().write(data))
        }
        val f = tmp.newFile("nt.part")
        val p = HttpDownloader(client, RequestSpec(raw!!.url("/n").toString()), f).run()
        assertFalse(p.acceptRanges)
        assertEquals(sha(data), sha(f.readBytes()))
    }
}
