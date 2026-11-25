package com.tadaffuq.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

/**
 * مقارنة اتصال واحد بعدة اتصالات على المصدر نفسه وفي الظروف نفسها: خادم محلي يحدّ كل اتصال
 * بنحو 1 م.ب/ث (كما تفعل خوادم وشبكات توصيل كثيرة). النتائج تُكتب في build/perf.txt.
 * هذا قياس محلي لمحاكاة حد الاتصال الواحد، لا ادعاء بسرعة على الإنترنت الحقيقي.
 */
class PerformanceTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun singleVersusMultipleConnections() = runBlocking {
        val data = Random(5).nextBytes(12 * 1024 * 1024)
        val server = RangeServer(data).apply { throttleMsPer64k = 62 } // ≈ 1 م.ب/ث لكل اتصال
        val client = HttpClients.create()
        val rt = Runtime.getRuntime()
        val lines = mutableListOf("connections\tseconds\tMB/s\theap_delta_MB")
        val times = HashMap<Int, Double>()
        try {
            for (n in listOf(1, 4, 8, 16)) {
                System.gc(); val heap0 = rt.totalMemory() - rt.freeMemory()
                val f = File(tmp.root, "p$n.bin")
                val t0 = System.nanoTime()
                HttpDownloader(client, RequestSpec(server.url()), f, DownloadConfig(connections = n, autoTune = false, minSplitBytes = 256 * 1024)).run()
                val secs = (System.nanoTime() - t0) / 1e9
                val heap = (rt.totalMemory() - rt.freeMemory() - heap0) / 1048576.0
                assertArrayEquals(data, f.readBytes())
                times[n] = secs
                lines += "$n\t${"%.2f".format(secs)}\t${"%.2f".format(data.size / 1048576.0 / secs)}\t${"%.1f".format(heap)}"
                f.delete()
            }
        } finally { server.close() }
        File("build/perf.txt").apply { parentFile.mkdirs() }.writeText(lines.joinToString("\n") + "\n")
        println(lines.joinToString("\n"))
        assertTrue("4 connections should beat 1 under a per-connection cap: $times", times[4]!! < times[1]!! * 0.5)
    }
}
