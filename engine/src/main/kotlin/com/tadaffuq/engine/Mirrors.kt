package com.tadaffuq.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.IOException

data class MirrorResult(
    val url: String,
    val probe: ProbeResult?,
    /** سرعة العيّنة الفعلية بالبايت/ث، أو 0 إن تعذّر القياس. */
    val bps: Long,
    /** نفس الحجم ونفس بايتات البداية والنهاية والمنتصف مثل المصدر الأساسي. */
    val matches: Boolean,
    val error: DownloadError? = null,
)

/**
 * يختبر المرايا: يفحص كلاً منها، ويقارن ثلاث عيّنات (البداية والمنتصف والنهاية) مع المصدر الأساسي
 * بايتاً ببايت، ويقيس سرعة عيّنة حقيقية. المرآة المختلفة لا تُستعمل أبداً (لا خلط نسخ).
 * المطابقة بالعيّنات دليل قوي لا قاطع؛ البصمة المرجعية (إن وُجدت) تُتحقق بعد الاكتمال.
 */
class MirrorSelector(private val client: OkHttpClient, private val sampleBytes: Int = 64 * 1024) {

    suspend fun rank(primary: RequestSpec, mirrors: List<String>): List<MirrorResult> = coroutineScope {
        val all = (listOf(primary.url) + mirrors).distinct()
        val probes = all.map { u -> async { u to runCatching { Prober(client).probe(primary.copy(url = u)) } } }.awaitAll()
        val ref = probes.first().second.getOrNull()
        val refSamples = ref?.takeIf { it.resumable }?.let { samples(primary, it) }
        probes.map { (u, r) ->
            async {
                val p = r.getOrNull()
                val err = (r.exceptionOrNull() as? DownloadException)?.error
                if (p == null || !p.resumable || ref == null || p.totalBytes != ref.totalBytes || refSamples == null) {
                    return@async MirrorResult(u, p, 0, matches = u == primary.url && p != null, error = err)
                }
                val t0 = System.nanoTime()
                val mine = runCatching { samples(primary.copy(url = u), p) }.getOrNull()
                val secs = (System.nanoTime() - t0) / 1e9
                val bytes = mine?.sumOf { it.size } ?: 0
                MirrorResult(u, p, if (secs > 0) (bytes / secs).toLong() else 0, mine != null && mine.indices.all { mine[it].contentEquals(refSamples[it]) })
            }
        }.awaitAll().sortedWith(compareByDescending<MirrorResult> { it.matches }.thenByDescending { it.bps })
    }

    private suspend fun samples(spec: RequestSpec, p: ProbeResult): List<ByteArray> = withContext(Dispatchers.IO) {
        val n = minOf(sampleBytes.toLong(), p.totalBytes)
        val starts = listOf(0L, (p.totalBytes / 2 - n / 2).coerceAtLeast(0), (p.totalBytes - n).coerceAtLeast(0)).distinct()
        starts.map { s ->
            val req = spec.toRequest(p.finalUrl).header("Range", "bytes=$s-${s + n - 1}").get().build()
            client.executeCancellable(req).use { r ->
                if (r.code != 206) throw IOException("no range ${r.code}")
                val cr = parseContentRange(r.header("Content-Range"))
                if (cr?.first != s) throw IOException("bad range")
                r.body!!.bytes()
            }
        }
    }
}
