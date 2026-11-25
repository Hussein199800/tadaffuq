package com.tadaffuq.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.URI
import java.net.URLDecoder
import javax.net.ssl.TrustManager

/** عنوان FTP/SFTP مفكك. كلمة المرور لا تُحفظ في الرابط أبداً بل في مخزن الأسرار. */
data class RemoteTarget(
    val scheme: String,
    val host: String,
    val port: Int,
    val user: String,
    val path: String,
) {
    /** مفتاح ثابت لكلمة المرور في مخزن الأسرار. */
    val credentialKey: String get() = "$scheme://$user@$host:$port"

    companion object {
        fun parse(url: String): RemoteTarget? = runCatching {
            val u = URI(url.trim())
            val scheme = u.scheme?.lowercase() ?: return null
            if (scheme !in setOf("ftp", "ftps", "sftp")) return null
            val user = u.rawUserInfo?.substringBefore(':')?.let { URLDecoder.decode(it, "UTF-8") }
                ?: if (scheme == "sftp") "" else "anonymous"
            val port = if (u.port > 0) u.port else if (scheme == "sftp") 22 else 21
            RemoteTarget(scheme, u.host ?: return null, port, user, URLDecoder.decode(u.rawPath.orEmpty(), "UTF-8").ifBlank { "/" })
        }.getOrNull()

        /** كلمة المرور إن كانت داخل الرابط (ftp://u:p@h)، لنقلها إلى مخزن الأسرار وحذفها من الرابط. */
        fun passwordInUrl(url: String): String? = runCatching {
            URI(url.trim()).rawUserInfo?.takeIf { ':' in it }?.substringAfter(':')?.let { URLDecoder.decode(it, "UTF-8") }
        }.getOrNull()

        fun stripPassword(url: String): String = runCatching {
            val u = URI(url.trim())
            val info = u.rawUserInfo ?: return url
            if (':' !in info) return url
            URI(u.scheme, info.substringBefore(':').let { URLDecoder.decode(it, "UTF-8") }, u.host, u.port, u.path, u.query, u.fragment).toString()
        }.getOrDefault(url)
    }
}

/** يزوّد كلمات المرور ويقرّر الثقة بمفاتيح SSH. */
interface Credentials {
    fun password(target: RemoteTarget): String?
    /** البصمة المحفوظة لهذا المضيف، أو null إن لم يُوثق به بعد. */
    fun knownHostKey(host: String, port: Int): String?
    /** للاختبارات فقط: شهادات مخصصة لـ FTPS. في التطبيق تُستعمل شهادات النظام دائماً. */
    val testTrustManager: TrustManager? get() = null
}

/** واجهة موحدة لكل البروتوكولات. */
interface Downloader {
    suspend fun run(): ProbeResult
}

object Protocols {
    fun isRemote(url: String) = RemoteTarget.parse(url) != null
    fun isSupported(url: String) = LinkTools.isSupported(url) || isRemote(url)

    suspend fun probe(client: OkHttpClient, spec: RequestSpec, creds: Credentials): ProbeResult {
        val t = RemoteTarget.parse(spec.url) ?: return Prober(client).probe(spec)
        return when (t.scheme) {
            "sftp" -> SftpDownloader.probe(t, creds)
            else -> FtpDownloader.probe(t, creds)
        }
    }

    fun downloader(
        client: OkHttpClient, spec: RequestSpec, file: File, config: DownloadConfig, saved: ResumeState?,
        taskLimiter: RateLimiter, globalLimiter: RateLimiter, listener: DownloadListener, creds: Credentials,
    ): Downloader {
        val t = RemoteTarget.parse(spec.url)
            ?: return object : Downloader {
                override suspend fun run() = HttpDownloader(client, spec, file, config, saved, taskLimiter, globalLimiter, listener).run()
            }
        return object : Downloader {
            override suspend fun run(): ProbeResult {
                val opener: StreamOpener = if (t.scheme == "sftp") SftpDownloader(t, creds) else FtpDownloader(t, creds)
                return SingleStreamDownload(opener, file, config, saved, taskLimiter, globalLimiter, listener).run()
            }
        }
    }
}

