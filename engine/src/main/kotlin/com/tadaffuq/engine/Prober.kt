package com.tadaffuq.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.IOException

/**
 * يفحص الرابط بطلب GET لبايت واحد (Range: bytes=0-0) لأن خوادم كثيرة لا تدعم HEAD جيداً.
 * 206 مع Content-Range = يدعم الاستئناف والتقسيم.
 */
class Prober(private val client: OkHttpClient) {

    suspend fun probe(spec: RequestSpec): ProbeResult = withContext(Dispatchers.IO) {
        val request = spec.toRequest().header("Range", "bytes=0-0").get().build()
        val response = try {
            client.executeCancellable(request)
        } catch (e: IOException) {
            throw DownloadException(DownloadError.Network(NetFail.of(e)), e)
        }
        response.use { r ->
            when (r.code) {
                206 -> Unit
                200 -> Unit
                401, 403 -> throw DownloadException(DownloadError.LinkExpired(r.code))
                404, 410 -> throw DownloadException(DownloadError.NotFound)
                416 -> Unit // ملف فارغ على بعض الخوادم
                else -> throw DownloadException(DownloadError.Http(r.code))
            }
            val range = parseContentRange(r.header("Content-Range"))
            val ranged = r.code == 206 && range != null && range.first == 0L
            val total = when {
                ranged -> range!!.total ?: -1
                r.code == 416 -> parseContentRange(r.header("Content-Range"))?.total ?: 0
                else -> r.body?.contentLength() ?: -1
            }
            val mime = r.header("Content-Type")
            val finalUrl = r.request.url.toString()
            ProbeResult(
                finalUrl = finalUrl,
                totalBytes = total,
                acceptRanges = ranged && total > 0,
                etag = r.header("ETag"),
                lastModified = r.header("Last-Modified"),
                fileName = FileNames.resolve(r.header("Content-Disposition"), finalUrl, mime),
                mimeType = mime?.substringBefore(';')?.trim(),
            )
        }
    }

    companion object {
        /**
         * هل يجوز دمج أجزاء من جلسة سابقة مع جلسة جديدة؟ يلزم تطابق الحجم ودليل موثوق على أن
         * المحتوى نفسه: ETag قوي متطابق، أو Last-Modified متطابق دون ETag متعارض.
         * عند غياب أي دليل يُعاد التنزيل بأمان بدل المخاطرة بخلط نسختين.
         */
        fun sameContent(saved: ProbeResult, now: ProbeResult): Boolean {
            if (saved.totalBytes != now.totalBytes) return false
            val strongSaved = saved.etag?.takeUnless { it.startsWith("W/") }
            val strongNow = now.etag?.takeUnless { it.startsWith("W/") }
            if (strongSaved != null && strongNow != null) return strongSaved == strongNow
            if (saved.etag != null && now.etag != null && saved.etag != now.etag) return false
            return saved.lastModified != null && saved.lastModified == now.lastModified
        }
    }
}
