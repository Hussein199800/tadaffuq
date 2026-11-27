package com.tadaffuq.engine

import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.Authenticator
import okhttp3.ConnectionPool
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

data class ProxyConfig(
    val type: Proxy.Type,
    val host: String,
    val port: Int,
    val user: String? = null,
    val password: String? = null,
)

/** عميل HTTP واحد مشترك: يعيد استخدام الاتصالات (keep-alive) ولا يعطّل التحقق من TLS أبداً. */
object HttpClients {
    fun create(
        proxy: ProxyConfig? = null,
        connectTimeoutSec: Long = 15,
        readTimeoutSec: Long = 30,
    ): OkHttpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(DownloadConfig.MAX_CONNECTIONS * 2, 5, TimeUnit.MINUTES))
        .connectTimeout(connectTimeoutSec, TimeUnit.SECONDS)
        .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .addNetworkInterceptor(SecretsGuard)
        .apply {
            if (proxy != null) {
                proxy(Proxy(proxy.type, InetSocketAddress.createUnresolved(proxy.host, proxy.port)))
                if (proxy.user != null && proxy.type == Proxy.Type.SOCKS) {
                    // مصادقة SOCKS5 في Java تمر عبر Authenticator العام؛ نقصرها على مضيف الوكيل فقط.
                    val host = proxy.host; val port = proxy.port; val u = proxy.user; val pw = proxy.password.orEmpty().toCharArray()
                    java.net.Authenticator.setDefault(object : java.net.Authenticator() {
                        override fun getPasswordAuthentication(): java.net.PasswordAuthentication? =
                            if (requestingHost == host && requestingPort == port) java.net.PasswordAuthentication(u, pw) else null
                    })
                }
                if (proxy.user != null && proxy.type == Proxy.Type.HTTP) {
                    val cred = Credentials.basic(proxy.user, proxy.password.orEmpty())
                    proxyAuthenticator(Authenticator { _, response ->
                        if (response.request.header("Proxy-Authorization") != null) null
                        else response.request.newBuilder().header("Proxy-Authorization", cred).build()
                    })
                }
            }
        }
        .build()
}

/** وسم يرافق الطلب في كل تحويل: لمن تُرسل الأسرار. */
internal data class SecretsScope(val authority: String, val https: Boolean)

internal fun RequestSpec.toRequest(url: String = this.url): Request.Builder = Request.Builder().url(url).apply {
    tag(SecretsScope::class.java, SecretsScope(secretsAuthority, this@toRequest.url.startsWith("https", true)))
    headers.forEach { (k, v) -> header(k, v) }
    userAgent?.let { header("User-Agent", it) }
    referer?.let { header("Referer", it) }
    cookies?.let { header("Cookie", it) }
    // ضغط النقل يفسد حساب المواضع في الملف.
    header("Accept-Encoding", "identity")
}

/**
 * ينفّذ الطلب ويقرأ الاستجابة داخل [block] ثم يغلقها دائماً. الإلغاء (الإيقاف) يُسقط الاتصال فوراً
 * طوال عمر الطلب، **بما في ذلك قراءة الجسم**، لا بعد مهلة القراءة: المعالج يُسجَّل لحظة بدء الإلغاء
 * (onCancelling) لأن الـ coroutine نفسه محجوب في قراءة متزامنة فلا «يكتمل» قبل انتهائها.
 */
@OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
internal suspend inline fun <T> OkHttpClient.withResponse(request: Request, block: (Response) -> T): T {
    val call = newCall(request)
    val job = currentCoroutineContext()[Job]
    val handle = job?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { if (it != null) call.cancel() }
    try {
        currentCoroutineContext().ensureActive()
        return call.execute().use(block)
    } catch (e: java.io.IOException) {
        // استثناء الإدخال/الإخراج بسبب إلغائنا نحن يُعاد كإلغاء لا كخطأ شبكة
        currentCoroutineContext().ensureActive()
        throw e
    } finally {
        handle?.dispose()
    }
}

/** RFC 9110 §14.4: «bytes a-b/total». */
internal data class ContentRange(val first: Long, val last: Long, val total: Long?) {
    val valid: Boolean get() = first >= 0 && last >= first && (total == null || last < total)
}

internal fun parseContentRange(value: String?): ContentRange? {
    if (value == null) return null
    val (unit, spec) = value.trim().split(' ', limit = 2).takeIf { it.size == 2 } ?: return null
    if (!unit.equals("bytes", true)) return null
    val span = spec.substringBefore('/')
    val size = spec.substringAfter('/', "*")
    val first = span.substringBefore('-').toLongOrNull() ?: return null
    val last = span.substringAfter('-').toLongOrNull() ?: return null
    return ContentRange(first, last, size.toLongOrNull())
}

/**
 * يحذف الكوكيز وAuthorization وأي ترويسة إضافية سرية (وReferer عند الهبوط من HTTPS إلى HTTP) من أي طلب — بما فيها التحويلات —
 * يتجه إلى مضيف غير الذي التُقطت له الأسرار، أو عبر HTTP بعد التقاطها عبر HTTPS (P0-10).
 * OkHttp يحذف Authorization وحده عند تغيّر المضيف، لكنه يُبقي Cookie الذي نضيفه يدوياً.
 */
internal object SecretsGuard : okhttp3.Interceptor {
    override fun intercept(chain: okhttp3.Interceptor.Chain): Response {
        val req = chain.request()
        val scope = req.tag(SecretsScope::class.java) ?: return chain.proceed(req)
        val authority = RequestSpec.authorityOf(req.url.toString())
        val foreign = authority != scope.authority
        val downgrade = scope.https && !req.isHttps
        if (!foreign && !downgrade) return chain.proceed(req)
        val clean = req.newBuilder().removeHeader("Cookie").removeHeader("Authorization")
            // ترويسات إضافية تحمل سراً (رمز أو مفتاح أو جلسة) تُعامل كالكوكيز
            .apply { req.headers.names().filter(ExtraHeaders::isSensitive).forEach { removeHeader(it) } }
            .apply { if (downgrade) removeHeader("Referer") }.build()
        return chain.proceed(clean)
    }
}
