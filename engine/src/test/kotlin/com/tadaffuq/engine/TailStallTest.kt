package com.tadaffuq.engine

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * 1.3.0: «يتوقف عند 95٪». اتصال واحد يبطؤ إلى بضع بايتات في الثانية (شائع في خوادم الفيديو) أو يتجمد دون إغلاق؛
 * كانت بقية الاتصالات تنتهي وينتظر الملف هذا الاتصال (أو مهلة 30 ث ثم محاولات حتى الفشل).
 */
class TailStallTest {
    private val data = Random(95).nextBytes(4 * 1024 * 1024)

    private fun run(server: RangeServer): Long = runBlocking {
        val f = File.createTempFile("tail", ".part").apply { deleteOnExit() }
        val t0 = System.nanoTime()
        withTimeout(40_000) { HttpDownloader(HttpClients.create(), RequestSpec(server.url()), f, DownloadConfig(connections = 4, autoTune = false)).run() }
        assertArrayEquals(data, f.readBytes())
        (System.nanoTime() - t0) / 1_000_000
    }

    /** الجزء الأخير على اتصال يبث 2 ك.ب/ث: يُستبدل باتصال جديد بدل انتظار دقائق. */
    @Test fun slowTailConnectionIsReplaced() {
        val s = RangeServer(data).apply { badFrom = 3L * 1024 * 1024; badConnections.set(1); badBytesPerSec = 2048 }
        try { val ms = run(s); assertTrue("took ${ms}ms", ms < 25_000) } finally { s.close() }
    }

    /** اتصال يتجمد تماماً (لا بايت ولا إغلاق): لا ننتظر مهلة القراءة كاملة ثم التراجع. */
    @Test fun frozenConnectionIsReconnected() {
        val s = RangeServer(data).apply { badFrom = 2L * 1024 * 1024; badConnections.set(1); badBytesPerSec = 1 }
        try { val ms = run(s); assertTrue("took ${ms}ms", ms < 25_000) } finally { s.close() }
    }
}

class SegmentHealthTest {
    private val s = 1_000_000_000L
    private fun seg(i: Int, done: Long = 0, end: Long = 10_000_000) = Segment(i, 0, end, done)

    /** شبكة بطيئة كلها: لا قطع (المرجع منها)، ولا قبل مهلة الفتح. */
    @Test fun uniformlySlowNetworkIsLeftAlone() {
        val h = SegmentHealth()
        val a = seg(0); val b = seg(1)
        h.launched(0, 0, 0); h.launched(1, 0, 0)
        for (t in 1..12) {
            a.downloaded = t * 20_000L; b.downloaded = t * 20_000L
            h.sample(listOf(a, b), setOf(a, b), t * s)
            assertTrue(h.toRestart(listOf(a, b), t * s).isEmpty())
        }
    }

    /** سقف الإعادة يمنع الدوران بلا نهاية مع خادم يجمد كل اتصال. */
    @Test fun restartsAreCapped() {
        val h = SegmentHealth()
        val a = seg(0)
        var restarts = 0
        var now = 0L
        repeat(SegmentHealth.MAX_RESTARTS + 5) {
            h.launched(0, a.downloaded, now)
            repeat(10) { now += s; h.sample(listOf(a), setOf(a), now); if (h.toRestart(listOf(a), now).isNotEmpty()) restarts++ }
        }
        org.junit.Assert.assertEquals(SegmentHealth.MAX_RESTARTS, restarts)
    }
}
