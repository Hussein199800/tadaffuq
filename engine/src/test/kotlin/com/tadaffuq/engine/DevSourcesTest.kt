package com.tadaffuq.engine

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DevSourcesTest {
    private val server = MockWebServer()
    private val client = HttpClients.create()
    @After fun tearDown() = server.shutdown()

    private val sha = "a".repeat(64)
    private val releasesJson get() = """[
      {"tag_name":"v2.0","name":"","prerelease":false,"published_at":"2026-11-01T00:00:00Z","body":"ملاحظات \"الإصدار\"\n- إصلاحات",
       "assets":[
         {"name":"app-armeabi-v7a.apk","size":10,"browser_download_url":"${server.url("/d/v7.apk")}","download_count":3},
         {"name":"app-arm64-v8a.apk","size":12,"browser_download_url":"${server.url("/d/a64.apk")}","digest":"sha256:$sha","download_count":9},
         {"name":"app-universal.apk","size":30,"browser_download_url":"${server.url("/d/u.apk")}"},
         {"name":"source.tar.gz","size":5,"browser_download_url":"${server.url("/d/src.tgz")}"},
         {"name":"SHA256SUMS","size":200,"browser_download_url":"${server.url("/d/sums")}"}]}]"""

    @Test fun githubReleasesAbiRankingAndChecksums() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when (request.path) {
                "/repos/acme/tool/releases?per_page=15" -> MockResponse().setBody(releasesJson)
                "/d/sums" -> MockResponse().setBody("${"b".repeat(64)}  app-armeabi-v7a.apk\n${"c".repeat(64)} *app-universal.apk\n")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val gh = GitHubReleases(client, server.url("").toString().removeSuffix("/"))
        assertEquals("acme/tool", gh.parseRepo("https://github.com/acme/tool/releases"))
        val rel = gh.releases("acme/tool").single()
        assertEquals("v2.0", rel.name)
        assertTrue(rel.notes.contains("\"الإصدار\""))
        val ranked = GitHubReleases.rankForAbis(rel.assets, listOf("arm64-v8a", "armeabi-v7a"))
        assertEquals(listOf("app-arm64-v8a.apk", "app-armeabi-v7a.apk", "app-universal.apk"), ranked.take(3).map { it.name })
        val sums = gh.checksums(rel)
        assertEquals(sha, sums["app-arm64-v8a.apk"])
        assertEquals("b".repeat(64), sums["app-armeabi-v7a.apk"])
        assertEquals("c".repeat(64), sums["app-universal.apk"])
    }

    @Test fun githubRateLimitIsReported() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setHeader("X-RateLimit-Remaining", "0").setHeader("X-RateLimit-Reset", "1800000000"))
        server.start()
        try { GitHubReleases(client, server.url("").toString().removeSuffix("/")).releases("a/b"); fail() }
        catch (e: SourceException) { assertEquals(SourceException.Kind.RATE_LIMITED, e.kind); assertEquals(1800000000L, e.resetAtEpochSec) }
    }

    @Test fun huggingFaceTreeShardsTokenOnlyToHub() = runBlocking {
        val tree = """[
          {"type":"directory","path":"q4"},
          {"type":"file","path":"README.md","size":100},
          {"type":"file","path":"q4/model-Q4-00001-of-00002.gguf","size":134,"lfs":{"oid":"$sha","size":5000000000}},
          {"type":"file","path":"q4/model-Q4-00002-of-00002.gguf","size":134,"lfs":{"oid":"${"d".repeat(64)}","size":3000000000}},
          {"type":"file","path":"single Q8.gguf","size":134,"lfs":{"oid":"${"e".repeat(64)}","size":7000000000}}]"""
        server.enqueue(MockResponse().setBody(tree))
        server.enqueue(MockResponse().setResponseCode(401))
        server.start()
        val hf = HuggingFace(client, server.url("").toString().removeSuffix("/"))
        assertEquals("org/model-GGUF", hf.parseRepo("https://huggingface.co/org/model-GGUF/tree/main"))
        val files = hf.files("org/model-GGUF", token = "hf_secret")
        assertEquals("Bearer hf_secret", server.takeRequest().getHeader("Authorization"))
        assertEquals(4, files.size)
        val shards = HuggingFace.shards(files, "q4/model-Q4")
        assertEquals(2, shards.size)
        assertEquals(5_000_000_000L, shards[0].size)
        assertEquals(sha, shards[0].sha256)
        assertTrue(files.first { it.path == "single Q8.gguf" }.url.endsWith("/resolve/main/single%20Q8.gguf"))
        assertNull(files.first { it.path == "README.md" }.sha256)
        try { hf.files("org/gated", null); fail() } catch (e: SourceException) { assertEquals(SourceException.Kind.UNAUTHORIZED, e.kind) }
    }

    @Test fun miniJsonEdgeCases() {
        @Suppress("UNCHECKED_CAST")
        val m = MiniJson.parse("""{"a":[1,2.5,-3,true,false,null],"s":"م\n\"x\""}""") as Map<String, Any?>
        assertEquals(listOf(1L, 2.5, -3L, true, false, null), m["a"])
        assertEquals("م\n\"x\"", m["s"])
    }
}
