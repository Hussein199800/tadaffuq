package com.tadaffuq.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** محلل JSON صغير ومحدود (بلا اعتماديات) يكفي لردود GitHub وHugging Face. */
object MiniJson {
    fun parse(s: String): Any? = Parser(s).run { val v = value(); ws(); v }

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            return when (val c = s[i]) {
                '{' -> obj(); '[' -> arr(); '"' -> str()
                't' -> { i += 4; true }; 'f' -> { i += 5; false }; 'n' -> { i += 4; null }
                else -> if (c == '-' || c.isDigit()) num() else error("bad json at $i")
            }
        }
        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>(); i++; ws()
            if (s[i] == '}') { i++; return m }
            while (true) { ws(); val k = str(); ws(); i++; m[k] = value(); ws(); if (s[i++] == '}') return m }
        }
        fun arr(): List<Any?> {
            val l = ArrayList<Any?>(); i++; ws()
            if (s[i] == ']') { i++; return l }
            while (true) { l += value(); ws(); if (s[i++] == ']') return l }
        }
        fun str(): String {
            val b = StringBuilder(); i++
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return b.toString()
                    '\\' -> when (val e = s[i++]) {
                        'n' -> b.append('\n'); 't' -> b.append('\t'); 'r' -> b.append('\r'); 'b' -> b.append('\b'); 'f' -> b.append('\u000C')
                        'u' -> { b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> b.append(e)
                    }
                    else -> b.append(c)
                }
            }
        }
        fun num(): Number {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] in "-+.eE")) i++
            val t = s.substring(st, i)
            return if (t.any { it in ".eE" }) t.toDouble() else t.toLong()
        }
    }
}

@Suppress("UNCHECKED_CAST")
private fun Any?.obj() = this as? Map<String, Any?> ?: emptyMap()
@Suppress("UNCHECKED_CAST")
private fun Any?.list() = this as? List<Any?> ?: emptyList()
private fun Map<String, Any?>.str(k: String) = this[k] as? String
private fun Map<String, Any?>.long(k: String) = (this[k] as? Number)?.toLong()

class SourceException(val kind: Kind, val resetAtEpochSec: Long? = null) : Exception(kind.name) {
    enum class Kind { NOT_FOUND, RATE_LIMITED, UNAUTHORIZED, NETWORK, BAD_INPUT }
}

data class ReleaseAsset(val name: String, val size: Long, val url: String, val sha256: String?, val downloads: Long)
data class Release(val tag: String, val name: String, val prerelease: Boolean, val publishedAt: String, val notes: String, val assets: List<ReleaseAsset>)

/** إصدارات GitHub: الأصول، وبصمات SHA-256 (حقل digest أو ملفات SHA256SUMS)، واختيار ما يناسب معمارية الجهاز. */
class GitHubReleases(private val client: OkHttpClient, private val api: String = "https://api.github.com") {

    /** يقبل owner/repo أو رابط github.com. */
    fun parseRepo(input: String): String? {
        val t = input.trim().removeSuffix("/").removeSuffix(".git")
        Regex("""(?:https?://)?(?:www\.)?github\.com/([\w.-]+)/([\w.-]+)""").find(t)?.let { return "${it.groupValues[1]}/${it.groupValues[2]}" }
        return Regex("""^([\w.-]+)/([\w.-]+)$""").find(t)?.let { "${it.groupValues[1]}/${it.groupValues[2]}" }
    }

