package com.tadaffuq.app.download

import android.content.Context
import com.tadaffuq.data.AppDatabase
import com.tadaffuq.data.Settings
import com.tadaffuq.data.SettingsRepository
import com.tadaffuq.data.TorrentEntity
import com.tadaffuq.data.TorrentRunState
import com.tadaffuq.torrent.PeerSnapshot
import com.tadaffuq.torrent.TorrentEngine
import com.tadaffuq.torrent.TorrentInfoSnapshot
import com.tadaffuq.torrent.TorrentMeta
import com.tadaffuq.torrent.TorrentSettings
import com.tadaffuq.torrent.TorrentState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * يدير جلسة التورنت: تُشغَّل فقط حين توجد تورنتات نشطة، وتتوقف مؤقتاً مع شروط الطابور نفسها
 * (Wi-Fi فقط، البطارية، الحرارة، حدود البيانات). الرفع ظاهر ومحدود بقواعد المستخدم.
 */
@Singleton
class TorrentManager @Inject constructor(
    @ApplicationContext private val ctx: Context,
    db: AppDatabase,
    private val settingsRepo: SettingsRepository,
    private val downloads: DownloadManager,
    private val notifier: Notifier,
) {
    private val dao = db.torrents()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val engine by lazy { loadNative(); TorrentEngine(File(ctx.filesDir, "torrent")) }
    private val lock = Mutex()
    private val _live = MutableStateFlow<Map<String, TorrentInfoSnapshot>>(emptyMap())
    val live: StateFlow<Map<String, TorrentInfoSnapshot>> = _live
    val torrents = dao.observe()
    @Volatile private var started = false
    @Volatile private var settings = Settings()
    @Volatile var nativeError: String? = null
        private set

    private fun loadNative() {
        // على أندرويد تُحمَّل المكتبة الأصلية من المكتبات المضمّنة في APK
        runCatching { System.loadLibrary("torrent4j") }.onFailure { nativeError = it.message }
    }

    fun start() {
        synchronized(this) { if (started) return; started = true }
        downloads.externalSpeed = { _live.value.values.sumOf { it.downBps.toLong() } }
        downloads.externalLoad = {
            val active = _live.value.values.filter { it.state == TorrentState.DOWNLOADING || it.state == TorrentState.METADATA || it.state == TorrentState.CHECKING }
            ExternalLoad(active.size, _live.value.values.sumOf { it.downBps.toLong() }, active.sumOf { it.wantedDone }, active.sumOf { it.wanted })
        }
        downloads.externalPauseAll = { pauseAll() }
        downloads.externalResumeAll = { resumeAll() }
        downloads.externalWork = { _live.value.values.any { it.state == TorrentState.DOWNLOADING || it.state == TorrentState.METADATA || it.state == TorrentState.SEEDING || it.state == TorrentState.CHECKING } }
        scope.launch { settingsRepo.settings.collect { settings = it; if (engine.running) engine.apply(torrentSettings(it)) } }
        scope.launch {
            val saved = dao.all().filter { it.state == TorrentRunState.ACTIVE || it.state == TorrentRunState.PAUSED || it.state == TorrentRunState.COMPLETED && it.seedAfter }
            if (saved.isNotEmpty()) lock.withLock { ensureEngine(); saved.forEach { restore(it) } }
            loop()
        }
    }

    private fun torrentSettings(s: Settings, down: Int = shareOfGlobal(s)) = TorrentSettings(
        dht = s.torrentDht, lsd = s.torrentDht, downloadLimit = down, uploadLimit = s.torrentUpLimit,
    )

    @Volatile private var appliedDown = -1
    @Volatile private var appliedAt = 0L

    /**
     * حد السرعة العام يشمل التورنت: يأخذ ما تتركه التنزيلات العادية من الحد (بحد أدنى 32 ك.ب/ث كي لا يتجمد)،
     * ولا يتجاوز حد التورنت الخاص إن وُضع. 0 = بلا حد. هامش موثّق: المجموع قد يزيد عن الحد بـ 32 ك.ب/ث على الأكثر.
     */
    internal fun shareOfGlobal(s: Settings, httpBps: Long = downloads.live.value.values.sumOf { it.speedBps }): Int {
        val global = downloads.globalLimiter.bytesPerSecond
        val own = s.torrentDownLimit
        if (global <= 0) return own
        val rest = (global - httpBps).coerceAtLeast(MIN_SHARE).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return if (own > 0) minOf(own, rest) else rest
    }

    private fun ensureEngine() { if (!engine.running) engine.start(torrentSettings(settings)) ; engine.onFinished = { h -> scope.launch { finished(h) } } }

    private fun restore(t: TorrentEntity) {
        runCatching {
            engine.add(TorrentEngine.parse(t.raw), File(t.saveDir), t.wantedSet(), paused = t.state == TorrentRunState.PAUSED)
            engine.setLimits(t.infoHash, t.downLimit, t.upLimit)
        }
    }

    private val lastTotals = HashMap<String, Long>()
    private companion object { const val MIN_SHARE = 32L * 1024 }

    /** من «إيقاف الكل» أو انتهاء مهلة الخدمة: يُحفظ PAUSED فلا يعود التورنت للعمل إلا باستئناف صريح. */
    suspend fun pauseAll() = withContext(Dispatchers.IO) {
        dao.all().filter { it.state == TorrentRunState.ACTIVE || (it.state == TorrentRunState.COMPLETED && it.seedAfter) }.forEach { t ->
            // المحرك لم يُشغَّل في هذه العملية (لا تورنت عامل): لا شيء يُوقف فيه، ويكفي حفظ الحالة
            if (started && engine.running) engine.pause(t.infoHash)
            // المكتمل الذي يشارك يُحفظ متوقفاً أيضاً (كالإيقاف الفردي)، وإلا عاد الرفع بعد إعادة تشغيل التطبيق
            dao.setState(t.infoHash, TorrentRunState.PAUSED)
        }
    }

    suspend fun resumeAll() = withContext(Dispatchers.IO) {
        dao.all().filter { it.state == TorrentRunState.PAUSED }.forEach { resume(it.infoHash) }
    }

    private suspend fun loop() {
        var tick = 0
        while (true) {
            delay(1_000)
            if (!engine.running) continue
            val all = dao.all()
            val snaps = all.mapNotNull { t -> engine.status(t.infoHash)?.let { t.infoHash to it } }.toMap()
            _live.value = snaps
            // نصيب التورنت من الحد العام يُعاد حسابه، ويُطبَّق فقط عند تغير ملموس (≥15٪) وكل 3 ث على الأكثر
            val want = shareOfGlobal(settings)
            val now = System.currentTimeMillis()
            if (want != appliedDown && now - appliedAt >= 3_000 &&
                (appliedDown <= 0 || want <= 0 || kotlin.math.abs(want - appliedDown) * 100 / appliedDown >= 15)) {
                engine.apply(torrentSettings(settings, want)); appliedDown = want; appliedAt = now
            }
            // شروط الطابور نفسها تنطبق على التورنت
            val hold = downloads.hold.value
            if (hold != null && !engine.sessionPaused) engine.pauseSession()
            if (hold == null && engine.sessionPaused) engine.resumeSession()
            // قواعد المشاركة: نسبة الرفع
            all.forEach { t ->
                val s = snaps[t.infoHash] ?: return@forEach
                if (t.state == TorrentRunState.COMPLETED && t.seedAfter && s.state == TorrentState.SEEDING && s.ratio >= t.ratioLimit) {
                    engine.pause(t.infoHash)
                    dao.upsert(t.copy(seedAfter = false))
                }
            }
            // البيانات الفعلية (تنزيل + رفع) تدخل حدود البيانات اليومية والشهرية كالتنزيل العادي
            snaps.forEach { (h, s) ->
                val now = s.downloaded + s.uploaded
                val prev = lastTotals.put(h, now)
                if (prev != null) downloads.recordExternalBytes(if (now >= prev) now - prev else now)
            }
            if (tick++ % 10 == 0) all.forEach { t -> snaps[t.infoHash]?.let { dao.counters(t.infoHash, it.uploaded, it.downloaded) } }
            if (snaps.values.any { it.state == TorrentState.DOWNLOADING || it.state == TorrentState.SEEDING }) DownloadService.ensureRunning(ctx, downloads)
        }
    }

    private suspend fun finished(hash: String) {
        val t = dao.get(hash) ?: return
        if (t.state == TorrentRunState.COMPLETED) return
        // افتراضياً لا مشاركة بعد الاكتمال (الرفع يستهلك بيانات المستخدم) إلا إن طلبها
        if (!t.seedAfter) engine.pause(hash)
        dao.upsert(t.copy(state = TorrentRunState.COMPLETED, completedAt = System.currentTimeMillis()))
        if (settings.notifyOnComplete) notifier.torrentCompleted(t.name)
    }

    suspend fun fetchMagnet(uri: String): TorrentMeta? = withContext(Dispatchers.IO) {
        lock.withLock { ensureEngine() }
        engine.fetchMagnet(uri, 90)
    }

    fun parse(raw: ByteArray): TorrentMeta = run { engine; TorrentEngine.parse(raw) }

    suspend fun add(meta: TorrentMeta, wanted: Set<Int>, seedAfter: Boolean, ratio: Float, magnet: String?) = withContext(Dispatchers.IO) {
        val dir = File(StorageDirs.directRoot(), "تورنت") // libtorrent يكتب في مكانه النهائي مباشرة
        val wantedSize = meta.files.filter { it.index in wanted }.sumOf { it.size }
        dao.upsert(TorrentEntity(meta.infoHash, meta.name, dir.path, meta.raw, wanted.joinToString(","), meta.totalSize, wantedSize,
            seedAfter = seedAfter, ratioLimit = ratio, magnet = magnet))
        lock.withLock {
            ensureEngine()
            engine.add(meta, dir, wanted)
        }
        DownloadService.ensureRunning(ctx, downloads)
    }

    suspend fun pause(hash: String) = withContext(Dispatchers.IO) { engine.pause(hash); dao.setState(hash, TorrentRunState.PAUSED) }
    suspend fun resume(hash: String) = withContext(Dispatchers.IO) {
        val t = dao.get(hash) ?: return@withContext
        lock.withLock { ensureEngine(); if (engine.status(hash) == null) restore(t.copy(state = TorrentRunState.ACTIVE)) }
        engine.resume(hash)
        if (t.state != TorrentRunState.COMPLETED) dao.setState(hash, TorrentRunState.ACTIVE)
    }
    suspend fun recheck(hash: String) = withContext(Dispatchers.IO) { engine.recheck(hash) }
    suspend fun setWanted(hash: String, wanted: Set<Int>) = withContext(Dispatchers.IO) {
        val t = dao.get(hash) ?: return@withContext
        val meta = TorrentEngine.parse(t.raw)
        engine.setWanted(hash, wanted)
        dao.upsert(t.copy(wanted = wanted.joinToString(","), wantedSize = meta.files.filter { it.index in wanted }.sumOf { it.size }, state = if (t.state == TorrentRunState.COMPLETED) TorrentRunState.ACTIVE else t.state))
    }
    suspend fun setLimits(hash: String, down: Int, up: Int) = withContext(Dispatchers.IO) {
        engine.setLimits(hash, down, up); dao.get(hash)?.let { dao.upsert(it.copy(downLimit = down, upLimit = up)) }
    }
    suspend fun setSeeding(hash: String, seed: Boolean, ratio: Float) = withContext(Dispatchers.IO) {
        val t = dao.get(hash) ?: return@withContext
        dao.upsert(t.copy(seedAfter = seed, ratioLimit = ratio))
        if (t.state == TorrentRunState.COMPLETED) { if (seed) engine.resume(hash) else engine.pause(hash) }
    }
    suspend fun remove(hash: String, deleteFiles: Boolean) = withContext(Dispatchers.IO) {
        if (engine.running) engine.remove(hash, deleteFiles)
        dao.delete(hash)
    }
    fun peers(hash: String): List<PeerSnapshot> = if (engine.running) engine.peers(hash) else emptyList()
    fun trackers(hash: String): List<String> = if (engine.running) engine.trackers(hash) else emptyList()
    fun magnet(hash: String): String? = if (engine.running) engine.magnetOf(hash) else null
}
