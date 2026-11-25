package com.tadaffuq.app.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * أسرار صغيرة (كلمة مرور Proxy، رموز Hugging Face، كلمات مرور FTP/SFTP) مشفّرة بـ AES-256-GCM
 * بمفتاح لا يغادر Android Keystore. لا تُكتب في السجلات ولا تدخل النسخ الاحتياطي.
 * إن تعذّر Keystore (بيئة اختبار) تبقى الأسرار في الذاكرة فقط ولا تُحفظ نصاً صريحاً أبداً.
 */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)
    private val memory = HashMap<String, String>()

    private fun key(): SecretKey? = runCatching {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }.getOrNull()

    @Synchronized fun put(name: String, value: String?) {
        if (value.isNullOrEmpty()) { prefs.edit().remove(name).apply(); memory.remove(name); return }
        val k = key()
        if (k == null) { memory[name] = value; return }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, k) }
        val out = c.iv + c.doFinal(value.toByteArray())
        prefs.edit().putString(name, Base64.encodeToString(out, Base64.NO_WRAP)).apply()
    }

    @Synchronized fun get(name: String): String? {
        memory[name]?.let { return it }
        val raw = prefs.getString(name, null) ?: return null
        val k = key() ?: return null
        return runCatching {
            val bytes = Base64.decode(raw, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, bytes, 0, 12)) }
            String(c.doFinal(bytes, 12, bytes.size - 12))
        }.getOrNull()
    }

    fun has(name: String) = get(name) != null

    companion object {
        private const val ALIAS = "tadaffuq-secrets"
        const val PROXY_PASSWORD = "proxy_password"
        const val HF_TOKEN = "hf_token"
        const val RPC_TOKEN = "rpc_token"
        fun ftpPassword(hostKey: String) = "ftp:$hostKey"
    }
}
