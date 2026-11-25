package com.tadaffuq.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import okhttp3.OkHttpClient
import java.io.IOException

/**
 * يفحص الرابط بطلب GET لبايت واحد (Range: bytes=0-0) لأن خوادم كثيرة لا تدعم HEAD جيداً.
 * 206 مع Content-Range = يدعم الاستئناف والتقسيم.
 */
class Prober(private val client: OkHttpClient) {

    suspend fun probe(spec: RequestSpec): ProbeResult = withContext(Dispatchers.IO) {
        val request = spec.toRequest().header("Range", "bytes=0-0").get().build()
        val ranged = try { client.withResponse(request) { r -> read(r, ranged = true) } }
        catch (e: IOException) { throw DownloadException(DownloadError.Network(NetFail.of(e)), e) }
        if (ranged != null) return@withContext ranged
        // الخادم لا يقدّم مدى موثوقاً (416 لملف غير فارغ، أو 206 بلا إجمالي، أو ترميز ضغط):
        // نفحص بطلب كامل نقرأ ترويساته فقط، ونعامل الملف كغير قابل للتقسيم ولا الاستئناف.
        val plain = spec.toRequest().get().build()
        try { client.withResponse(plain) { r -> read(r, ranged = false)!! } }
        catch (e: IOException) { throw DownloadException(DownloadError.Network(NetFail.of(e)), e) }
    }

    /** يعيد null حين يلزم الرجوع لفحص بلا Range. */
    private fun read(r: okhttp3.Response, ranged: Boolean): ProbeResult? {
        when (r.code) {
            200, 206 -> Unit
            416 -> {
                val total = parseContentRange(r.header("Content-Range"))?.total
                    ?: Regex("""\*/(\d+)""").find(r.header("Content-Range").orEmpty())?.groupValues?.get(1)?.toLongOrNull()
                if (total == 0L) return result(r, 0, ranged = false) // ملف فارغ فعلاً
                if (ranged) return null
                throw DownloadException(DownloadError.Http(416))
            }
            401, 403 -> throw DownloadException(DownloadError.LinkExpired(r.code))
            404, 410 -> throw DownloadException(DownloadError.NotFound)
            else -> throw DownloadException(DownloadError.Http(r.code))
        }
        val encoded = r.header("Content-Encoding")?.let { !it.equals("identity", true) } == true
        if (r.code == 206) {
            val cr = parseContentRange(r.header("Content-Range"))
            val ok = cr != null && cr.valid && cr.first == 0L && cr.last == 0L && cr.total != null && cr.total > 0 && !encoded
            return if (ok) result(r, cr!!.total!!, ranged = true) else if (ranged) null else result(r, -1, false)
        }
        // 200: الخادم تجاهل Range؛ الحجم من Content-Length إن لم يكن مضغوطاً
        val len = if (encoded) -1 else r.body?.contentLength() ?: -1
        return result(r, len, ranged = false)
    }

    private fun result(r: okhttp3.Response, total: Long, ranged: Boolean): ProbeResult {
        val mime = r.header("Content-Type")
        val finalUrl = r.request.url.toString()
        return ProbeResult(
            finalUrl = finalUrl, totalBytes = total, acceptRanges = ranged && total > 0,
            etag = r.header("ETag"), lastModified = r.header("Last-Modified"),
            fileName = FileNames.resolve(r.header("Content-Disposition"), finalUrl, mime),
            mimeType = mime?.substringBefore(';')?.trim(),
        )
    }

    companion object {
        /**
         * هل يجوز دمج أجزاء من جلسة سابقة مع جلسة جديدة؟ يلزم تطابق الحجم ودليل موثوق على أن
         * المحتوى نفسه: ETag قوي متطابق، أو Last-Modified متطابق دون ETag متعارض.
         * عند غياب أي دليل يُعاد التنزيل بأمان بدل المخاطرة بخلط نسختين.
         */
        fun sameContent(saved: ProbeResult, now: ProbeResult): Boolean {
            if (saved.totalBytes != now.totalBytes) return false
            val a = saved.strongEtag
            val b = now.strongEtag
            // ETag قوي في أي طرف: يجب أن يوجد في الطرفين ويتطابق تماماً (اختفاؤه أو ظهوره = لا دليل)
            if (a != null || b != null) return a != null && a == b
            if (saved.etag != null && now.etag != null && saved.etag != now.etag) return false
            // بلا ETag قوي: Last-Modified معلن في الطرفين ومتطابق، وإلا بداية آمنة
            return saved.lastModified != null && saved.lastModified == now.lastModified
        }
    }
}
