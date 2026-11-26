package com.tadaffuq.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SelfUpdateTest {
    private val sha = "a".repeat(64)
    private fun asset(name: String, url: String = SelfUpdate.DOWNLOAD_PREFIX + "v1/" + name, digest: String? = sha) = ReleaseAsset(name, 10, url, digest, 0)
    private fun rel(tag: String, vararg a: ReleaseAsset, pre: Boolean = false) = Release(tag, tag, pre, "", "## ما الجديد\n- **إصلاح** شيء", a.toList())
    private val noSums: (Release) -> Map<String, String> = { emptyMap() }

    @Test fun picksNewestMatchingAbi() {
        val r = listOf(
            rel("v1.0.2", asset("tadaffuq-1.0.2-10-abcdef1-github-arm64-v8a-release.apk"), asset("tadaffuq-1.0.2-10-abcdef1-github-universal-release.apk")),
            rel("v1.0.1", asset("tadaffuq-1.0.1-9-1234567-github-arm64-v8a-release.apk")),
        )
        val c = SelfUpdate.pick(r, currentCode = 9, abis = listOf("arm64-v8a"), checksums = noSums)!!
        assertEquals(10, c.versionCode); assertEquals("arm64-v8a", c.abi); assertEquals("1.0.2", c.versionName)
    }

    @Test fun fallsBackToUniversal() {
        val r = listOf(rel("v1.0.2", asset("tadaffuq-1.0.2-10-abcdef1-github-universal-release.apk")))
        assertEquals("universal", SelfUpdate.pick(r, 9, listOf("x86"), noSums)!!.abi)
    }

    @Test fun rejectsSameOrOlder() {
        val r = listOf(rel("v1.0.1", asset("tadaffuq-1.0.1-9-1234567-github-arm64-v8a-release.apk")))
        assertNull(SelfUpdate.pick(r, 9, listOf("arm64-v8a"), noSums))
    }

    @Test fun rejectsForeignUrlPlayFlavorPrereleaseAndMissingChecksum() {
        val n = "tadaffuq-1.0.2-10-abcdef1-github-arm64-v8a-release.apk"
        assertNull("foreign host", SelfUpdate.pick(listOf(rel("v", asset(n, url = "https://evil.example/$n"))), 9, listOf("arm64-v8a"), noSums))
        assertNull("other repo", SelfUpdate.pick(listOf(rel("v", asset(n, url = "https://github.com/someone/fork/releases/download/v/$n"))), 9, listOf("arm64-v8a"), noSums))
        assertNull("play flavor", SelfUpdate.pick(listOf(rel("v", asset("tadaffuq-1.0.2-10-abcdef1-play-arm64-v8a-release.apk"))), 9, listOf("arm64-v8a"), noSums))
        assertNull("prerelease", SelfUpdate.pick(listOf(rel("v", asset(n), pre = true)), 9, listOf("arm64-v8a"), noSums))
        assertNull("no checksum", SelfUpdate.pick(listOf(rel("v", asset(n, digest = null))), 9, listOf("arm64-v8a"), noSums))
    }

    @Test fun checksumFromSumsFile() {
        val n = "tadaffuq-1.0.2-10-abcdef1-github-arm64-v8a-release.apk"
        val c = SelfUpdate.pick(listOf(rel("v", asset(n, digest = null))), 9, listOf("arm64-v8a"), { mapOf(n to "B".repeat(64)) })!!
        assertEquals("b".repeat(64), c.sha256)
    }

    @Test fun notesAndSchedule() {
        assertEquals("ما الجديد\n• إصلاح شيء", SelfUpdate.plainNotes("## ما الجديد\n- **إصلاح** شيء"))
        assertEquals(true, SelfUpdate.shouldCheck(0, 1))
        assertEquals(false, SelfUpdate.shouldCheck(1_000, 1_000 + 3_600_000))
        assertEquals(true, SelfUpdate.shouldCheck(1_000, 1_000 + 12 * 3_600_000L))
    }
}
