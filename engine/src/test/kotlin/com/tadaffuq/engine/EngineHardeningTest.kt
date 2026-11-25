package com.tadaffuq.engine

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

class EngineHardeningTest {
    @get:Rule val tmp = TemporaryFolder()
    private val client = HttpClients.create(readTimeoutSec = 10)
    private val server = MockWebServer()
    @After fun tearDown() = server.shutdown()

    /** بايت حتمي لأي موضع: نولّد المحتوى عند الطلب بلا ذاكرة لملف بحجم 5 غ.ب. */
    private fun byteAt(pos: Long): Byte = ((pos * 2654435761L) ushr 13).toByte()

    private fun synthetic(total: Long, onRange: (Long, Long) -> MockResponse? = { _, _ -> null }) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val spec = request.getHeader("Range")!!.removePrefix("bytes=")
            val start = spec.substringBefore('-').toLong()
            val end = minOf(spec.substringAfter('-').toLong(), total - 1)
            onRange(start, end)?.let { return it }
            val buf = Buffer()
            var p = start
            while (p <= end) { buf.writeByte(byteAt(p).toInt()); p++ }
            return MockResponse().setResponseCode(206).setHeader("ETag", "\"big\"")
                .setHeader("Content-Range", "bytes $start-$end/$total").setBody(buf)
        }
    }

    /** ملف أكبر من 4 غ.ب: نستأنف من حالة محفوظة لا يبقى فيها إلا ما بعد حد 2^32، ونتحقق من البايتات هناك. */
    @Test fun resumesBeyondFourGigabytes() = runBlocking {
        val total = 5L * 1024 * 1024 * 1024 + 123
        server.dispatcher = synthetic(total)
        server.start()
        val url = server.url("/big.iso").toString()
        val f = tmp.newFile("big.part")
        RandomAccessFile(f, "rw").use { it.setLength(total) } // ملف متناثر لا يستهلك مساحة فعلية
        val probe = Prober(client).probe(RequestSpec(url))
        assertEquals(total, probe.totalBytes)
        val four = 1L shl 32
        val saved = ResumeState(probe, listOf(
            SegmentState(0, 0, four - 1, four),                       // مكتمل
            SegmentState(1, four, four + 3_000_000, 1_000_000),       // يعبر 4GB، نصفه مكتمل
            SegmentState(2, four + 3_000_001, total - 1, total - 1 - (four + 3_000_001) + 1 - 2_000_000),
        ))
        assertTrue(SegmentPlanner.isConsistent(saved.segments, total))
        val fetched = java.util.concurrent.atomic.AtomicLong()
        HttpDownloader(client, RequestSpec(url), f, DownloadConfig(connections = 2), saved,
            listener = object : DownloadListener { override fun onBytes(count: Long) { fetched.addAndGet(count) } },
            usableSpace = { Long.MAX_VALUE }).run()
        assertEquals(total, f.length())
        assertEquals(2_000_001L + 2_000_000L, fetched.get())
        RandomAccessFile(f, "r").use { raf ->
            for (pos in listOf(four + 1_000_000, four + 2_500_000, four + 3_000_000, total - 2_000_000, total - 1)) {
                raf.seek(pos); assertEquals("byte at $pos", byteAt(pos), raf.readByte())
            }
        }
    }

    /** 429 يخفّض عدد الاتصالات المتزامنة فعلياً، ويكتمل الملف سليماً. */
    @Test fun tooManyRequestsReducesConcurrency() = runBlocking {
        val total = 4L * 1024 * 1024
        val content = ByteArray(total.toInt()) { byteAt(it.toLong()) }
        val inFlight = AtomicInteger()
        val maxAfter = AtomicInteger()
        val busyServed = AtomicInteger()
        val limit = 2
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val spec = request.getHeader("Range")!!.removePrefix("bytes=")
                val start = spec.substringBefore('-').toLong()
                val end = minOf(spec.substringAfter('-').toLong(), total - 1)
                if (start > 0 || end > 0) {
                    val now = inFlight.incrementAndGet()
                    try {
                        if (now > limit) { busyServed.incrementAndGet(); return MockResponse().setResponseCode(429).setHeader("Retry-After", "1") }
                        if (busyServed.get() > 0) maxAfter.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                        Thread.sleep(30)
                    } finally { inFlight.decrementAndGet() }
                }
                return MockResponse().setResponseCode(206).setHeader("ETag", "\"x\"")
                    .setHeader("Content-Range", "bytes $start-$end/$total")
                    .setBody(Buffer().write(content, start.toInt(), (end - start + 1).toInt()))
                    .throttleBody(64 * 1024, 20, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
        }
        server.start()
        val f = tmp.newFile("c.part")
        HttpDownloader(client, RequestSpec(server.url("/c").toString()), f, DownloadConfig(connections = 8, baseRetryDelayMs = 20, minSplitBytes = 64 * 1024)).run()
        assertTrue("server pushed back", busyServed.get() > 0)
        assertArrayEquals(content, f.readBytes())
    }

    @Test fun dnsFailureIsClassified() = runBlocking {
        try {
            Prober(client).probe(RequestSpec("http://nonexistent.invalid/x"))
            throw AssertionError("expected failure")
        } catch (e: DownloadException) {
            assertEquals(DownloadError.Network(NetFail.DNS), e.error)
        }
    }
}

