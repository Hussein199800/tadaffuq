package com.tadaffuq.app.download

import android.content.Context
import com.tadaffuq.app.core.SecretStore
import com.tadaffuq.engine.Credentials
import com.tadaffuq.engine.RemoteTarget

/** كلمات مرور FTP/SFTP من المخزن المشفّر، وبصمات مفاتيح SSH التي وافق عليها المستخدم. */
class AppCredentials(context: Context, private val secrets: SecretStore) : Credentials {
    private val hosts = context.getSharedPreferences("known_hosts", Context.MODE_PRIVATE)

    override fun password(target: RemoteTarget): String? = secrets.get(SecretStore.ftpPassword(target.credentialKey))
    fun setPassword(target: RemoteTarget, password: String?) = secrets.put(SecretStore.ftpPassword(target.credentialKey), password)

    override fun knownHostKey(host: String, port: Int): String? = hosts.getString("$host:$port", null)
    fun trustHostKey(host: String, port: Int, fingerprint: String) = hosts.edit().putString("$host:$port", fingerprint).apply()
    fun forgetHostKey(host: String, port: Int) = hosts.edit().remove("$host:$port").apply()
}
