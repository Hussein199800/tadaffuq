package com.tadaffuq.app.download

import com.tadaffuq.app.core.SecretStore
import com.tadaffuq.data.ProxyMode
import com.tadaffuq.data.Settings
import com.tadaffuq.engine.HttpClients
import com.tadaffuq.engine.ProxyConfig
import okhttp3.OkHttpClient
import java.net.Proxy

/** عميل HTTP واحد لكل إعداد Proxy: يُعاد بناؤه فقط عند تغيّر الإعداد، فتبقى الاتصالات مُعادة الاستخدام. */
class ClientProvider(private val secrets: SecretStore) {
    private var key: Any? = null
    private var client: OkHttpClient = HttpClients.create()

    @Synchronized fun get(s: Settings): OkHttpClient {
        val cfg = proxyOf(s)
        val k = listOf(cfg, s.mode)
        if (k != key) {
            key = k
            val weak = s.mode == com.tadaffuq.engine.SpeedMode.WEAK_NETWORK
            client = HttpClients.create(cfg, connectTimeoutSec = if (weak) 30 else 15, readTimeoutSec = if (weak) 90 else 30)
        }
        return client
    }

    fun proxyOf(s: Settings): ProxyConfig? = when {
        s.proxyMode == ProxyMode.NONE || s.proxyHost.isBlank() || s.proxyPort !in 1..65535 -> null
        else -> ProxyConfig(
            if (s.proxyMode == ProxyMode.SOCKS5) Proxy.Type.SOCKS else Proxy.Type.HTTP,
            s.proxyHost.trim(), s.proxyPort, s.proxyUser.ifBlank { null }, secrets.get(SecretStore.PROXY_PASSWORD),
        )
    }
}