class MirrorTest {
    @get:Rule val tmp = TemporaryFolder()
    private val client = HttpClients.create(readTimeoutSec = 10)

    @Test fun picksFastestMatchingMirrorAndRejectsDifferentContent() = runBlocking {
        val data = Random(9).nextBytes(2 * 1024 * 1024)
        val other = data.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() } // يختلف في بايت واحد بالمنتصف
        val slow = RangeServer(data).apply { throttleMsPer64k = 120 }
        val fast = RangeServer(data)
        val wrong = RangeServer(other)
        try {
            val ranked = MirrorSelector(client, sampleBytes = 256 * 1024).rank(RequestSpec(slow.url()), listOf(fast.url(), wrong.url()))
            assertEquals(fast.url(), ranked.first().url)
            assertTrue(ranked.first { it.url == slow.url() }.matches)
            assertTrue("different content must not match", !ranked.first { it.url == wrong.url() }.matches)
        } finally { slow.close(); fast.close(); wrong.close() }
    }
}

class LiveSettingsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val client = HttpClients.create(readTimeoutSec = 10)

    @Test fun loweringConnectionsMidDownloadTakesEffectAndKeepsIntegrity() = runBlocking {
        val data = Random(21).nextBytes(10 * 1024 * 1024)
        val server = RangeServer(data).apply { throttleMsPer64k = 250 }
        try {
            val cap = java.util.concurrent.atomic.AtomicInteger(8)
            val seen = java.util.Collections.synchronizedList(mutableListOf<Int>())
            val f = tmp.newFile("live.part")
            val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).async {
                HttpDownloader(client, RequestSpec(server.url()), f, DownloadConfig(connections = 8, minSplitBytes = 64 * 1024),
                    listener = object : DownloadListener { override fun onProgress(snapshot: Snapshot) { seen += snapshot.connections } },
                    liveMaxConnections = { cap.get() }).run()
            }
            kotlinx.coroutines.withTimeout(20_000) { while (seen.none { it > 2 }) kotlinx.coroutines.delay(100) }
            cap.set(2)
            val mark = seen.size
            job.await()
            val after = seen.drop(mark + 2) // مهلة ثانيتين للتطبيق
            assertTrue("reduced to ≤2 after change: $after", after.isNotEmpty() && after.all { it <= 2 })
            assertArrayEquals(data, f.readBytes())
        } finally { server.close() }
    }

    @Test fun dynamicSplitCanBeDisabled() = runBlocking {
        val data = Random(22).nextBytes(2 * 1024 * 1024)
        val server = RangeServer(data)
        try {
            val probe = Prober(client).probe(RequestSpec(server.url()))
            var last: ResumeState? = null
            val f = tmp.newFile("nosplit.part").also { java.io.RandomAccessFile(it, "rw").use { r -> r.setLength(data.size.toLong()) } }
            HttpDownloader(client, RequestSpec(server.url()), f, DownloadConfig(connections = 4, minSplitBytes = 64 * 1024, dynamicSplit = false),
                ResumeState(probe, listOf(SegmentState(0, 0, data.size - 1L, 0))),
                listener = object : DownloadListener { override suspend fun persist(state: ResumeState) { last = state } }).run()
            assertEquals(1, last!!.segments.size)
            assertArrayEquals(data, f.readBytes())
        } finally { server.close() }
    }
}
