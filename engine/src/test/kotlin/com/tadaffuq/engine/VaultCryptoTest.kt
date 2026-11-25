package com.tadaffuq.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.crypto.KeyGenerator
import kotlin.random.Random

class VaultCryptoTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun enc(data: ByteArray, chunk: Int = 4096) = ByteArrayOutputStream().also { VaultCrypto.encrypt(key, ByteArrayInputStream(data), it, chunk) }.toByteArray()
    private fun dec(data: ByteArray) = ByteArrayOutputStream().also { VaultCrypto.decrypt(key, ByteArrayInputStream(data), it) }.toByteArray()

    @Test fun roundTripVariousSizes() {
        for (n in listOf(0, 1, 4095, 4096, 4097, 3 * 4096, 50_000)) {
            val d = Random(n).nextBytes(n)
            assertArrayEquals("size $n", d, dec(enc(d)))
        }
    }

    @Test fun tamperingTruncationAndReorderingAreDetected() {
        val d = Random(9).nextBytes(3 * 4096 + 10)
        val e = enc(d)
        fun expectFail(bytes: ByteArray, what: String) { try { dec(bytes); fail(what) } catch (_: Exception) { } }
        expectFail(e.copyOf().also { it[100] = (it[100] + 1).toByte() }, "bit flip")
        val header = 5 + 4 + 8
        val c = 4096 + 16
        expectFail(e.copyOf(header + 2 * c), "dropped last chunks")
        val swapped = e.copyOf().also { System.arraycopy(e, header + c, it, header, c); System.arraycopy(e, header, it, header + c, c) }
        expectFail(swapped, "reordered")
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        try { VaultCrypto.decrypt(other, ByteArrayInputStream(e), ByteArrayOutputStream()); fail("wrong key") } catch (_: Exception) { }
    }
}
