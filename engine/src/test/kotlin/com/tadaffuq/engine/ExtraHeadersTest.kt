package com.tadaffuq.engine

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** 1.2.0: ترويسات تصل من تطبيق آخر أو يكتبها المستخدم. */
class ExtraHeadersTest {
    @Test fun parseAndFormat() {
        val h = ExtraHeaders.parse("Origin: https://site.tv\n\n  X-Requested-With : com.app \nbad line\nAccept: */*")
        assertEquals(mapOf("Origin" to "https://site.tv", "X-Requested-With" to "com.app", "Accept" to "*/*"), h)
        assertEquals(h, ExtraHeaders.parse(ExtraHeaders.format(h)))
        assertEquals(emptyMap<String, String>(), ExtraHeaders.parse(null))
    }

    /** ترويسات يديرها المحرك تفسد التقسيم والاستئناف؛ أسماء غير صالحة أو أسطر داخل القيمة = حقن. */
    @Test fun rejectsManagedInvalidAndInjected() {
        val h = ExtraHeaders.sanitize(listOf(
            "Range" to "bytes=0-1", "Host" to "evil", "Accept-Encoding" to "gzip", "If-Range" to "x", "Content-Length" to "1",
            "Bad Name" to "v", "X-Ok" to "a\r\nInjected: 1", "X-Null" to "a\u0000b", "X-Empty" to "  ", "X-Long" to "v".repeat(ExtraHeaders.MAX_VALUE + 1),
            "Origin" to "https://o",
        ))
        assertEquals(mapOf("Origin" to "https://o"), h)
        assertEquals(listOf("Range: bytes=0-1", "nonsense"), ExtraHeaders.rejectedLines("Range: bytes=0-1\nOrigin: x\nnonsense\n"))
    }

    @Test fun lastValueWinsCaseInsensitiveAndCountIsCapped() {
        assertEquals(mapOf("origin" to "b"), ExtraHeaders.sanitize(listOf("Origin" to "a", "origin" to "b")))
        assertEquals(ExtraHeaders.MAX_COUNT, ExtraHeaders.sanitize((1..100).map { "X-H$it" to "v" }).size)
    }

    @Test fun sensitiveNames() {
        listOf("Authorization", "Cookie", "X-Auth-Token", "X-API-Key", "X-Session-Id", "X-CSRF-Token", "X-Signature").forEach { assertTrue(it, ExtraHeaders.isSensitive(it)) }
        listOf("Origin", "Accept", "X-Requested-With", "Accept-Language").forEach { assertFalse(it, ExtraHeaders.isSensitive(it)) }
    }

    @Test fun withExtension() {
        assertEquals("الحلقة 5.mp4", FileNames.withExtension("الحلقة 5", "video.mp4", "video/mp4"))
        assertEquals("الحلقة 5.mkv", FileNames.withExtension("الحلقة 5", "stream", "video/x-matroska"))
        assertEquals("Ep 12.mp4", FileNames.withExtension("Ep 12", null, "video/mp4"))
        assertEquals("song.mp3", FileNames.withExtension("song.mp3", "x.bin", "audio/mpeg"))
        assertEquals("Ep 5.1.mp4", FileNames.withExtension("Ep 5.1", "a.mp4", null)) // «.1» ليس امتداداً
        assertEquals("ep_5.mp4", FileNames.withExtension("ep/5", "a.mp4", null))
        assertEquals("title", FileNames.withExtension("title", "stream", null))
        assertEquals("watch.mp4", FileNames.resolve(null, "https://cdn.x/watch?id=9", "video/mp4"))
    }

    /** ترويسة سرية مخصصة لا تتبع التحويل إلى مضيف آخر؛ غير السرية (Origin) تتبعه، والمضيف الأصلي يتلقى الاثنتين. */
    @Test fun sensitiveCustomHeadersStayWithTheirHost() = runBlocking {
        val data = Random(5).nextBytes(50_000)
        val cdn = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setBody(Buffer().write(data))
            }
            start()
        }
        val origin = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(302).setHeader("Location", cdn.url("/v.mp4"))
            }
            start()
        }
        try {
            val spec = RequestSpec(origin.url("/watch").toString(), headers = mapOf("Origin" to "https://site.tv", "X-Auth-Token" to "SECRET"), referer = "https://site.tv/ep5")
            val f = File.createTempFile("hdr", ".part").apply { deleteOnExit() }
            HttpDownloader(HttpClients.create(), spec, f, DownloadConfig(connections = 1)).run()
            val first = origin.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("SECRET", first.getHeader("X-Auth-Token")); assertEquals("https://site.tv", first.getHeader("Origin"))
            val atCdn = generateSequence { cdn.takeRequest(1, TimeUnit.SECONDS) }.toList()
            assertTrue(atCdn.isNotEmpty())
            atCdn.forEach {
                assertNull(it.getHeader("X-Auth-Token"))
                assertEquals("https://site.tv", it.getHeader("Origin"))
                assertEquals("https://site.tv/ep5", it.getHeader("Referer"))
            }
            assertArrayEquals(data, f.readBytes())
        } finally { origin.shutdown(); cdn.shutdown() }
    }
}
