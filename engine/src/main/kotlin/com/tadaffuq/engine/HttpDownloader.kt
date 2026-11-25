package com.tadaffuq.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.random.Random

/** ما يُحفظ لاستئناف المهمة بأمان. */
data class ResumeState(val probe: ProbeResult, val segments: List<SegmentState>)

interface DownloadListener {
    /** بعد الفحص. restarted = كان هناك تقدم محفوظ لكنه رُفض لأن الملف تغيّر أو لا يدعم الاستئناف. */
    fun onStart(probe: ProbeResult, restarted: Boolean) {}
    fun onProgress(snapshot: Snapshot) {}
    /** يُستدعى بعد مزامنة البيانات مع القرص، فالأرقام المحفوظة لا تسبق المكتوب فعلاً. */
    suspend fun persist(state: ResumeState) {}
    fun onBytes(count: Long) {}
    fun onRetry(segment: Int, attempt: Int, error: DownloadError, delayMs: Long) {}
}

/**
 * محرك HTTP متعدد الاتصالات:
 * - كتابة مباشرة في مواضع الملف (FileChannel positional write) بلا ملفات مؤقتة للأجزاء.
 * - تقسيم ديناميكي: كلما انتهى اتصال يُقسم أكبر جزء متبقٍّ ليبقى العدد المستهدف عاملاً.
 * - If-Range يمنع خلط ملفين مختلفين إن تغيّر الملف على الخادم.
 * - إعادة محاولة بتأخير تصاعدي مع عشوائية، واحترام Retry-After.
 * الإلغاء (cancel) = إيقاف مؤقت: يُحفظ التقدم في finally.
 */
