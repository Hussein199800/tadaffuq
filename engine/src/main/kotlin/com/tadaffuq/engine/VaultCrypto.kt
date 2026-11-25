package com.tadaffuq.engine

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * تشفير ملفات المجلد الخاص بكتل AES-256-GCM (يُبث دون تحميل الملف كله في الذاكرة).
 * كل كتلة لها nonce = 8 بايت عشوائية + عدّاد، والبيانات الإضافية تحمل رقم الكتلة وعلامة «الأخيرة»،
 * فأي تعديل أو حذف أو إعادة ترتيب أو اقتطاع للكتل يُكتشف ويفشل الفك.
 */
object VaultCrypto {
    private val MAGIC = "TDQV1".toByteArray()
    const val CHUNK = 1 shl 20

    fun encrypt(key: SecretKey, input: InputStream, output: OutputStream, chunk: Int = CHUNK, cipherFor: (Int, ByteArray) -> Cipher = { mode, iv -> gcm(key, mode, iv) }) {
        val base = ByteArray(8).also { SecureRandom().nextBytes(it) }
        val out = DataOutputStream(output)
        out.write(MAGIC); out.writeInt(chunk); out.write(base)
        var counter = 0
        var current = readFully(input, chunk)
        while (true) {
            val next = if (current.size == chunk) readFully(input, chunk) else ByteArray(0)
            val last = next.isEmpty()
            val c = cipherFor(Cipher.ENCRYPT_MODE, nonce(base, counter))
            c.updateAAD(aad(counter, last))
            out.write(c.doFinal(current))
            if (last) break
            current = next; counter++
        }
        out.flush()
    }

    fun decrypt(key: SecretKey, input: InputStream, output: OutputStream, cipherFor: (Int, ByteArray) -> Cipher = { mode, iv -> gcm(key, mode, iv) }) {
        val inp = DataInputStream(input)
        val magic = ByteArray(MAGIC.size).also { inp.readFully(it) }
        require(magic.contentEquals(MAGIC)) { "not a vault file" }
        val chunk = inp.readInt()
        require(chunk in 1024..(64 shl 20)) { "bad chunk size" }
        val base = ByteArray(8).also { inp.readFully(it) }
        var counter = 0
        var current = readFully(inp, chunk + 16)
        while (true) {
            val next = if (current.size == chunk + 16) readFully(inp, chunk + 16) else ByteArray(0)
            val last = next.isEmpty()
            if (current.size < 16) throw EOFException("truncated")
            val c = cipherFor(Cipher.DECRYPT_MODE, nonce(base, counter))
            c.updateAAD(aad(counter, last))
            output.write(c.doFinal(current)) // AEADBadTagException عند أي عبث
            if (last) break
            current = next; counter++
        }
        output.flush()
    }

    fun gcm(key: SecretKey, mode: Int, iv: ByteArray): Cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(mode, key, GCMParameterSpec(128, iv)) }

    private fun nonce(base: ByteArray, counter: Int) = ByteBuffer.allocate(12).put(base).putInt(counter).array()
    private fun aad(counter: Int, last: Boolean) = ByteBuffer.allocate(5).putInt(counter).put(if (last) 1 else 0).array()

    private fun readFully(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) break
            off += r
        }
        return if (off == n) buf else buf.copyOf(off)
    }
}
