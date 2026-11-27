package com.tadaffuq.engine

/**
 * ترويسات HTTP إضافية تصل من تطبيق آخر («فتح باستخدام») أو يكتبها المستخدم: سطر لكل ترويسة «الاسم: القيمة».
 * تُنقّى قبل الاستعمال: لا ترويسات يديرها المحرك نفسه (Range وHost…)، ولا أسماء غير صالحة، ولا أسطر داخل قيمة.
 */
object ExtraHeaders {
    /** يديرها المحرك أو OkHttp؛ إرسالها من الخارج يفسد التقسيم أو الاستئناف. */
    private val managed = setOf(
        "host", "content-length", "range", "if-range", "accept-encoding", "connection", "transfer-encoding", "te",
        "upgrade", "keep-alive", "proxy-connection", "proxy-authorization", "expect", "trailer", "if-match",
        "if-none-match", "if-modified-since", "if-unmodified-since",
    )
    /** لها حقول خاصة في المهمة (Referer وUser-Agent والكوكيز). */
    val dedicated = setOf("referer", "user-agent", "cookie")
    private val token = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]{1,64}$")
    const val MAX_COUNT = 30
    const val MAX_VALUE = 8192

    /** ترويسة تحمل سراً: لا تُرسل إلا إلى المضيف الذي التُقطت له ولا عبر HTTP بعد HTTPS. */
    fun isSensitive(name: String): Boolean {
        val n = name.lowercase()
        if (n == "authorization" || n == "cookie") return true
        return listOf("token", "auth", "key", "secret", "session", "csrf", "xsrf", "password", "signature").any { n.contains(it) }
    }

    /** يقبل ترويسة واحدة أو يرفضها؛ القيمة بعد التنقية أو null. */
    fun clean(name: String, value: String): Pair<String, String>? {
        val n = name.trim()
        val v = value.trim()
        if (!token.matches(n) || n.lowercase() in managed) return null
        if (v.isEmpty() || v.length > MAX_VALUE || v.any { it == '\r' || it == '\n' || it == '\u0000' }) return null
        return n to v
    }

    /** يُنقّي مجموعة كاملة؛ الاسم لا يتكرر (الأخير يغلب بلا تمييز لحالة الأحرف) والعدد محدود. */
    fun sanitize(headers: Iterable<Pair<String, String>>): Map<String, String> {
        val out = LinkedHashMap<String, Pair<String, String>>()
        for ((k, v) in headers) {
            val c = clean(k, v) ?: continue
            out[c.first.lowercase()] = c
            if (out.size >= MAX_COUNT) break
        }
        return out.values.associate { it }
    }

    /** «الاسم: القيمة» في كل سطر؛ الأسطر الفارغة أو بلا «:» تُتجاهل. */
    fun parse(text: String?): Map<String, String> = if (text.isNullOrBlank()) emptyMap() else sanitize(
        text.lineSequence().mapNotNull { l -> l.indexOf(':').takeIf { it > 0 }?.let { l.substring(0, it) to l.substring(it + 1) } }.asIterable()
    )

    fun format(headers: Map<String, String>): String = headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }

    /** الأسطر التي لم تُقبل (لعرض تنبيه للمستخدم). */
    fun rejectedLines(text: String): List<String> = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.filter { l ->
        val i = l.indexOf(':')
        i <= 0 || clean(l.substring(0, i), l.substring(i + 1)) == null
    }

    fun get(headers: Map<String, String>, name: String): String? = headers.entries.firstOrNull { it.key.equals(name, true) }?.value
    fun without(headers: Map<String, String>, names: Set<String>): Map<String, String> = headers.filterKeys { it.lowercase() !in names }
}