/** يفتح بث الملف من موضع معيّن (FTP REST أو SFTP seek) ويُغلق الجلسة بعده. */
interface StreamOpener {
    suspend fun probe(): ProbeResult
    /** يعيد البث وعملية إغلاق الجلسة. */
    suspend fun open(offset: Long): Pair<InputStream, () -> Unit>
}

/**
 * تنزيل باتصال واحد قابل للاستئناف (FTP/SFTP): الخوادم كثيراً ما تحدّ الجلسات المتزامنة،
 * فالاتصال الواحد أسلم. الاستئناف يتطلب تطابق الحجم ووقت التعديل، وإلا يبدأ من جديد.
 */
internal class SingleStreamDownload(
    private val opener: StreamOpener,
    private val file: File,
    private val config: DownloadConfig,
    private val saved: ResumeState?,
    private val taskLimiter: RateLimiter,
    private val globalLimiter: RateLimiter,
    private val listener: DownloadListener,
) {
    suspend fun run(): ProbeResult = withContext(Dispatchers.IO) {
        val probe = opener.probe()
        val prev = saved?.segments?.singleOrNull()
        val resume = prev != null && file.exists() && probe.resumable && saved.probe.totalBytes == probe.totalBytes &&
            saved.probe.lastModified != null && saved.probe.lastModified == probe.lastModified && prev.downloaded in 1 until probe.totalBytes
        var pos = if (resume) prev!!.downloaded else 0L
        listener.onStart(probe, restarted = prev != null && prev.downloaded > 0 && !resume)
        val end = if (probe.totalBytes > 0) probe.totalBytes - 1 else -1
        val raf = RandomAccessFile(file, "rw").apply { if (!resume) setLength(0) }
        fun state() = ResumeState(probe, listOf(SegmentState(0, 0, end, pos)))
        val t0 = System.nanoTime()
        var lastTick = t0; var lastBytes = 0L; var session = 0L; var maxBps = 0L; var lastPersist = t0
        var failures = 0
        try {
            while (end < 0 || pos <= end) {
                val before = pos
                try {
                    val (input, close) = opener.open(pos)
                    try {
                        val buf = ByteArray(config.bufferBytes)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            taskLimiter.acquire(n); globalLimiter.acquire(n)
                            raf.seek(pos); raf.write(buf, 0, n)
                            pos += n; session += n
                            listener.onBytes(n.toLong())
                            val now = System.nanoTime()
                            if (now - lastTick >= 1_000_000_000L) {
                                val speed = ((session - lastBytes) * 1e9 / (now - lastTick)).toLong()
                                maxBps = maxOf(maxBps, speed); lastTick = now; lastBytes = session
                                listener.onProgress(Snapshot(pos, probe.totalBytes, speed, (session * 1e9 / (now - t0)).toLong(), maxBps,
                                    if (probe.totalBytes > 0 && speed > 0) (probe.totalBytes - pos) / speed else -1, 1, state().segments))
                            }
                            if (now - lastPersist >= config.persistIntervalMs * 1_000_000L) {
                                lastPersist = now; raf.fd.sync(); listener.persist(state())
                            }
                        }
                    } finally { runCatching { input.close() }; runCatching { close() } }
                    if (end < 0) break
                    if (pos <= end) throw IOException("stream ended early")
                } catch (e: DownloadException) {
                    throw e
                } catch (e: IOException) {
                    currentCoroutineContext().ensureActive()
                    failures = if (pos > before) 1 else failures + 1
                    if (failures > config.maxRetries) throw DownloadException(DownloadError.Network(NetFail.of(e)), e)
                    if (!probe.resumable) { pos = 0; raf.setLength(0) }
                    val wait = (config.baseRetryDelayMs shl (failures - 1).coerceAtMost(10)).coerceAtMost(config.maxRetryDelayMs)
                    listener.onRetry(0, failures, DownloadError.Network(NetFail.of(e)), wait)
                    kotlinx.coroutines.delay(wait)
                }
            }
            if (end < 0) raf.setLength(pos)
            if (probe.totalBytes > 0 && raf.length() != probe.totalBytes) throw DownloadException(DownloadError.Storage("size mismatch"))
        } finally {
            withContext(NonCancellable) {
                runCatching { raf.fd.sync() }
                listener.persist(state())
                runCatching { raf.close() }
            }
        }
        probe
    }
}
