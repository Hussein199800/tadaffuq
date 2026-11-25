package com.tadaffuq.app.download

import android.content.Context
import android.os.Build
import com.tadaffuq.app.BuildConfig
import com.tadaffuq.data.AppDatabase
import com.tadaffuq.data.SegmentEntity
import com.tadaffuq.data.Settings
import com.tadaffuq.data.SettingsRepository
import com.tadaffuq.data.TaskEntity
import com.tadaffuq.data.TaskStatus
import com.tadaffuq.engine.DownloadConfig
import com.tadaffuq.engine.DownloadError
import com.tadaffuq.engine.DownloadException
import com.tadaffuq.engine.DownloadListener
import com.tadaffuq.engine.FileNames
import com.tadaffuq.engine.Hashes
import com.tadaffuq.engine.HttpDownloader
import com.tadaffuq.engine.Prober
import com.tadaffuq.engine.ProbeResult
import com.tadaffuq.engine.RateLimiter
import com.tadaffuq.engine.RequestSpec
import com.tadaffuq.engine.ResumeState
import com.tadaffuq.engine.SegmentState
import com.tadaffuq.engine.Snapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** طلب إضافة مهمة من الواجهة. */
data class NewTask(
    val url: String,
    val fileName: String,
    val dirPath: String,
    val probe: ProbeResult? = null,
    val connections: Int = 0,
    val expectedHash: String? = null,
    val userAgent: String? = null,
    val referer: String? = null,
    val cookies: String? = null,
    val priority: Int = 0,
    val packageId: Long? = null,
    val wifiOnly: Boolean = false,
    val speedLimitBps: Long = 0,
    /** يبدأ متوقفاً (للحفظ في الطابور دون بدء). */
    val startPaused: Boolean = false,
    val mirrors: String? = null,
    val postExtract: Boolean = false,
    val scheduledAt: Long? = null,
    /** مجلد SAF لهذه المهمة؛ null = الإعداد العام وقت الإضافة. */
    val treeUri: String? = null,
)

/**
 * منسّق التنزيلات: Room مصدر الحقيقة، وهذا الكائن يشغّل ما في الطابور حسب الأولوية
 * وحدّ التنزيلات المتوازية وحالة الجهاز، ويحفظ التقدم ليُستأنف بعد أي انقطاع.
 */
