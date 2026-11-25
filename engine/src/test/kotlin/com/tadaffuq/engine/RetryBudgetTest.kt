package com.tadaffuq.engine

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import kotlin.random.Random

/** P1 (التدقيق): ميزانية المحاولات وفحص الرابط بإعادة محاولة. */
class RetryBudgetTest {
    private val data = Random(5).nextBytes(400_000)
    private val server = RangeServer(data)
    @After fun tearDown() { server.close() }
    private fun part() = File.createTempFile("retry", ".part").apply { deleteOnExit() }

    /** خادم يقطع كل اتصال بعد بضع بايتات: تقدم ضئيل لا يجدد الميزانية إلى ما لا نهاية. */
    @Test fun tinyProgressDoesNotResetBudgetForever() = runBlocking {
        server.cutAfterBytes = 100
        server.cutsRemaining.set(Int.MAX_VALUE)
        val cfg = DownloadConfig(connections = 1, maxConnections = 1, maxRetries = 3, baseRetryDelayMs = 5, maxRetryDelayMs = 10, autoTune = false)
        try {
            withTimeout(20_000) { HttpDownloader(HttpClients.create(), RequestSpec(server.url()), part(), cfg).run() }
            fail("should give up")
        } catch (e: DownloadException) {
            assertTrue(e.error is DownloadError.Network)
        }
        assertTrue("bounded attempts: ${server.requests.get()}", server.requests.get() < 40)
    }

    /** الفحص نفسه يتراجع عند 503 مع Retry-After ثم ينجح. */
    @Test fun probeRetriesBusyServer() = runBlocking {
        server.errorsRemaining.set(2)
        server.errorCode = 503
        val f = part()
        val cfg = DownloadConfig(connections = 2, maxRetries = 4, baseRetryDelayMs = 10, maxRetryDelayMs = 50)
        HttpDownloader(HttpClients.create(), RequestSpec(server.url()), f, cfg).run()
        assertArrayEquals(data, f.readBytes())
    }
}