class HttpDownloader(
    private val client: OkHttpClient,
    private val spec: RequestSpec,
    private val file: File,
    private val config: DownloadConfig = DownloadConfig(),
    private val saved: ResumeState? = null,
    private val taskLimiter: RateLimiter = RateLimiter(),
    private val globalLimiter: RateLimiter = RateLimiter(),
    private val listener: DownloadListener = object : DownloadListener {},
    /** المساحة المتاحة لمجلد موجود (يُنشأ المجلد قبل السؤال)؛ ‎-1 إن تعذّر الاستعلام. */
    private val usableSpace: (File) -> Long = { dir -> runCatching { dir.usableSpace }.getOrDefault(-1L) },
    /** الحد الأعلى للاتصالات قابلاً للتغيير أثناء التنزيل (من لوحة التحكم السريعة). */
    private val liveMaxConnections: () -> Int = { config.maxConnections },
) {
    private val segments = mutableListOf<Segment>()
    private val sessionBytes = AtomicLong()
    /** عدد ردود 429/503: الخادم يطلب تخفيف الضغط. */
    private val pushbacks = java.util.concurrent.atomic.AtomicInteger()
    private val runningWorkers = java.util.concurrent.atomic.AtomicInteger()
    @Volatile private var sharedTarget = 1

    suspend fun run(): ProbeResult = withContext(Dispatchers.IO) {
        val probe = Prober(client).probe(spec)
        var restarted = false
        // الملف الجزئي يُقبل فقط إن كان طوله بالضبط الحجم المحجوز (نحجزه كاملاً عند البدء)؛
        // ملف مقتطع أو مستبدل يعني أن «المنجز» في الجدول قد لا يكون على القرص.
        val reuse = saved?.takeIf {
            it.segments.isNotEmpty() && file.isFile && file.length() == probe.totalBytes && probe.resumable && it.probe.resumable &&
                Prober.sameContent(it.probe, probe) && SegmentPlanner.isConsistent(it.segments, probe.totalBytes)
        }
        if (reuse != null) {
            segments += reuse.segments.map(Segment::of)
        } else {
            restarted = saved?.segments?.any { it.downloaded > 0 } == true
            segments += if (probe.resumable) {
                val n = if (config.connections > 0) config.connections
                else SegmentPlanner.autoConnections(probe.totalBytes, config.maxConnections, config.minSplitBytes)
                SegmentPlanner.initial(probe.totalBytes, n)
            } else {
                listOf(Segment(0, 0, if (probe.totalBytes > 0) probe.totalBytes - 1 else -1))
            }
        }
        listener.onStart(probe, restarted)

        // المجلد أولاً، ثم قابلية الكتابة، ثم المساحة الفعلية لوحدة التخزين (P0-01).
        val dir = file.absoluteFile.parentFile ?: throw DownloadException(DownloadError.Storage("no parent"))
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw DownloadException(DownloadError.Storage("cannot create ${dir.path}"))
        if (!dir.canWrite()) throw DownloadException(DownloadError.Storage("EACCES ${dir.path}"))
        val remaining = if (probe.totalBytes > 0) probe.totalBytes - segments.sumOf { it.downloaded } else 0
        val ticket = SpaceLedger.reserve(dir, remaining)
        reservation = ticket
        if (probe.totalBytes > 0) {
            val available = usableSpace(dir)
            if (available >= 0) {
                // ما حجزته تنزيلات أخرى جارية على وحدة التخزين نفسها ولم تكتبه بعد
                val others = SpaceLedger.reservedByOthers(dir, ticket)
                if (available - others < remaining) {
                    SpaceLedger.release(ticket); reservation = null
                    throw DownloadException(DownloadError.InsufficientSpace(remaining + others, available, dir.path))
                }
            } // تعذّر الاستعلام (‎-1): نتابع، ونعالج ENOSPC أثناء الكتابة
        }
        spacePath = dir.path

        val raf = try {
            RandomAccessFile(file, "rw").apply {
                if (reuse == null) {
                    setLength(0)
                    if (probe.totalBytes > 0) setLength(probe.totalBytes)
                }
            }
        } catch (e: IOException) {
            throw DownloadException(DownloadError.Storage(e.message ?: "io"), e)
        }

        try {
            supervise(probe, raf.channel)
            if (probe.totalBytes <= 0) raf.setLength(segments.single().position)
            if (probe.totalBytes > 0 && raf.length() != probe.totalBytes) {
                throw DownloadException(DownloadError.Storage("size mismatch"))
            }
        } finally {
            withContext(NonCancellable) {
                // لقطة ثابتة أولاً ثم تثبيت القرص، ولا يُحفظ تقدم لم يتأكد تثبيته (P0-04)
                val snapshot = segments.map { it.state() }
                val synced = runCatching { raf.channel.force(false) }.isSuccess
                if (synced) listener.persist(ResumeState(probe, snapshot))
                runCatching { raf.close() }
                reservation?.let(SpaceLedger::release)
            }
        }
        probe
    }

    private var reservation: SpaceLedger.Ticket? = null
    private var spacePath: String = ""

    private suspend fun supervise(probe: ProbeResult, channel: FileChannel) = coroutineScope {
        val jobs = HashMap<Segment, Job>()
        var target = when {
            !probe.resumable -> 1
            config.connections > 0 -> config.connections
            else -> maxOf(SegmentPlanner.autoConnections(probe.totalBytes, config.maxConnections, config.minSplitBytes), 1)
        }
        val tune = AutoTune(config, probe.resumable && config.connections == 0)

        fun launchFor(seg: Segment) {
            seg.active = true
            runningWorkers.incrementAndGet()
            jobs[seg] = launch(Dispatchers.IO) {
                try { runSegment(seg, probe, channel) } finally { runningWorkers.decrementAndGet() }
            }
        }
        var seenPushbacks = 0

        val startNs = System.nanoTime()
        var lastTickNs = startNs
        var lastTickBytes = 0L
        var lastPersistNs = startNs
        var ema = 0.0
        var maxBps = 0L
        var speed = 0L

        while (true) {
            jobs.entries.removeAll { (seg, job) -> (job.isCompleted).also { if (it) seg.active = false } }
            if (segments.all { it.done }) break
            // تراجع تلقائي: كل دفعة 429/503 جديدة تنصّف الاتصالات وتوقف الضبط التلقائي
            val pb = pushbacks.get()
            if (pb > seenPushbacks) {
                seenPushbacks = pb
                target = maxOf(1, minOf(target, jobs.size) / 2)
                tune.stop()
            }
            sharedTarget = target

            if (jobs.isEmpty() || probe.resumable) {
                while (jobs.size < target) {
                    val idle = segments.firstOrNull { !it.done && !it.active }
                    val next = idle ?: if (probe.resumable) (if (config.dynamicSplit) SegmentPlanner.splitLargest(segments, config.minSplitBytes) else null) else null
                    next ?: break
                    launchFor(next)
                }
            }

            delay(TICK_MS)
            val now = System.nanoTime()
            if (now - lastTickNs >= 1_000_000_000L) {
                val bytes = sessionBytes.get()
                speed = ((bytes - lastTickBytes) * 1e9 / (now - lastTickNs)).toLong()
                lastTickBytes = bytes
                lastTickNs = now
                ema = if (ema == 0.0) speed.toDouble() else ema * 0.7 + speed * 0.3
                maxBps = maxOf(maxBps, speed)
                target = tune.next(target, speed, now, jobs.size)
                // تغيير الحد أثناء التنزيل: الخفض فوري (تتخلى الاتصالات الزائدة عن أجزائها)،
                // والرفع يعيد تشغيل الضبط التلقائي ليجرّب اتصالات أكثر إن أفادت
                val cap = liveMaxConnections().coerceIn(1, DownloadConfig.MAX_CONNECTIONS)
                if (cap != tune.cap) { if (cap > tune.cap) tune.reopen(); tune.cap = cap }
                if (target > cap) target = cap
                toYield.set((jobs.size - target).coerceAtLeast(0))
                val downloaded = segments.sumOf { it.downloaded }
                val remaining = probe.totalBytes - downloaded
                listener.onProgress(
                    Snapshot(
                        downloaded = downloaded,
                        total = probe.totalBytes,
                        speedBps = speed,
                        averageBps = (bytes * 1e9 / (now - startNs)).toLong(),
                        maxBps = maxBps,
                        etaSeconds = if (probe.totalBytes > 0 && ema >= 1) (remaining / ema).toLong() else -1,
                        connections = jobs.size,
                        segments = segments.map { it.state() },
                    ),
                )
            }
            if (now - lastPersistNs >= config.persistIntervalMs * 1_000_000L) {
                lastPersistNs = now
                // اللقطة قبل force: كل بايت محسوب فيها مكتوب قبل التثبيت (العمال قد يتقدمون بعدها، فلا بأس)
                val snapshot = segments.map { it.state() }
                channel.force(false)
                listener.persist(ResumeState(probe, snapshot))
                reservation?.let { SpaceLedger.update(it, probe.totalBytes - snapshot.sumOf { s -> s.downloaded }) }
            }
        }
    }

    /** عدد الاتصالات التي عليها التخلي عن أجزائها بعد خفض الحد (يحدده المشرف). */
    private val toYield = java.util.concurrent.atomic.AtomicInteger()
    private fun shouldYield(): Boolean = toYield.getAndUpdate { if (it > 0) it - 1 else it } > 0

    private suspend fun runSegment(seg: Segment, probe: ProbeResult, channel: FileChannel) {
        var failures = 0
        while (!seg.done) {
            val before = seg.downloaded
            try {
                fetch(seg, probe, channel)
                if (seg.end < 0) { seg.markStreamEnded(); break } // حجم مجهول وانتهى البث طبيعياً
                if (!seg.done) throw IOException("stream ended early")
            } catch (e: Yield) {
                return // الجزء يبقى غير مكتمل ويلتقطه اتصال آخر
            } catch (e: DownloadException) {
                if (!e.error.retryable) throw e
                failures = if (seg.downloaded > before) 1 else failures + 1
                val err = e.error
                val busy = err is DownloadError.Http && (err.code == 429 || err.code == 503)
                if (busy) pushbacks.incrementAndGet()
                backoff(seg, failures, e.error, (e.cause as? RetryAfter)?.ms)
                // الخادم مزدحم والاتصالات أكثر من المستهدف: نترك الجزء لاتصال آخر لاحقاً
                if (busy && runningWorkers.get() > sharedTarget) return
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                failures = if (seg.downloaded > before) 1 else failures + 1
                backoff(seg, failures, DownloadError.Network(NetFail.of(e)), null)
            }
        }
    }

    private suspend fun backoff(seg: Segment, attempt: Int, error: DownloadError, retryAfterMs: Long?) {
        if (attempt > config.maxRetries) throw DownloadException(error)
        val exp = config.baseRetryDelayMs * (1L shl (attempt - 1).coerceAtMost(16))
        val wait = retryAfterMs?.coerceAtMost(config.maxRetryDelayMs * 4)
            ?: (exp.coerceAtMost(config.maxRetryDelayMs) * (0.75 + Random.nextDouble() * 0.5)).toLong()
        listener.onRetry(seg.index, attempt, error, wait)
        delay(wait)
    }

    private suspend fun fetch(seg: Segment, probe: ProbeResult, channel: FileChannel) {
        val builder = spec.toRequest(probe.finalUrl).get()
        if (probe.resumable) {
            builder.header("Range", "bytes=${seg.position}-${seg.end}")
            probe.ifRangeValidator?.let { builder.header("If-Range", it) }
        }
        client.withResponse(builder.build()) { r ->
            var limit = Long.MAX_VALUE // أقصى بايتات يُسمح بقراءتها من هذا الجسم
            when (r.code) {
                206 -> {
                    // RFC 9110 §14.4: البداية = الموضع المطلوب، والنهاية ضمن المطلوب، والإجمالي مطابق،
                    // وطول الجسم (إن أُعلن) يساوي طول المدى، وبلا ترميز ضغط.
                    val cr = parseContentRange(r.header("Content-Range"))
                    if (cr == null || !cr.valid || cr.first != seg.position || cr.last > seg.end) throw DownloadException(DownloadError.RangeNotSupported)
                    if (cr.total != null && cr.total != probe.totalBytes) throw DownloadException(DownloadError.ChangedOnServer)
                    val len = r.body?.contentLength() ?: -1
                    if (len >= 0 && len != cr.last - cr.first + 1) throw DownloadException(DownloadError.RangeNotSupported)
                    if (r.header("Content-Encoding")?.let { !it.equals("identity", true) } == true) throw DownloadException(DownloadError.RangeNotSupported)
                    // كل جزء يُقارن معرّفه بمعرّف الفحص: ملف آخر بالحجم نفسه لا يُخلط
                    val tag = r.header("ETag")?.takeUnless { it.startsWith("W/") }
                    if (probe.strongEtag != null && tag != null && tag != probe.strongEtag) throw DownloadException(DownloadError.ChangedOnServer)
                    limit = cr.last - cr.first + 1
                }
                200 -> {
                    // طلبنا جزءاً مع If-Range فأعاد الخادم الملف كاملاً: المحتوى تغيّر.
                    if (probe.resumable) throw DownloadException(DownloadError.ChangedOnServer)
                    synchronized(seg) { seg.downloaded = 0 } // بلا استئناف: نبدأ البث من أوله
                }
                401, 403, 410 -> throw DownloadException(DownloadError.LinkExpired(r.code))
                404 -> throw DownloadException(DownloadError.NotFound)
                else -> {
                    val retryAfter = parseRetryAfter(r.header("Retry-After"))
                    throw DownloadException(DownloadError.Http(r.code), retryAfter?.let(::RetryAfter))
                }
            }
            val source = r.body?.source() ?: throw IOException("empty body")
            val buf = ByteArray(config.bufferBytes)
            var read = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                if (seg.done) return
                if (shouldYield()) throw Yield()
                if (read >= limit) return
                val n = source.read(buf, 0, minOf(buf.size.toLong(), limit - read).toInt())
                if (n < 0) return
                read += n
                taskLimiter.acquire(n)
                globalLimiter.acquire(n)
                val written = synchronized(seg) {
                    val allowed = if (seg.end < 0) n.toLong() else minOf(n.toLong(), seg.end - seg.position + 1)
                    if (allowed > 0) {
                        writeAt(channel, buf, allowed.toInt(), seg.position)
                        seg.downloaded += allowed
                    }
                    allowed
                }
                if (written <= 0) return
                sessionBytes.addAndGet(written)
                listener.onBytes(written)
            }
        }
    }

    private fun writeAt(channel: FileChannel, buf: ByteArray, length: Int, position: Long) {
        val bb = ByteBuffer.wrap(buf, 0, length)
        try {
            while (bb.hasRemaining()) channel.write(bb, position + bb.position())
        } catch (e: IOException) {
            // امتلاء فعلي أثناء الكتابة (حتى مع الحجم المجهول أو تنزيلات أخرى)
            val msg = e.message.orEmpty()
            if (msg.contains("ENOSPC") || msg.contains("No space left", true)) {
                val dir = File(spacePath)
                throw DownloadException(DownloadError.InsufficientSpace(length.toLong(), usableSpace(dir).coerceAtLeast(0), spacePath), e)
            }
            throw DownloadException(DownloadError.Storage(msg.ifBlank { "write" }), e)
        }
    }

    private class RetryAfter(val ms: Long) : Exception()

    companion object {
        /** Retry-After بصيغتيه: ثوانٍ أو تاريخ HTTP (RFC 9110 §10.2.3). */
        fun parseRetryAfter(v: String?): Long? {
            val t = v?.trim() ?: return null
            t.toLongOrNull()?.let { return (it * 1000).coerceAtLeast(0) }
            return runCatching {
                val d = java.time.ZonedDateTime.parse(t, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                (d.toInstant().toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(0)
            }.getOrNull()
        }
        private const val TICK_MS = 200L
    }
    private class Yield : Exception()

    /**
     * ضبط تلقائي لعدد الاتصالات: عند استقرار السرعة يضيف اتصالين، وبعد ٥ ثوانٍ يقارن
     * نسبة زيادة السرعة بنسبة زيادة الاتصالات؛ إن كانت أقل من ٨٠٪ منها يعود للعدد السابق ويتوقف.
     */
    internal class AutoTune(private val config: DownloadConfig, private val enabled: Boolean) {
        var cap = config.maxConnections
        fun reopen() { if (enabled) { finished = false; checkpoint = null; history.clear() } }
        private val history = ArrayDeque<Long>()
        private var checkpoint: Triple<Int, Double, Long>? = null
        var finished = !enabled
            private set

        fun stop() { finished = true }

        fun next(target: Int, speed: Long, nowNs: Long, running: Int): Int {
            if (finished) return target
            history.addLast(speed)
            if (history.size > 5) history.removeFirst()
            if (history.size < 5) return target
            val avg = history.average()
            if (avg <= 0) return target
            if (history.maxOf { abs(it - avg) / avg } > 0.15) return target
            val cp = checkpoint
            if (cp == null) {
                if (target >= cap || running < target) { finished = target >= cap; return target }
                checkpoint = Triple(target, avg, nowNs)
                return (target + 2).coerceAtMost(cap)
            }
            if (nowNs - cp.third < 5_000_000_000L) return target
            val workerRatio = (target - cp.first).toDouble() / cp.first
            val speedRatio = (avg - cp.second) / cp.second
            checkpoint = null
            history.clear()
            return if (workerRatio <= 0 || speedRatio < 0.8 * workerRatio) {
                finished = true
                cp.first
            } else target
        }
    }

}