@Singleton
class DownloadManager @Inject constructor(
    @ApplicationContext private val ctx: Context,
    db: AppDatabase,
    private val settingsRepo: SettingsRepository,
    private val clients: ClientProvider,
    val credentials: AppCredentials,
    val conditions: Conditions,
    private val notifier: Notifier,
) {
    private val dao = db.tasks()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val usage = UsageMeter(db.usage(), conditions)

    private val _live = MutableStateFlow<Map<Long, Snapshot>>(emptyMap())
    /** القراءات اللحظية الحقيقية لكل مهمة تعمل. */
    val live: StateFlow<Map<Long, Snapshot>> = _live

    private val _speedHistory = MutableStateFlow(List(HISTORY) { 0L })
    /** السرعة الإجمالية في آخر ٢ دقيقة، عيّنة كل ثانية. */
    val speedHistory: StateFlow<List<Long>> = _speedHistory

    private val _hold = MutableStateFlow<Hold?>(null)
    val hold: StateFlow<Hold?> = _hold

    private val _running = MutableStateFlow<Set<Long>>(emptySet())
    val runningIds: StateFlow<Set<Long>> = _running

    val globalLimiter = RateLimiter()
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val stopAs = ConcurrentHashMap<Long, TaskStatus>()
    private val limiters = ConcurrentHashMap<Long, RateLimiter>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val scheduleLock = Mutex()
    /** يحجز الأسماء النهائية ذرياً بين الإضافة والإنهاء (لا مهمتان على اسم واحد). */
    private val nameLock = Mutex()
    @Volatile private var settings = Settings()
    @Volatile private var started = false
    private val ready = kotlinx.coroutines.CompletableDeferred<Unit>()
    @Volatile private var pendingCount = 0
    private var ticks = 0

    fun start() {
        synchronized(this) { if (started) return; started = true }
        conditions.start()
        notifier.ensureChannels()
        scope.launch {
            // ما كان يعمل عند توقف العملية (إغلاق أو إعادة تشغيل) يعود للطابور ويُستأنف من آخر حفظ.
            // بدون الاستئناف التلقائي تبقى متوقفة حتى يستأنفها المستخدم (التقدم محفوظ في الحالتين)
            // الترتيب: الإعدادات المحفوظة ثم الاستعادة ثم الجدولة، فلا تبدأ مهمة بإعدادات افتراضية
            settings = settingsRepo.current()
            recoverInterrupted(settings.autoResume)
            ready.complete(Unit)
            settingsRepo.settings.collect {
                val notifChanged = it.notifySound != settings.notifySound || it.notifyVibrate != settings.notifyVibrate
                settings = it
                if (notifChanged) notifier.ensureChannels(it.notifySound, it.notifyVibrate)
                applyLimiter()
                wake()
            }
        }
        scope.launch { conditions.state.collect { applyLimiter(); wake() } }
        scope.launch { ready.await(); for (w in wake) scheduleLock.withLock { schedule() } }
        scope.launch {
            while (true) {
                delay(1_000)
                val total = _live.value.values.sumOf { it.speedBps } + externalSpeed()
                _speedHistory.update { (it.drop(1) + total) }
                // المساحة والنافذة الزمنية والمواعيد تتغير مع الوقت: مراجعة دورية رخيصة
                if (++ticks % 30 == 0) {
                    conditions.refreshStorage(StorageDirs.root()); wake()
                    if (jobs.isNotEmpty() || total > 0) runCatching { com.tadaffuq.app.widget.SpeedWidget.refresh(ctx) }
                }
            }
        }
        usage.onCellularBytes = {
            // بلوغ حد بيانات الجوال أثناء التنزيل يوقظ الطابور فوراً (لا ينتظر المراجعة الدورية)
            val s = effective
            if ((s.dailyLimitBytes > 0 && usage.cellularToday >= s.dailyLimitBytes) || (s.monthlyLimitBytes > 0 && usage.cellularMonth >= s.monthlyLimitBytes)) wake()
        }
        usage.start(scope)
    }

    /**
     * ما انقطع بموت العملية: RUNNING يعود للطابور (أو يُوقف)، وVERIFYING (الإنهاء) يُستعاد بحسب ما حدث فعلاً:
     * إن نُشر الملف النهائي بحجمه الصحيح ولم يبق جزئي → مكتمل دون إعادة تنزيل؛ وإلا يعود للطابور
     * والمحرك يعيد استعمال الجزئي المكتمل. الكوكيز القديمة غير المشفرة تُشفَّر.
     */
    private suspend fun recoverInterrupted(autoResume: Boolean) {
        val back = if (autoResume) TaskStatus.QUEUED else TaskStatus.PAUSED
        // المهام السابقة لتثبيت الوجهة لكل مهمة تأخذ مجلد SAF الذي كان مختاراً وقت التحديث (مرة واحدة)
        if (!settings.perTaskTreeMigrated) {
            settings.moveToTree.ifBlank { null }?.let { dao.adoptTree(it) }
            settingsRepo.update { it.copy(perTaskTreeMigrated = true) }
        }
        // نقل إلى SAF/MediaStore انقطع بموت العملية
        for (t in dao.withPublishJournal()) {
            val again = publisher.recover(t, targetFor(t))
            com.tadaffuq.app.core.TechLog.log("publish recovered #${t.id} again=$again")
            if (again) t.filePath?.let(::File)?.takeIf { it.isFile }?.let { f -> scope.launch(Dispatchers.IO) { dao.get(t.id)?.let { publishOut(it, f) } } }
        }
        for (t in dao.withStatus(listOf(TaskStatus.RUNNING, TaskStatus.VERIFYING))) {
            val dest = t.filePath?.let(::File)
            val published = t.status == TaskStatus.VERIFYING && dest != null && dest.isFile &&
                !StorageDirs.partFile(t.dirPath, t.id).exists() && (t.totalBytes < 0 || dest.length() == t.totalBytes)
            if (published) {
                dao.clearSegments(t.id)
                // البصمة حُسبت قبل النشر وحُفظت مع المسار؛ إن غابت (نسخة سابقة) تُحسب الآن من الملف المنشور
                var hashOk = t.hashOk; var actual = t.actualHash
                if (!t.expectedHash.isNullOrBlank() && hashOk == null) Hashes.verify(dest!!, t.expectedHash!!)?.let { hashOk = it.first; actual = it.second }
                if (hashOk == false) {
                    dao.update(t.copy(status = TaskStatus.FAILED, errorCode = "CHECKSUM", hashOk = false, actualHash = actual, filePath = dest!!.path))
                    continue
                }
                dao.update(t.copy(status = TaskStatus.COMPLETED, fileName = dest!!.name, totalBytes = dest.length(), downloadedBytes = dest.length(),
                    hashOk = hashOk, actualHash = actual, completedAt = t.completedAt ?: System.currentTimeMillis()))
                com.tadaffuq.app.core.TechLog.log("recovered finalized #${t.id}")
            } else dao.update(t.copy(status = back, filePath = null))
        }
        dao.withCookies().filter { !com.tadaffuq.app.core.FieldCrypt.isSealed(it.cookies) }
            .forEach { dao.update(it.copy(cookies = com.tadaffuq.app.core.FieldCrypt.seal(it.cookies))) }
    }

    fun wake() { wake.trySend(Unit) }

    /** للاختبارات: يحاكي موت العملية (يلغي كل شيء دون تحديث الحالة). */
    @androidx.annotation.VisibleForTesting
    fun kill() { killed = true; scope.coroutineContext[Job]?.cancel() }
    @Volatile private var killed = false

    private fun onCellular(d: DeviceState = conditions.state.value) = d.net == com.tadaffuq.data.NetKind.CELLULAR || (d.net == com.tadaffuq.data.NetKind.OTHER && d.metered)

    /** الإعدادات الفعلية الآن (ملف بيانات الهاتف المستقل إن فُعّل). */
    private val effective: Settings get() = settings.forNetwork(onCellular())

    private fun applyLimiter() { globalLimiter.bytesPerSecond = effective.globalLimitBps }

    /** حد الاتصالات الحي: يتغير أثناء التنزيل مع الإعدادات والشبكة والحرارة. */
    private fun liveCap(): Int {
        val s = effective
        return Policy.connectionCap(conditions.state.value, DownloadConfig.forMode(s.mode, s.maxConnections).maxConnections)
    }

    private suspend fun schedule() {
        val s = effective
        val d = conditions.state.value
        val hold = Policy.hold(s, d, usage.cellularToday, usage.cellularMonth)
        _hold.value = hold
        val now = System.currentTimeMillis()
        val all = dao.withStatus(listOf(TaskStatus.QUEUED, TaskStatus.WAITING_NETWORK))
        val (future, waiting) = all.partition { (it.scheduledAt ?: 0) > now }
        future.minOfOrNull { it.scheduledAt!! }?.let { ScheduleWorker.at(ctx, it) }
        pendingCount = waiting.count { Policy.hold(s, d, usage.cellularToday, usage.cellularMonth, it.wifiOnly) == null }
        if (hold != null) {
            val auto = settings.autoResume
            jobs.keys.toList().forEach { stop(it, if (auto) TaskStatus.WAITING_NETWORK else TaskStatus.PAUSED) }
            if (auto && (waiting.isNotEmpty() || jobs.isNotEmpty())) scheduleResumeWhenConnected()
            updateService()
            return
        }
        // شروط كل مهمة (Wi-Fi فقط) تُطبَّق على العاملة أيضاً عند تغيّر الشبكة، لا عند البدء فقط
        for (id in jobs.keys.toList()) {
            val t = dao.get(id) ?: continue
            if (t.wifiOnly && Policy.hold(s, d, usage.cellularToday, usage.cellularMonth, true) != null)
                stop(id, if (settings.autoResume) TaskStatus.WAITING_NETWORK else TaskStatus.PAUSED)
        }
        // الحد الأقصى للمتوازي: الزائد عن الحد (بعد تقليل الإعداد) ينتظر دوره
        val alive = jobs.keys.filter { !stopAs.containsKey(it) }
        val over = alive.size - s.maxParallel
        if (over > 0) alive.take(over).forEach { stop(it, TaskStatus.QUEUED) }
        val slots = s.maxParallel - jobs.size
        if (slots > 0) {
            waiting.asSequence()
                .filter { !jobs.containsKey(it.id) && Policy.hold(s, d, usage.cellularToday, usage.cellularMonth, it.wifiOnly) == null }
                .take(slots)
                .forEach { launchTask(it) }
        }
        updateService()
    }

    private suspend fun launchTask(t: TaskEntity) {
        dao.setStatus(t.id, TaskStatus.RUNNING)
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) { execute(t) }
        jobs[t.id] = job
        _running.value = jobs.keys.toSet()
        scope.launch { runCatching { com.tadaffuq.app.widget.SpeedWidget.refresh(ctx) } }
        job.invokeOnCompletion {
            scope.launch { runCatching { com.tadaffuq.app.widget.SpeedWidget.refresh(ctx) } }
            jobs.remove(t.id)
            _running.value = jobs.keys.toSet()
            _live.update { it - t.id }
            wake()
        }
        job.start()
    }

    private fun updateService() {
        if (jobs.isNotEmpty()) DownloadService.ensureRunning(ctx, this)
    }

    /** هل بقي عمل؟ تستعمله الخدمة وعامل الاستئناف ليتوقفا عند الفراغ. */
    fun hasWork(): Boolean = jobs.isNotEmpty() || (pendingCount > 0 && _hold.value == null) || externalWork()

    /** يضيف التورنت سرعته وعمله دون اعتماد دائري بين المديرين. */
    @Volatile var externalSpeed: () -> Long = { 0L }
    @Volatile var externalWork: () -> Boolean = { false }

    private fun stop(id: Long, status: TaskStatus) {
        val job = jobs[id] ?: return
        stopAs[id] = status
        job.cancel()
    }

    private fun scheduleResumeWhenConnected() {
        if (!DownloadService.workerInForeground) ResumeWorker.enqueue(ctx)
    }

    private suspend fun execute(t: TaskEntity) {
        val segs = dao.segments(t.id)
        val saved = if (segs.isNotEmpty() && t.totalBytes > 0) ResumeState(
            ProbeResult(t.finalUrl ?: t.url, t.totalBytes, t.acceptRanges, t.etag, t.lastModified, t.fileName, t.mimeType),
            segs.map { SegmentState(it.idx, it.start, it.endInclusive, it.downloaded) },
        ) else null
        val s = settings
        val eff = effective
        val base = DownloadConfig.forMode(eff.mode, eff.maxConnections)
        val cap = liveCap()
        val cfg = base.copy(
            connections = t.connections.coerceAtMost(cap), maxConnections = cap,
            autoTune = base.autoTune && eff.smartTuning, dynamicSplit = eff.dynamicSplit,
            // وضع الإنترنت الضعيف يرفع المحاولات؛ وإلا فإعداد المستخدم
            maxRetries = maxOf(eff.maxRetries, if (eff.mode == com.tadaffuq.engine.SpeedMode.WEAK_NETWORK) base.maxRetries else 0),
            baseRetryDelayMs = maxOf(eff.retryDelaySec * 1000L, if (eff.mode == com.tadaffuq.engine.SpeedMode.WEAK_NETWORK) base.baseRetryDelayMs else 0),
        )
        val limiter = limiters.getOrPut(t.id) { RateLimiter(t.speedLimitBps) }
        // يحدّثها عمال متوازون: عدادات ذرية
        val counters = java.util.concurrent.atomic.AtomicIntegerArray(intArrayOf(t.retries, t.networkRetries, t.serverRetries))
        var last: Snapshot? = null
        com.tadaffuq.app.core.TechLog.log("start #${t.id} ${t.url} conn=${cfg.connections}/${cfg.maxConnections} resume=${saved != null}")
        val spec = RequestSpec(
            headers = hubAuth(t.url),
            url = t.url,
            userAgent = t.userAgent ?: s.userAgent.ifBlank { null } ?: DEFAULT_UA,
            referer = t.referer,
            cookies = com.tadaffuq.app.core.FieldCrypt.open(t.cookies),
            // الكوكيز والتفويض لمضيف الرابط الأصلي فقط (لا لمرآة أو تحويل إلى مضيف آخر)
            secretsHost = RequestSpec.authorityOf(t.url),
        )
        val part = StorageDirs.partFile(t.dirPath, t.id)
        migrateLegacyPart(t, part)
        val listener = object : DownloadListener {
            override fun onProgress(snapshot: Snapshot) {
                last = snapshot
                _live.update { it + (t.id to snapshot) }
            }

            override suspend fun persist(state: ResumeState) {
                val p = state.probe
                dao.saveProgress(
                    t.id,
                    state.segments.map { SegmentEntity(t.id, it.index, it.start, it.end, it.downloaded) },
                    state.segments.sumOf { it.downloaded }, p.totalBytes, p.etag, p.lastModified, p.acceptRanges, p.finalUrl,
                )
                last?.let { dao.stats(t.id, it.averageBps, it.maxBps) }
            }

            override fun onBytes(count: Long) = usage.add(count)

            override fun onRetry(segment: Int, attempt: Int, error: DownloadError, delayMs: Long) {
                com.tadaffuq.app.core.TechLog.log("retry #${t.id} seg=$segment attempt=$attempt $error wait=${delayMs}ms")
                counters.incrementAndGet(0)
                when (error) {
                    is DownloadError.Network -> counters.incrementAndGet(1)
                    is DownloadError.Http -> counters.incrementAndGet(2)
                    else -> Unit
                }
            }
        }
        try {
            val probe = runWithMirrors(t, spec, part, cfg, saved, limiter, listener)
            finalize(t, part, probe, counters, last)
        } catch (e: CancellationException) {
            if (killed) throw e // موت العملية: لا يُكتب شيء، كما في الواقع
            val status = stopAs.remove(t.id) ?: TaskStatus.PAUSED
            withContext(NonCancellable) {
                dao.setStatus(t.id, status)
                dao.setRetries(t.id, counters[0], counters[1], counters[2])
            }
            throw e
        } catch (e: FinalizeFailure) {
            withContext(NonCancellable) {
                dao.get(t.id)?.let {
                    val failed = e.apply(it).copy(status = TaskStatus.FAILED, filePath = null,
                        retries = counters[0], networkRetries = counters[1], serverRetries = counters[2])
                    dao.update(failed)
                    if (settings.notifyOnFail) notifier.failed(failed)
                }
            }
        } catch (e: DownloadException) {
            com.tadaffuq.app.core.TechLog.log("fail #${t.id} ${e.error}")
            withContext(NonCancellable) {
                val offline = e.error is DownloadError.Network && !conditions.state.value.connected && settings.autoResume
                dao.get(t.id)?.let {
                    val failed = it.copy(
                        status = if (offline) TaskStatus.WAITING_NETWORK else TaskStatus.FAILED,
                        errorCode = ErrorCodes.of(e.error),
                        errorDetail = ErrorCodes.detail(e.error),
                        retries = counters[0], networkRetries = counters[1], serverRetries = counters[2],
                    )
                    dao.update(failed)
                    if (!offline && settings.notifyOnFail) notifier.failed(failed)
                }
            }
        } catch (e: Exception) {
            withContext(NonCancellable) {
                dao.setStatus(t.id, TaskStatus.FAILED, "INTERNAL", e.javaClass.simpleName)
            }
        } finally {
            withContext(NonCancellable) { if (!killed) runCatching { usage.flush() } }
        }
    }

    /** فشل في مرحلة الإنهاء: يحمل التعديل المطلوب على المهمة (الرمز والبصمة). */
    private class FinalizeFailure(val apply: (TaskEntity) -> TaskEntity) : Exception()

    /**
     * الإنهاء بخطوات قابلة للاستعادة:
     * 1) VERIFYING والتحقق من البصمة على الملف الجزئي قبل نشره (فشلها أو مرجع غير صالح = FAILED، والملف لا يُنشر).
     * 2) حجز الاسم النهائي وتسجيل مساره في القاعدة قبل إعادة التسمية.
     * 3) إعادة التسمية، ثم COMPLETED ومسح الأجزاء.
     * موت العملية بين أي خطوتين يُستعاد في [recoverInterrupted].
     */
    private suspend fun finalize(t: TaskEntity, part: File, probe: ProbeResult, counters: java.util.concurrent.atomic.AtomicIntegerArray, last: Snapshot?) {
        dao.setStatus(t.id, TaskStatus.VERIFYING)
        var hashOk: Boolean? = null
        var actual: String? = null
        val ref = t.expectedHash?.trim()?.ifBlank { null }
        if (ref != null) {
            val r = Hashes.verify(part, ref) ?: throw FinalizeFailure { it.copy(errorCode = "HASH_INVALID", errorDetail = null, hashOk = null) }
            hashOk = r.first; actual = r.second
            if (!r.first) throw FinalizeFailure { it.copy(errorCode = "CHECKSUM", errorDetail = null, hashOk = false, actualHash = r.second) }
        }
        val dir = File(t.dirPath)
        val dest = nameLock.withLock {
            val cur = dao.get(t.id) ?: return
            val others = dao.unfinishedNames(t.dirPath, exclude = t.id).toSet()
            val name = FileNames.uniqueIn(dir, cur.fileName) { it.exists() || it.name in others }
            val dest = File(dir, name)
            // سجل النشر: المسار ونتيجة البصمة قبل rename، فالاستعادة لا تحتاج إعادة التحقق ولا تفقد نتيجته
            dao.update(cur.copy(fileName = name, filePath = dest.path, status = TaskStatus.VERIFYING, hashOk = hashOk, actualHash = actual))
            dest
        }
        if (!part.renameTo(dest)) throw FinalizeFailure { it.copy(errorCode = "STORAGE", errorDetail = "rename") }
        val current = dao.get(t.id) ?: return
        val done = current.copy(
            status = TaskStatus.COMPLETED,
            fileName = dest.name,
            filePath = dest.path,
            totalBytes = dest.length(),
            downloadedBytes = dest.length(),
            finalUrl = probe.finalUrl,
            mimeType = probe.mimeType ?: current.mimeType,
            completedAt = System.currentTimeMillis(),
            actualHash = actual,
            hashOk = hashOk,
            errorCode = null,
            errorDetail = null,
            retries = counters[0], networkRetries = counters[1], serverRetries = counters[2],
            averageBps = last?.averageBps ?: current.averageBps,
            maxBps = maxOf(last?.maxBps ?: 0, current.maxBps),
        )
        dao.update(done)
        dao.clearSegments(t.id)
        limiters.remove(t.id)
        StorageDirs.scan(ctx, dest)
        val final = postActions(done, dest)
        if (settings.notifyOnComplete) notifier.completed(final)
    }


    /** ترحيل ملف جزئي من الإصدارات السابقة إن كان لهذه المهمة وحدها (لا مهمة أخرى غير مكتملة بالاسم نفسه). */
    private suspend fun migrateLegacyPart(t: TaskEntity, part: File) {
        if (part.exists()) return
        StorageDirs.rc3PartFile(t.dirPath, t.fileName, t.id).takeIf { it.isFile }?.let { if (it.renameTo(part)) return }
        val legacy = StorageDirs.legacyPartFile(t.dirPath, t.fileName)
        if (!legacy.isFile) return
        val ambiguous = nameLock.withLock { t.fileName in dao.unfinishedNames(t.dirPath, exclude = t.id) }
        if (ambiguous) { com.tadaffuq.app.core.TechLog.log("legacy part ambiguous #${t.id}: not reused"); return }
        if (legacy.renameTo(part)) com.tadaffuq.app.core.TechLog.log("legacy part migrated #${t.id}")
    }

    /**
     * إجراءات بعد الاكتمال والتحقق: فك الأرشيف (إن طُلب ولم تفشل البصمة) ثم النقل إلى مجلد SAF.
     * فشلها لا يُفشل التنزيل؛ يُسجَّل في errorDetail فقط.
     */
    private suspend fun postActions(t: TaskEntity, file: File): TaskEntity {
        if ((t.postExtract || settings.autoExtract) && t.hashOk != false && com.tadaffuq.engine.Archives.isArchive(file.name)) {
            val dest = File(file.parentFile, file.name.substringBeforeLast('.').removeSuffix(".tar"))
            runCatching { com.tadaffuq.engine.Archives.extract(file, dest) }
                .onFailure { e -> dao.setDetail(t.id, "EXTRACT:" + ((e as? com.tadaffuq.engine.ArchiveException)?.reason ?: e.javaClass.simpleName)) }
                .onSuccess { StorageDirs.scan(ctx, dest) }
        }
        return publishOut(dao.get(t.id) ?: t, file)
    }

    private val publisher by lazy { Publisher(dao) }

    /** وجهة النشر الخارجية للمهمة: مجلد SAF المثبّت عند الإضافة، أو MediaStore إن حُفظت في المجلد الاحتياطي. */
    private fun targetFor(t: TaskEntity): PublishTarget? = runCatching {
        when {
            !t.treeUri.isNullOrBlank() -> SafTarget(ctx, t.treeUri!!)
            Build.VERSION.SDK_INT >= 29 && StorageDirs.isInAppDir(File(t.dirPath)) ->
                MediaStoreTarget(ctx, StorageDirs.mediaStorePath(File(t.dirPath)))
            else -> null
        }
    }.getOrNull()

    /** نقل قابل للاستعادة (انظر [Publisher])؛ فشله لا يُفشل التنزيل: يبقى الملف محلياً مع رمز MOVE_FAILED. */
    private suspend fun publishOut(t: TaskEntity, file: File): TaskEntity {
        val needs = !t.treeUri.isNullOrBlank() || (Build.VERSION.SDK_INT >= 29 && StorageDirs.isInAppDir(File(t.dirPath)))
        if (!needs) return t
        val target = targetFor(t) ?: run { dao.setDetail(t.id, "MOVE_FAILED"); return dao.get(t.id) ?: t }
        return try { publisher.publish(t, file, target) } catch (e: Publisher.PublishFailed) {
            com.tadaffuq.app.core.TechLog.log("publish failed #${t.id} ${e.cause?.javaClass?.simpleName}")
            dao.setDetail(t.id, "MOVE_FAILED"); dao.get(t.id) ?: t
        }
    }

    /** نقل يدوي من شاشة الملفات بالسجل نفسه (لا حذف للأصل قبل تثبيت الوجهة). */
    suspend fun moveToTree(t: TaskEntity, tree: String): TaskEntity = withContext(Dispatchers.IO) {
        val f = t.filePath?.let(::File)?.takeIf { it.isFile } ?: error("missing")
        publisher.publish(t, f, SafTarget(ctx, tree))
    }

    /** تغيير وجهة مهمة لم تكتمل (صراحة من تفاصيلها). */
    suspend fun setTree(id: Long, tree: String?) = dao.setTree(id, tree?.ifBlank { null })


    // ===== واجهة الاستخدام =====

    private val client: OkHttpClient get() = clients.get(settings)

    /** رمز Hugging Face يُرسل لمضيفه فقط (OkHttp يحذفه عند التحويل لمضيف آخر)، ولا يُحفظ في قاعدة البيانات. */
    private fun hubAuth(url: String): Map<String, String> {
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return emptyMap()
        if (host != "huggingface.co") return emptyMap()
        return credentials.hfToken()?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap()
    }

    /**
     * مع المرايا: تُرتَّب حسب سرعة عيّنة حقيقية بعد التحقق من تطابق المحتوى، وعند فشل مصدر
     * (رابط منتهٍ أو غير موجود أو شبكة) يُجرَّب التالي. التبديل بين مصدرين لا يدمج أجزاءهما إلا
     * بدليل موثوق (ETag قوي أو Last-Modified)، وإلا يبدأ من جديد بأمان.
     */
    private suspend fun runWithMirrors(t: TaskEntity, spec: RequestSpec, part: File, cfg: DownloadConfig, saved: ResumeState?, limiter: RateLimiter, listener: DownloadListener): ProbeResult {
        val mirrors = t.mirrors?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() && it != t.url }.orEmpty()
        if (mirrors.isEmpty() || com.tadaffuq.engine.Protocols.isRemote(spec.url))
            return if (com.tadaffuq.engine.Protocols.isRemote(spec.url)) com.tadaffuq.engine.Protocols.downloader(client, spec, part, cfg, saved, limiter, globalLimiter, listener, credentials).run()
            else HttpDownloader(client, spec, part, cfg, saved, limiter, globalLimiter, listener, liveMaxConnections = ::liveCap).run()
        val ranked = com.tadaffuq.engine.MirrorSelector(client).rank(spec, mirrors).filter { it.matches }.map { it.url }
        val order = ranked.ifEmpty { listOf(t.url) }
        var last: DownloadException? = null
        var state = saved
        for (u in order) {
            try {
                return HttpDownloader(client, spec.copy(url = u), part, cfg, state, limiter, globalLimiter, listener, liveMaxConnections = ::liveCap).run()
            } catch (e: DownloadException) {
                last = e
                val switchable = e.error is DownloadError.LinkExpired || e.error == DownloadError.NotFound || e.error is DownloadError.Network || e.error is DownloadError.Http
                if (!switchable) throw e
                val segs = dao.segments(t.id)
                val cur = dao.get(t.id)
                state = if (segs.isNotEmpty() && cur != null && cur.totalBytes > 0) ResumeState(
                    ProbeResult(cur.finalUrl ?: u, cur.totalBytes, cur.acceptRanges, cur.etag, cur.lastModified, cur.fileName, cur.mimeType),
                    segs.map { SegmentState(it.idx, it.start, it.endInclusive, it.downloaded) },
                ) else null
            }
        }
        throw last ?: DownloadException(DownloadError.NotFound)
    }

    suspend fun testMirrors(t: TaskEntity): List<com.tadaffuq.engine.MirrorResult> {
        val mirrors = t.mirrors?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        return com.tadaffuq.engine.MirrorSelector(client).rank(RequestSpec(t.url, userAgent = t.userAgent ?: DEFAULT_UA, referer = t.referer, cookies = com.tadaffuq.app.core.FieldCrypt.open(t.cookies), secretsHost = RequestSpec.authorityOf(t.url)), mirrors)
    }

    suspend fun setMirrors(id: Long, mirrors: String?) { dao.get(id)?.let { dao.update(it.copy(mirrors = mirrors?.trim()?.ifBlank { null })) } }

    suspend fun probe(spec: RequestSpec): ProbeResult = com.tadaffuq.engine.Protocols.probe(client, spec.copy(userAgent = spec.userAgent ?: settings.userAgent.ifBlank { null } ?: DEFAULT_UA), credentials)

    /**
     * يحجز لكل مهمة اسماً نهائياً فريداً ذرياً (الملفات الموجودة + مهام غير مكتملة + الدفعة نفسها)؛
     * لا يُستبدل ملف موجود أبداً.
     */
    suspend fun enqueue(tasks: List<NewTask>): List<Long> = nameLock.withLock {
        val reserved = HashMap<String, MutableSet<String>>()
        val ids = dao.insertAll(tasks.map { n ->
            val taken = reserved.getOrPut(n.dirPath) { dao.unfinishedNames(n.dirPath).toMutableSet() }
            val name = FileNames.uniqueIn(File(n.dirPath), FileNames.sanitize(n.fileName)) { it.exists() || it.name in taken }
            taken += name
            TaskEntity(
                url = n.url, fileName = name, dirPath = n.dirPath,
                status = if (n.startPaused) TaskStatus.PAUSED else TaskStatus.QUEUED,
                totalBytes = n.probe?.totalBytes ?: -1, acceptRanges = n.probe?.acceptRanges ?: false,
                etag = n.probe?.etag, lastModified = n.probe?.lastModified, mimeType = n.probe?.mimeType,
                finalUrl = n.probe?.finalUrl, connections = n.connections.coerceIn(0, DownloadConfig.MAX_CONNECTIONS),
                expectedHash = n.expectedHash?.trim()?.ifBlank { null }, userAgent = n.userAgent, referer = n.referer,
                cookies = com.tadaffuq.app.core.FieldCrypt.seal(n.cookies), priority = n.priority, packageId = n.packageId, wifiOnly = n.wifiOnly,
                speedLimitBps = n.speedLimitBps, mirrors = n.mirrors?.trim()?.ifBlank { null },
                postExtract = n.postExtract, treeUri = n.treeUri ?: settings.moveToTree.ifBlank { null }, scheduledAt = n.scheduledAt?.takeIf { it > System.currentTimeMillis() },
            )
        })
        wake()
        ids
    }

    suspend fun pause(ids: Collection<Long>) {
        ids.forEach { id ->
            if (jobs.containsKey(id)) stop(id, TaskStatus.PAUSED)
            else dao.get(id)?.takeIf { it.status.isWaiting }?.let { dao.setStatus(id, TaskStatus.PAUSED) }
        }
    }

    suspend fun resume(ids: Collection<Long>) {
        ids.forEach { id ->
            val t = dao.get(id)?.takeIf { it.status == TaskStatus.PAUSED || it.status == TaskStatus.FAILED || it.status == TaskStatus.WAITING_NETWORK } ?: return@forEach
            when (t.errorCode) {
                // ملف لا يطابق البصمة: إعادة المحاولة = تنزيل جديد (لا يُعاد التحقق من البيانات نفسها)
                "CHECKSUM" -> { dao.clearSegments(id); StorageDirs.partFile(t.dirPath, t.id).delete()
                    dao.update(t.copy(status = TaskStatus.QUEUED, downloadedBytes = 0, errorCode = null, errorDetail = null, hashOk = null, actualHash = null)) }
                // مرجع غير صالح: المستخدم رأى الرسالة واختار الإكمال دون تحقق
                "HASH_INVALID" -> dao.update(t.copy(status = TaskStatus.QUEUED, expectedHash = null, errorCode = null, errorDetail = null))
                else -> dao.update(t.copy(status = TaskStatus.QUEUED, errorCode = null, errorDetail = null))
            }
        }
        wake()
    }

    /** إعادة من الصفر: تُحذف الأجزاء والملف الجزئي. */
    suspend fun restart(id: Long) {
        val t = dao.get(id) ?: return
        jobs[id]?.let { stop(id, TaskStatus.PAUSED); it.join() }
        dao.clearSegments(id)
        StorageDirs.partFile(t.dirPath, t.id).delete()
        dao.update(t.copy(status = TaskStatus.QUEUED, downloadedBytes = 0, errorCode = null, errorDetail = null, retries = 0, networkRetries = 0, serverRetries = 0))
        wake()
    }

    suspend fun pauseAll() = pause(jobs.keys + dao.withStatus(listOf(TaskStatus.QUEUED, TaskStatus.WAITING_NETWORK)).map { it.id })
    suspend fun resumeAll() = resume(dao.withStatus(listOf(TaskStatus.PAUSED, TaskStatus.WAITING_NETWORK)).map { it.id })

    /**
     * حذف مهام؛ withFiles يحذف الملف المكتمل أيضاً، والملف الجزئي يُحذف دائماً.
     * إن تعذّر حذف الملف يبقى سجله (لا ملف يتيم بلا سجل). يعيد عدد ما تعذّر.
     */
    suspend fun delete(ids: Collection<Long>, withFiles: Boolean): Int {
        val removed = ArrayList<Long>()
        ids.forEach { id ->
            jobs[id]?.let { stop(id, TaskStatus.PAUSED); it.join() }
            val t = dao.get(id) ?: return@forEach
            StorageDirs.partFile(t.dirPath, t.id).delete()
            limiters.remove(id)
            val f = t.filePath?.takeIf { withFiles && t.status == TaskStatus.COMPLETED }?.let(::File)
            if (f == null || !f.exists() || f.delete()) removed += id
        }
        dao.delete(removed)
        return ids.size - removed.size
    }

    /** نقل إلى السلة (قابل للاستعادة)؛ يعيد عدد ما تعذّر نقله، وسجله يبقى كما هو. */
    suspend fun trash(ids: Collection<Long>): Int = withContext(Dispatchers.IO) {
        val bin = StorageDirs.trash().apply { mkdirs() }
        var failed = 0
        ids.forEach { id ->
            jobs[id]?.let { stop(id, TaskStatus.PAUSED); it.join() }
            val t = dao.get(id) ?: return@forEach
            val f = t.filePath?.let(::File)
            if (f != null && f.exists()) {
                val dest = File(bin, "${t.id}_${f.name}")
                if (f.renameTo(dest)) dao.update(t.copy(filePath = dest.path, trashedAt = System.currentTimeMillis())) else failed++
            } else dao.update(t.copy(trashedAt = System.currentTimeMillis()))
        }
        failed
    }

    val notifyPerTask: Boolean get() = settings.notifyPerTask

    fun notificationSettingsIntent(): android.content.Intent = notifier.channelSettingsIntent()

    /** المهام العاملة الآن (لإشعارات كل ملف). */
    suspend fun runningTasks(): List<TaskEntity> = jobs.keys.toList().mapNotNull { dao.get(it) }

    suspend fun task(id: Long): TaskEntity? = dao.get(id)

    suspend fun setPriority(id: Long, priority: Int) { dao.setPriority(id, priority); wake() }

    suspend fun setSpeedLimit(id: Long, bps: Long) {
        dao.setSpeedLimit(id, bps)
        limiters.getOrPut(id) { RateLimiter() }.bytesPerSecond = bps
    }

    /** تحديث رابط منتهٍ: يبقى التقدم، والمحرك يتحقق عند الاستئناف أن الملف نفسه (الحجم وETag). */
    suspend fun updateUrl(id: Long, url: String) {
        jobs[id]?.let { stop(id, TaskStatus.PAUSED); it.join() }
        dao.get(id) ?: return
        dao.setUrl(id, url)
        wake()
    }

    suspend fun duplicates(url: String, dir: String, name: String): List<TaskEntity> =
        (dao.byUrl(url) + dao.byName(dir, name)).distinctBy { it.id }

    companion object {
        const val HISTORY = 120
        val DEFAULT_UA = "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}) Tadaffuq/${BuildConfig.VERSION_NAME}"
    }
}

