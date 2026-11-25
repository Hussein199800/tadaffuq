package com.tadaffuq.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RulesTest {
    @Test fun matchingAndOrder() {
        val iso = Rule(1, "صور الأقراص", extensions = "iso, .img", folder = "أقراص", priority = 5)
        val gh = Rule(2, "GitHub", hostContains = "github.com", folder = "../../etc", extract = true)
        val big = Rule(3, "كبير", minSizeMb = 1000, speedLimitKbps = 500)
        val rules = listOf(iso, gh, big)
        assertEquals(iso, Rule.firstMatch(rules, "ubuntu.ISO", "https://x.org/u.iso", 10))
        assertEquals(gh, Rule.firstMatch(rules, "app.zip", "https://objects.github.com/a", 10))
        assertEquals(big, Rule.firstMatch(rules, "x.bin", "https://y", 2L * 1024 * 1024 * 1024))
        assertNull(Rule.firstMatch(rules, "x.bin", "https://y", -1))
        assertFalse(iso.copy(enabled = false).matches("a.iso", "https://x", 1))
        assertEquals("etc", Rule.safeFolder(gh.folder))
        val back = Rule.listFrom(Rule.listTo(rules))
        assertEquals(rules, back)
        assertTrue(Rule.listFrom("garbage").isEmpty())
    }
}
