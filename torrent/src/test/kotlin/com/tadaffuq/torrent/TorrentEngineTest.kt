package com.tadaffuq.torrent

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.libtorrent4j.TorrentBuilder
import java.io.File
import kotlin.random.Random

/**
 * تورنت حقيقي على الجهاز نفسه: جلسة «باذر» تملك الملفات وجلسة «منزّل» تتصل بها مباشرة
 * (بلا DHT ولا Trackers، فلا يخرج شيء إلى الإنترنت). نتحقق من البايتات واختيار الملفات والاستئناف.
 */
class TorrentEngineTest {
    @get:Rule val tmp = TemporaryFolder()
    private val local = TorrentSettings(dht = false, lsd = false, allowLocalPeers = true)

    private fun makeTorrent(dir: File): TorrentMeta {
        val result = TorrentBuilder().path(dir).pieceSize(64 * 1024).creator("tadaffuq-test").generate()
        return TorrentEngine.parse(result.entry().bencode())
    }

    private fun waitUntil(timeoutMs: Long, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) { if (System.currentTimeMillis() > end) throw AssertionError("timeout"); Thread.sleep(100) }
    }

    @Test fun downloadsSelectedFilesFromLocalSeederAndResumes() {
        val src = tmp.newFolder("pack")
        val a = Random(1).nextBytes(1_500_000).also { File(src, "a.bin").writeBytes(it) }
        Random(2).nextBytes(900_000).also { File(src, "b.bin").writeBytes(it) }
        val meta = makeTorrent(src)
        assertEquals(2, meta.files.size)

        val seeder = TorrentEngine(tmp.newFolder("s-state"))
        val seedPort = java.net.ServerSocket(0).use { it.localPort }
        seeder.start(local.copy(listenInterface = "127.0.0.1:$seedPort"))
        seeder.add(meta, src.parentFile, meta.files.map { it.index }.toSet())
        waitUntil(15_000) { seeder.status(meta.infoHash)?.state == TorrentState.SEEDING }
        val port = seedPort
        assertTrue("seeder listening", port > 0)

        val leechState = tmp.newFolder("l-state")
        val out = tmp.newFolder("out")
        var leecher = TorrentEngine(leechState)
        val leechPort = java.net.ServerSocket(0).use { it.localPort }
        leecher.start(local.copy(listenInterface = "127.0.0.1:$leechPort"))
        val onlyA = setOf(meta.files.first { it.path.endsWith("a.bin") }.index)
        leecher.add(meta, out, onlyA, peers = listOf("127.0.0.1" to port))
        var n = 0
        waitUntil(60_000) {
            if (n++ % 20 == 0) leecher.connectPeer(meta.infoHash, "127.0.0.1", port) // إعادة المحاولة إن كان التورنت ما زال يُفحص
            leecher.status(meta.infoHash)?.let { it.wanted > 0 && it.wantedDone == it.wanted } == true
        }
        val st = leecher.status(meta.infoHash)!!
        assertEquals(a.size.toLong(), st.wanted)
        val gotA = File(out, "pack/a.bin")
        assertArrayEquals(a, gotA.readBytes())
        assertFalse("unselected file not fully downloaded", File(out, "pack/b.bin").let { it.exists() && it.length() == 900_000L && it.readBytes().contentEquals(File(src, "b.bin").readBytes()) })

        // إعادة التشغيل: بيانات الاستئناف تمنع إعادة التنزيل
        leecher.saveResume(meta.infoHash)
        waitUntil(5_000) { File(leechState, "${meta.infoHash}.resume").exists() }
        leecher.stop()
        leecher = TorrentEngine(leechState)
        leecher.start(local.copy(listenInterface = "127.0.0.1:$leechPort"))
        leecher.add(meta, out, onlyA)
        waitUntil(20_000) { leecher.status(meta.infoHash)?.let { it.state != TorrentState.CHECKING && it.wantedDone == it.wanted && it.wanted > 0 } == true }
        assertEquals("nothing re-downloaded", 0L, leecher.status(meta.infoHash)!!.downloaded)

        // إعادة فحص القطع بعد إتلاف بايت: يكتشف النقص
        gotA.writeBytes(a.copyOf().also { it[100] = (it[100] + 1).toByte() })
        leecher.recheck(meta.infoHash)
        waitUntil(20_000) { leecher.status(meta.infoHash)?.let { it.state != TorrentState.CHECKING && it.wantedDone < it.wanted } == true }

        leecher.stop(); seeder.stop()
    }

    @Test fun parsesMagnet() {
        assertTrue(TorrentEngine.isMagnet("magnet:?xt=urn:btih:abc"))
        assertFalse(TorrentEngine.isMagnet("https://x"))
    }
}