/** رموز الأخطاء المحفوظة في قاعدة البيانات، وتحويلها للطبيب والواجهة. */
object ErrorCodes {
    fun of(e: DownloadError): String = when (e) {
        is DownloadError.Network -> "NET_${e.kind}"
        is DownloadError.Http -> "HTTP_${e.code}"
        is DownloadError.LinkExpired -> "EXPIRED_${e.code}"
        DownloadError.NotFound -> "NOT_FOUND"
        DownloadError.ChangedOnServer -> "CHANGED"
        DownloadError.RangeNotSupported -> "NO_RANGE"
        is DownloadError.InsufficientSpace -> "SPACE"
        is DownloadError.Storage -> "STORAGE"
        is DownloadError.ChecksumMismatch -> "CHECKSUM"
        DownloadError.AuthFailed -> "AUTH"
        is DownloadError.HostKeyUnknown -> "HOSTKEY_NEW"
        is DownloadError.HostKeyChanged -> "HOSTKEY_CHANGED"
    }

    /** تفاصيل تُحفظ مع الرمز (بصمة SSH مثلاً). */
    fun detail(e: DownloadError): String? = when (e) {
        is DownloadError.Storage -> e.detail
        is DownloadError.HostKeyUnknown -> e.fingerprint
        is DownloadError.HostKeyChanged -> e.fingerprint
        else -> null
    }

