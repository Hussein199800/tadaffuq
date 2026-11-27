package com.tadaffuq.app.core

import android.content.Intent
import android.os.Bundle
import com.tadaffuq.app.ui.add.AddRequest
import com.tadaffuq.engine.ExtraHeaders

/**
 * ما ترسله تطبيقات المشاهدة (مثل تطبيقات الأنمي) حين تفتح رابطاً بـ «فتح باستخدام» أو «تنزيل خارجي»:
 * الرابط في البيانات ونوعه (video/mp4…)، ومعه في الإضافات Referer وUser-Agent والكوكيز والعنوان والترويسات.
 * كل تطبيق يسمّيها باسم مختلف، فتُقرأ كل الصيغ الشائعة:
 * - `headers` مصفوفة نصوص [اسم، قيمة، اسم، قيمة…] أو [«اسم: قيمة»…] (صيغة MX Player وكثير من التطبيقات).
 * - `com.android.browser.headers` أو `headers` حزمة Bundle (صيغة متصفح أندرويد).
 * - نص بأسطر «اسم: قيمة»، أو HashMap.
 * - مفاتيح منفصلة: referer/referrer، User-Agent/useragent، cookie/cookies، title/filename/name، مع البادئات
 *   `extra_` و`android.intent.extra.` وبأي حالة أحرف.
 */
object ExternalIntent {
    private const val MAX_EXTRAS = 100

    /** يحوّل مفتاحاً إلى صورة موحدة: «android.intent.extra.User-Agent» ← «useragent». */
    internal fun norm(key: String): String = key.lowercase()
        .removePrefix("android.intent.extra.").removePrefix("com.android.browser.").removePrefix("extra_").removePrefix("extra.")
        .filter { it.isLetterOrDigit() }

    private val headerKeys = setOf("headers", "header", "httpheaders", "requestheaders", "extraheaders")
    private val refererKeys = setOf("referer", "referrer", "httpreferer", "referrerurl", "refererurl")
    private val uaKeys = setOf("useragent", "ua", "httpuseragent")
    private val cookieKeys = setOf("cookie", "cookies", "httpcookie", "httpcookies")
    /** الأولوية: اسم الملف قبل العنوان. */
    private val fileNameKeys = setOf("filename", "file", "displayname", "outputfilename")
    private val titleKeys = setOf("title", "name", "videotitle", "episodetitle")

    /** طلب إضافة من نية VIEW أو SEND، أو null إن لم يكن فيها رابط تنزيل. */
    fun request(intent: Intent): AddRequest? {
        val text = when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.dataString
            else -> null
        }?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val extras = runCatching { intent.extras }.getOrNull()
        return fromExtras(text, extras, intent.type)
    }

    internal fun fromExtras(text: String, extras: Bundle?, mime: String?): AddRequest {
        val headers = ArrayList<Pair<String, String>>()
        var referer: String? = null
        var ua: String? = null
        var cookie: String? = null
        var fileName: String? = null
        var title: String? = null
        val keys = runCatching { extras?.keySet()?.toList() }.getOrNull().orEmpty().take(MAX_EXTRAS)
        for (key in keys) {
            @Suppress("DEPRECATION")
            val value = runCatching { extras?.get(key) }.getOrNull() ?: continue
            when (val k = norm(key)) {
                in headerKeys -> headers += headerPairs(value)
                in refererKeys -> asText(value)?.takeIf(::isWebUrl)?.let { referer = it }
                in uaKeys -> asText(value)?.let { ua = it }
                in cookieKeys -> asText(value)?.let { cookie = it }
                in fileNameKeys -> asText(value)?.let { fileName = it }
                in titleKeys -> asText(value)?.let { if (k == "title" || title == null) title = it }
            }
        }
        val clean = ExtraHeaders.sanitize(headers)
        // المفاتيح المنفصلة تغلب ما داخل الترويسات لأنها أصرح
        referer = referer ?: ExtraHeaders.get(clean, "Referer")?.takeIf(::isWebUrl)
        ua = ua ?: ExtraHeaders.get(clean, "User-Agent")
        cookie = cookie ?: ExtraHeaders.get(clean, "Cookie")
        val name = (fileName ?: title)?.let(::singleLine)?.takeIf { it.isNotBlank() }
        return AddRequest(
            text = text,
            referer = referer?.let(::singleLine),
            userAgent = ua?.let(::singleLine),
            cookies = cookie?.let(::singleLine),
            headers = ExtraHeaders.without(clean, ExtraHeaders.dedicated),
            suggestedName = name?.take(180),
            mimeType = mime?.takeIf { it.contains('/') && !it.contains('*') },
            external = true,
        )
    }

    /** يقرأ الترويسات بأي صيغة شائعة. */
    internal fun headerPairs(value: Any): List<Pair<String, String>> = when (value) {
        is Bundle -> value.keySet().take(MAX_EXTRAS).mapNotNull { k -> @Suppress("DEPRECATION") asText(value.get(k))?.let { k to it } }
        is Array<*> -> listPairs(value.map { it?.toString() })
        is List<*> -> listPairs(value.map { it?.toString() })
        is Map<*, *> -> value.entries.take(MAX_EXTRAS).mapNotNull { (k, v) -> if (k == null || v == null) null else k.toString() to v.toString() }
        is CharSequence -> lines(value.toString())
        else -> emptyList()
    }

    /** [«اسم: قيمة»…] إن كانت كل العناصر كذلك، وإلا أزواج متتالية [اسم، قيمة…]. */
    private fun listPairs(items: List<String?>): List<Pair<String, String>> {
        val list = items.filterNotNull().take(MAX_EXTRAS * 2)
        if (list.isNotEmpty() && list.all { it.indexOf(':') > 0 } && list.none { isWebUrl(it) }) return list.flatMap(::lines)
        return list.chunked(2).filter { it.size == 2 }.map { it[0] to it[1] }
    }

    private fun lines(text: String): List<Pair<String, String>> = text.lines().mapNotNull { l ->
        l.indexOf(':').takeIf { it > 0 }?.let { l.substring(0, it) to l.substring(it + 1) }
    }

    private fun asText(v: Any?): String? = when (v) {
        is CharSequence -> v.toString()
        is android.net.Uri -> v.toString()
        else -> null
    }?.trim()?.takeIf { it.isNotEmpty() && it.length <= ExtraHeaders.MAX_VALUE }

    private fun singleLine(s: String) = s.replace(Regex("[\\r\\n\\u0000]+"), " ").trim()

    private fun isWebUrl(s: String) = s.startsWith("http://", true) || s.startsWith("https://", true)
}
