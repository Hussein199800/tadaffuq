package com.tadaffuq.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * قاعدة تنظيم تلقائي: تطابق الامتداد أو المصدر أو الحجم، وتحدد المجلد والأولوية والسرعة
 * وفك الأرشيف بعد الاكتمال. تُطبَّق عند الإضافة بالترتيب، وأول قاعدة مطابقة تُستعمل.
 */
data class Rule(
    val id: Long = System.currentTimeMillis(),
    val name: String,
    /** امتدادات بلا نقطة، مفصولة بفواصل. فارغ = أي امتداد. */
    val extensions: String = "",
    /** جزء من اسم المضيف. فارغ = أي مصدر. */
    val hostContains: String = "",
    /** بالميغابايت، 0 = بلا شرط. */
    val minSizeMb: Long = 0,
    /** مجلد فرعي داخل التنزيلات/تدفق. فارغ = حسب النوع. */
    val folder: String = "",
    val priority: Int = 0,
    val speedLimitKbps: Long = 0,
    val extract: Boolean = false,
    val enabled: Boolean = true,
) {
    fun matches(fileName: String, url: String, sizeBytes: Long): Boolean {
        if (!enabled) return false
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val exts = extensions.split(',', ' ').map { it.trim().trimStart('.').lowercase() }.filter { it.isNotEmpty() }
        if (exts.isNotEmpty() && ext !in exts) return false
        val host = runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("").lowercase()
        if (hostContains.isNotBlank() && !host.contains(hostContains.trim().lowercase())) return false
        if (minSizeMb > 0 && (sizeBytes < 0 || sizeBytes < minSizeMb * 1024 * 1024)) return false
        return true
    }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("extensions", extensions).put("host", hostContains)
        .put("minSizeMb", minSizeMb).put("folder", folder).put("priority", priority).put("speedLimitKbps", speedLimitKbps)
        .put("extract", extract).put("enabled", enabled)

    companion object {
        fun fromJson(o: JSONObject) = Rule(
            o.optLong("id"), o.optString("name"), o.optString("extensions"), o.optString("host"), o.optLong("minSizeMb"),
            o.optString("folder"), o.optInt("priority"), o.optLong("speedLimitKbps"), o.optBoolean("extract"), o.optBoolean("enabled", true),
        )
        fun listFrom(json: String): List<Rule> = runCatching { JSONArray(json).let { a -> (0 until a.length()).map { fromJson(a.getJSONObject(it)) } } }.getOrDefault(emptyList())
        fun listTo(rules: List<Rule>): String = JSONArray().apply { rules.forEach { put(it.toJson()) } }.toString()
        fun firstMatch(rules: List<Rule>, fileName: String, url: String, size: Long) = rules.firstOrNull { it.matches(fileName, url, size) }

        /** مجلد آمن: بلا ../ ولا فواصل غريبة. */
        fun safeFolder(f: String) = f.split('/', '\\').map { it.trim() }.filter { it.isNotEmpty() && it != "." && it != ".." }
            .joinToString("/") { it.replace(Regex("[:*?\"<>|]"), "_") }
    }
}