    fun toError(code: String?, detail: String? = null): DownloadError? = when {
        code == null -> null
        code == "NETWORK" -> DownloadError.Network(com.tadaffuq.engine.NetFail.RESET)
        code.startsWith("NET_") -> DownloadError.Network(runCatching { com.tadaffuq.engine.NetFail.valueOf(code.removePrefix("NET_")) }.getOrDefault(com.tadaffuq.engine.NetFail.RESET))
        code.startsWith("HTTP_") -> DownloadError.Http(code.removePrefix("HTTP_").toIntOrNull() ?: 0)
        code.startsWith("EXPIRED_") -> DownloadError.LinkExpired(code.removePrefix("EXPIRED_").toIntOrNull() ?: 403)
        code == "NOT_FOUND" -> DownloadError.NotFound
        code == "CHANGED" -> DownloadError.ChangedOnServer
        code == "NO_RANGE" -> DownloadError.RangeNotSupported
        code == "SPACE" -> DownloadError.InsufficientSpace(0, 0)
        code == "STORAGE" || code == "INTERNAL" -> DownloadError.Storage(detail ?: code)
        code == "CHECKSUM" -> DownloadError.ChecksumMismatch("", "")
        code == "AUTH" -> DownloadError.AuthFailed
        code == "HOSTKEY_NEW" -> DownloadError.HostKeyUnknown("", detail.orEmpty())
        code == "HOSTKEY_CHANGED" -> DownloadError.HostKeyChanged("", detail.orEmpty())
        else -> null
    }
}
