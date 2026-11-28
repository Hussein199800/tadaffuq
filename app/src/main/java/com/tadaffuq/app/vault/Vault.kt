package com.tadaffuq.app.vault

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.tadaffuq.engine.VaultCrypto
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

data class VaultItem(val id: String, val name: String, val size: Long, val addedAt: Long)

/**
 * المجلد الخاص: الملفات مشفّرة بـ AES-256-GCM (VaultCrypto) بمفتاح داخل Android Keystore لا يُستعمل
 * إلا بعد مصادقة بالبصمة أو رمز الجهاز (صالحة 60 ثانية)، والفهرس (الأسماء) مشفّر أيضاً.
 * حدود الاستعادة: المفتاح لا يغادر الجهاز، فحذف التطبيق أو إزالة قفل الشاشة يجعل الملفات غير قابلة للاسترجاع،
 * ولا تدخل النسخ الاحتياطي. الحذف من ذاكرة الفلاش لا يضمن المسح الفيزيائي للنسخة الأصلية.
 */
class Vault(private val context: Context) {
    private val dir = File(context.filesDir, "vault").apply { mkdirs() }
    private val indexFile = File(dir, "index.tdv")
    val openDir = File(context.cacheDir, "vault-open")

    private fun keyOrNull(): SecretKey? = runCatching {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        ks.getKey(ALIAS, null) as? SecretKey
    }.getOrNull()

    /** ينشئ المفتاح أول مرة. يفشل إن لم يكن للجهاز قفل شاشة آمن. */
    fun ensureKey(): SecretKey {
        keyOrNull()?.let { return it }
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .apply {
                if (Build.VERSION.SDK_INT >= 30) setUserAuthenticationParameters(60, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
                else @Suppress("DEPRECATION") setUserAuthenticationValidityDurationSeconds(60)
            }
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
    }

    /** هل المصادقة الحديثة ما زالت سارية؟ */
    fun unlocked(): Boolean = runCatching {
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding"); c.init(javax.crypto.Cipher.ENCRYPT_MODE, ensureKey()); true
    }.getOrDefault(false)

    fun list(): List<VaultItem> {
        if (!indexFile.exists()) return emptyList()
        val out = ByteArrayOutputStream()
        indexFile.inputStream().use { VaultCrypto.decrypt(ensureKey(), it, out) }
        val a = JSONArray(out.toString(Charsets.UTF_8.name()))
        return (0 until a.length()).map { a.getJSONObject(it).let { o -> VaultItem(o.getString("id"), o.getString("name"), o.getLong("size"), o.getLong("at")) } }
    }

    private fun save(items: List<VaultItem>) {
        val json = JSONArray().apply { items.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("size", it.size).put("at", it.addedAt)) } }.toString()
        val tmp = File(dir, "index.tmp")
        tmp.outputStream().use { VaultCrypto.encrypt(ensureKey(), ByteArrayInputStream(json.toByteArray()), it) }
        if (!tmp.renameTo(indexFile)) { indexFile.delete(); tmp.renameTo(indexFile) }
    }

    /** يشفّر الملف إلى المجلد. deleteOriginal يحذف الأصل فقط بعد نجاح التشفير والتحقق بفك الملف. */
    fun add(src: File, deleteOriginal: Boolean): VaultItem {
        val id = UUID.randomUUID().toString()
        val enc = File(dir, "$id.tdv")
        val key = ensureKey()
        try {
            src.inputStream().buffered().use { input -> enc.outputStream().buffered().use { VaultCrypto.encrypt(key, input, it) } }
            // تحقق: الفك ينجح وبالحجم نفسه قبل حذف أي شيء
            var n = 0L
            enc.inputStream().buffered().use { VaultCrypto.decrypt(key, it, object : java.io.OutputStream() { override fun write(b: Int) { n++ }; override fun write(b: ByteArray, o: Int, l: Int) { n += l } }) }
            check(n == src.length()) { "verify" }
        } catch (e: Exception) { enc.delete(); throw e }
        val item = VaultItem(id, src.name, src.length(), System.currentTimeMillis())
        save(list() + item)
        if (deleteOriginal) src.delete()
        return item
    }

    /** يفك ملفاً إلى مؤقت خاص بالتطبيق لفتحه أو مشاركته؛ يُحذف عند القفل وعند بدء التطبيق. */
    fun openTemp(item: VaultItem): File {
        openDir.mkdirs()
        val out = File(openDir, item.name)
        File(dir, "${item.id}.tdv").inputStream().buffered().use { input -> out.outputStream().buffered().use { VaultCrypto.decrypt(ensureKey(), input, it) } }
        return out
    }

    fun export(item: VaultItem, dest: File) {
        File(dir, "${item.id}.tdv").inputStream().buffered().use { input -> dest.outputStream().buffered().use { VaultCrypto.decrypt(ensureKey(), input, it) } }
    }

    /** يُحذف من الفهرس فقط بعد أن يختفي الملف المشفّر فعلاً؛ الفشل يُرمى ليظهر للمستخدم. */
    fun delete(item: VaultItem) {
        val f = File(dir, "${item.id}.tdv")
        if (f.exists() && !f.delete()) throw java.io.IOException("delete failed")
        save(list().filterNot { it.id == item.id })
    }

    fun clearTemp() { openDir.deleteRecursively() }

    companion object { private const val ALIAS = "tadaffuq-vault" }
}
