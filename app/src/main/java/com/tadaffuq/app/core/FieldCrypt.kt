package com.tadaffuq.app.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * تشفير حقول حساسة في قاعدة البيانات (الكوكيز) بمفتاح من Android Keystore (AES-GCM).
 * إن تعذّر Keystore لا يُكتب السر نصاً صريحاً أبداً: يبقى في الذاكرة فقط ويُحفظ مرجعه.
 */
object FieldCrypt {
    private const val ALIAS = "tadaffuq-fields"
    private const val ENC = "enc1:"
    private const val MEM = "mem1:"
    private val memory = ConcurrentHashMap<String, String>()
    private val counter = AtomicLong()

    @Volatile private var cached: SecretKey? = null

    private fun key(): SecretKey? = cached ?: runCatching {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        ((ks.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()).also { cached = it }
    }.getOrNull()

    fun isSealed(v: String?) = v != null && (v.startsWith(ENC) || v.startsWith(MEM))

    fun seal(plain: String?): String? {
        if (plain.isNullOrEmpty() || isSealed(plain)) return plain
        val k = key()
        if (k == null) {
            val ref = MEM + System.nanoTime() + "-" + counter.incrementAndGet()
            memory[ref] = plain
            return ref
        }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, k) }
        return ENC + Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    /** يعيد null إن تعذّر الفك (مفتاح محذوف أو مرجع ذاكرة بعد موت العملية): يُنزَّل بلا كوكيز. */
    fun open(stored: String?): String? = when {
        stored.isNullOrEmpty() -> null
        stored.startsWith(MEM) -> memory[stored]
        stored.startsWith(ENC) -> runCatching {
            val bytes = Base64.decode(stored.removePrefix(ENC), Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key()!!, GCMParameterSpec(128, bytes, 0, 12)) }
            String(c.doFinal(bytes, 12, bytes.size - 12))
        }.getOrNull()
        else -> stored // صف قديم قبل التشفير؛ يُشفَّر عند الإقلاع
    }
}