    suspend fun releases(repo: String, perPage: Int = 15): List<Release> = withContext(Dispatchers.IO) {
        val r = parseRepo(repo) ?: throw SourceException(SourceException.Kind.BAD_INPUT)
        val body = get("$api/repos/$r/releases?per_page=$perPage")
        MiniJson.parse(body).list().map { it.obj() }.map { rel ->
            val assets = rel["assets"].list().map { it.obj() }.map { a ->
                ReleaseAsset(a.str("name").orEmpty(), a.long("size") ?: -1, a.str("browser_download_url").orEmpty(),
                    a.str("digest")?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:"), a.long("download_count") ?: 0)
            }
            Release(rel.str("tag_name").orEmpty(), rel.str("name").orEmpty().ifBlank { rel.str("tag_name").orEmpty() }, rel["prerelease"] == true,
                rel.str("published_at").orEmpty(), rel.str("body").orEmpty(), assets)
        }
    }

    /** يقرأ ملفات البصمات المنشورة مع الإصدار (SHA256SUMS أو *.sha256) ويربطها بأسماء الأصول. */
    suspend fun checksums(release: Release): Map<String, String> = withContext(Dispatchers.IO) {
        val out = HashMap<String, String>()
        release.assets.filter { it.sha256 != null }.forEach { out[it.name] = it.sha256!! }
        release.assets.filter { val n = it.name.lowercase(); (n.contains("sha256") || n.endsWith(".sha256sum")) && it.size in 1..1_000_000 }.forEach { sums ->
            runCatching { get(sums.url) }.getOrNull()?.lineSequence()?.forEach { line ->
                val m = Regex("""^([a-fA-F0-9]{64})\s+\*?(.+)$""").find(line.trim())
                if (m != null) out.putIfAbsent(m.groupValues[2].trim(), m.groupValues[1].lowercase())
                else if (Regex("^[a-fA-F0-9]{64}$").matches(line.trim())) out.putIfAbsent(sums.name.removeSuffix(".sha256").removeSuffix(".sha256sum"), line.trim().lowercase())
            }
        }
        out
    }

    private fun get(url: String): String {
        val req = Request.Builder().url(url).header("Accept", "application/vnd.github+json").header("User-Agent", "Tadaffuq").build()
        try {
            client.newCall(req).execute().use { r ->
                when {
                    r.code == 404 -> throw SourceException(SourceException.Kind.NOT_FOUND)
                    (r.code == 403 || r.code == 429) && r.header("X-RateLimit-Remaining") == "0" ->
                        throw SourceException(SourceException.Kind.RATE_LIMITED, r.header("X-RateLimit-Reset")?.toLongOrNull())
                    !r.isSuccessful -> throw SourceException(SourceException.Kind.NETWORK)
                }
                return r.body!!.string()
            }
        } catch (e: java.io.IOException) { throw SourceException(SourceException.Kind.NETWORK) }
    }

    companion object {
        /**
         * يرتّب الأصول حسب ملاءمتها لمعماريات الجهاز (Build.SUPPORTED_ABIS بالترتيب):
         * APK يطابق المعمارية الأولى، ثم الأخرى، ثم «universal»، ثم بقية APK؛ وغير APK في الآخر.
         */
        fun rankForAbis(assets: List<ReleaseAsset>, abis: List<String>): List<ReleaseAsset> {
            val aliases = mapOf(
                "arm64-v8a" to listOf("arm64-v8a", "arm64", "aarch64", "armv8"),
                "armeabi-v7a" to listOf("armeabi-v7a", "armeabi", "armv7", "arm32"),
                "x86_64" to listOf("x86_64", "x64", "amd64"),
                "x86" to listOf("x86", "i686"),
            )
            fun score(a: ReleaseAsset): Int {
                val n = a.name.lowercase()
                val apk = n.endsWith(".apk") || n.endsWith(".apks") || n.endsWith(".xapk")
                if (!apk) return 1000
                abis.forEachIndexed { i, abi -> if (aliases[abi].orEmpty().any { n.contains(it) } && !(abi == "x86" && n.contains("x86_64"))) return i * 10 }
                if (n.contains("universal") || n.contains("all")) return 100
                val otherArch = aliases.values.flatten().any { n.contains(it) }
                return if (otherArch) 900 else 200
            }
            return assets.sortedBy(::score)
        }
    }
}

data class HfFile(val path: String, val size: Long, val sha256: String?, val url: String) {
    val isGguf get() = path.endsWith(".gguf", true)
    /** «model-00001-of-00003.gguf» → «model» حتى تُجمع الأجزاء معاً. */
    val shardGroup: String? get() = Regex("""^(.*)-(\d{5})-of-(\d{5})\.gguf$""", RegexOption.IGNORE_CASE).find(path)?.groupValues?.get(1)
}

/**
 * ملفات Hugging Face: شجرة المستودع مع أحجام وبصمات LFS (SHA-256) تُستعمل للتحقق بعد التنزيل.
 * الرمز (اختياري، للنماذج المقيّدة) يُرسل إلى huggingface.co فقط؛ OkHttp يحذف ترويسة Authorization
 * عند التحويل إلى مضيف آخر (روابط CDN الموقّعة)، فلا يتسرب الرمز.
 */
class HuggingFace(private val client: OkHttpClient, private val base: String = "https://huggingface.co") {
    fun parseRepo(input: String): String? {
        val t = input.trim().removeSuffix("/")
        Regex("""(?:https?://)?huggingface\.co/(?:models/)?([\w.-]+/[\w.-]+)""").find(t)?.let { return it.groupValues[1] }
        return Regex("""^([\w.-]+/[\w.-]+)$""").find(t)?.groupValues?.get(1)
    }

    suspend fun files(repo: String, token: String?, revision: String = "main"): List<HfFile> = withContext(Dispatchers.IO) {
        val r = parseRepo(repo) ?: throw SourceException(SourceException.Kind.BAD_INPUT)
        val req = Request.Builder().url("$base/api/models/$r/tree/$revision?recursive=1").header("User-Agent", "Tadaffuq")
            .apply { if (!token.isNullOrBlank()) header("Authorization", "Bearer $token") }.build()
        val body = try {
            client.newCall(req).execute().use { resp ->
                when (resp.code) {
                    401, 403 -> throw SourceException(SourceException.Kind.UNAUTHORIZED)
                    404 -> throw SourceException(SourceException.Kind.NOT_FOUND)
                    429 -> throw SourceException(SourceException.Kind.RATE_LIMITED, resp.header("Retry-After")?.toLongOrNull()?.let { System.currentTimeMillis() / 1000 + it })
                }
                if (!resp.isSuccessful) throw SourceException(SourceException.Kind.NETWORK)
                resp.body!!.string()
            }
        } catch (e: java.io.IOException) { throw SourceException(SourceException.Kind.NETWORK) }
        MiniJson.parse(body).list().map { it.obj() }.filter { it.str("type") == "file" }.map { f ->
            val path = f.str("path").orEmpty()
            val lfs = f["lfs"].obj()
            HfFile(path, lfs.long("size") ?: f.long("size") ?: -1, lfs.str("oid")?.takeIf { it.length == 64 },
                "$base/$r/resolve/$revision/" + path.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") })
        }
    }

    companion object {
        fun shards(files: List<HfFile>, group: String) = files.filter { it.shardGroup == group }.sortedBy { it.path }
    }
}
