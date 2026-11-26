package com.tadaffuq.torrent

import org.libtorrent4j.AlertListener
import org.libtorrent4j.Entry
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TcpEndpoint
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.TorrentStatus
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.SaveResumeDataAlert
import org.libtorrent4j.alerts.TorrentAlert
import org.libtorrent4j.swig.libtorrent
import org.libtorrent4j.swig.settings_pack
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class TorrentFile(val index: Int, val path: String, val size: Long)

data class TorrentMeta(
    val infoHash: String,
    val name: String,
    val totalSize: Long,
    val files: List<TorrentFile>,
    val trackers: List<String>,
    /** ملف .torrent الخام (bencode) يُحفظ لإعادة الإضافة بعد إعادة التشغيل. */
    val raw: ByteArray,
)

enum class TorrentState { CHECKING, METADATA, DOWNLOADING, FINISHED, SEEDING, PAUSED, ERROR, UNKNOWN }

data class TorrentInfoSnapshot(
    val infoHash: String,
    val state: TorrentState,
    val progress: Float,
    val wantedDone: Long,
    val wanted: Long,
    val downBps: Int,
    val upBps: Int,
    val peers: Int,
    val seeds: Int,
    /** إجمالي المرفوع/المنزّل منذ الإضافة (من libtorrent، يبقى بعد الاستئناف). */
    val uploaded: Long,
    val downloaded: Long,
    val ratio: Float,
    val error: String?,
)

data class PeerSnapshot(val ip: String, val client: String, val downBps: Int, val upBps: Int, val progress: Float)

data class TorrentSettings(
    val listenPort: Int = 0,
    val dht: Boolean = true,
    val lsd: Boolean = true,
    /** بايت/ث، 0 = بلا حد. */
    val downloadLimit: Int = 0,
    val uploadLimit: Int = 0,
    val maxConnections: Int = 200,
    /** للاختبارات: السماح بأقران على 127.0.0.1. */
    val allowLocalPeers: Boolean = false,
    /** للاختبارات: واجهة استماع محددة بدل كل الواجهات. */
    val listenInterface: String? = null,
)

/**
 * غلاف رفيع حول libtorrent: DHT وPEX وTrackers وLSD، أولويات الملفات، حدود الرفع والتنزيل،
 * إعادة فحص القطع، وبيانات استئناف تُحفظ بعد كل تغيير مهم عبر [onResumeData].
 */
class TorrentEngine(private val stateDir: File) {
    private val session = SessionManager(false)
    private val handles = ConcurrentHashMap<String, TorrentHandle>()
    var onResumeData: ((String, ByteArray) -> Unit)? = null
    var onFinished: ((String) -> Unit)? = null
    @Volatile var running = false
        private set

    private val listener = object : AlertListener {
        override fun types(): IntArray? = intArrayOf(
            AlertType.SAVE_RESUME_DATA.swig(), AlertType.TORRENT_FINISHED.swig(), AlertType.ADD_TORRENT.swig(),
        )

        override fun alert(alert: Alert<*>) {
            when (alert.type()) {
                AlertType.SAVE_RESUME_DATA -> {
                    val a = alert as SaveResumeDataAlert
                    val bytes = Entry(libtorrent.write_resume_data(a.params().swig())).bencode()
                    val hash = a.handle().infoHash().toHex()
                    File(stateDir, "$hash.resume").writeBytes(bytes)
                    onResumeData?.invoke(hash, bytes)
                }
                AlertType.TORRENT_FINISHED -> {
                    val h = (alert as TorrentAlert<*>).handle()
                    h.saveResumeData()
                    onFinished?.invoke(h.infoHash().toHex())
                }
                // مقبض التنبيه يشير إلى ذاكرة التنبيه نفسه التي تُحرَّر بعد تسليمه؛ حفظه كان يسبب انهياراً أصلياً
                // متقطعاً (SIGSEGV في torrent_handle.is_valid) عند الاستعمال لاحقاً. نحفظ نسخة تملك نفسها من الجلسة.
                AlertType.ADD_TORRENT -> {
                    val hash = (alert as TorrentAlert<*>).handle().infoHash()
                    session.find(hash)?.takeIf { it.isValid }?.let { handles[hash.toHex()] = it }
                }
                else -> Unit
            }
        }
    }

    fun start(s: TorrentSettings) {
        if (running) { apply(s); return }
        stateDir.mkdirs()
        session.addListener(listener)
        session.start(SessionParams(pack(s)))
        running = true
    }

    fun apply(s: TorrentSettings) = session.applySettings(pack(s))

    private fun pack(s: TorrentSettings) = SettingsPack().apply {
        listenInterfaces(s.listenInterface ?: "0.0.0.0:${s.listenPort},[::]:${s.listenPort}")
        setEnableDht(s.dht)
        setEnableLsd(s.lsd)
        downloadRateLimit(s.downloadLimit)
        uploadRateLimit(s.uploadLimit)
        connectionsLimit(s.maxConnections)
        if (s.allowLocalPeers) setBoolean(settings_pack.bool_types.allow_multiple_connections_per_ip.swigValue(), true)
    }

    fun stop() {
        if (!running) return
        handles.values.forEach { runCatching { if (it.isValid) it.saveResumeData() } }
        Thread.sleep(300)
        session.removeListener(listener)
        session.stop()
        running = false
        handles.clear()
    }

    fun pauseSession() = session.pause()
    fun resumeSession() = session.resume()
    val sessionPaused: Boolean get() = session.isPaused

    /** ميتاداتا من Magnet عبر DHT/Trackers. null إن انتهت المهلة. */
    fun fetchMagnet(uri: String, timeoutSec: Int): TorrentMeta? =
        session.fetchMagnet(uri, timeoutSec, File(stateDir, "tmp").apply { mkdirs() })?.let(::parse)

    companion object {
        fun parse(raw: ByteArray): TorrentMeta {
            val ti = TorrentInfo(raw)
            val fs = ti.files()
            return TorrentMeta(
                infoHash = ti.infoHash().toHex(),
                name = ti.name(),
                totalSize = ti.totalSize(),
                files = (0 until fs.numFiles()).filterNot { fs.padFileAt(it) }.map { TorrentFile(it, fs.filePath(it), fs.fileSize(it)) },
                trackers = emptyList(),
                raw = raw,
            )
        }

        fun isMagnet(s: String) = s.trim().startsWith("magnet:?", ignoreCase = true)
    }

    /**
     * يضيف التورنت أو يستأنفه. [wanted] مؤشرات الملفات المطلوبة (غيرها يُتجاهل ولا يُنزَّل).
     * بيانات الاستئناف المحفوظة تُستعمل إن وُجدت فلا يُعاد تنزيل ما اكتمل.
     */
    fun add(meta: TorrentMeta, saveDir: File, wanted: Set<Int>, paused: Boolean = false, peers: List<Pair<String, Int>> = emptyList()): String {
        saveDir.mkdirs()
        val ti = TorrentInfo(meta.raw)
        val fs = ti.files()
        val prio = Array(fs.numFiles()) { if (it in wanted) Priority.DEFAULT else Priority.IGNORE }
        val resume = File(stateDir, "${meta.infoHash}.resume").takeIf { it.exists() }
        val flags = if (paused) TorrentFlags.PAUSED else TorrentFlags.AUTO_MANAGED
        session.download(ti, saveDir, resume, prio, null, flags)
        val h = waitHandle(meta.infoHash)
        h?.let { handles[meta.infoHash] = it }
        peers.forEach { (host, port) -> connectPeer(meta.infoHash, host, port) }
        return meta.infoHash
    }

    private fun waitHandle(hash: String): TorrentHandle? {
        repeat(50) {
            session.find(Sha1Hash.parseHex(hash))?.takeIf { it.isValid }?.let { return it }
            Thread.sleep(40)
        }
        return null
    }

    private fun h(hash: String) = handles[hash]?.takeIf { it.isValid } ?: session.find(Sha1Hash.parseHex(hash))?.takeIf { it.isValid }

    fun status(hash: String): TorrentInfoSnapshot? {
        val h = h(hash) ?: return null
        val st = h.status()
        val uploaded = st.allTimeUpload()
        val downloaded = st.allTimeDownload()
        val paused = st.flags().and_(TorrentFlags.PAUSED).non_zero()
        return TorrentInfoSnapshot(
            infoHash = hash,
            state = if (paused) TorrentState.PAUSED else when (st.state()) {
                TorrentStatus.State.CHECKING_FILES, TorrentStatus.State.CHECKING_RESUME_DATA -> TorrentState.CHECKING
                TorrentStatus.State.DOWNLOADING_METADATA -> TorrentState.METADATA
                TorrentStatus.State.DOWNLOADING -> TorrentState.DOWNLOADING
                TorrentStatus.State.FINISHED -> TorrentState.FINISHED
                TorrentStatus.State.SEEDING -> TorrentState.SEEDING
                else -> TorrentState.UNKNOWN
            },
            progress = st.progress(),
            wantedDone = st.totalWantedDone(), wanted = st.totalWanted(),
            downBps = st.downloadPayloadRate(), upBps = st.uploadPayloadRate(),
            peers = st.numPeers(), seeds = st.numSeeds(),
            uploaded = uploaded, downloaded = downloaded,
            ratio = if (downloaded > 0) uploaded.toFloat() / downloaded else 0f,
            error = st.errorCode()?.takeIf { it.isError }?.message,
        )
    }

    /** اتصال مباشر بقرين معروف (للشبكات المحلية والاختبار). */
    fun connectPeer(hash: String, host: String, port: Int) {
        h(hash)?.swig()?.connect_peer(TcpEndpoint(host, port).swig())
    }

    fun peers(hash: String): List<PeerSnapshot> = h(hash)?.peerInfo().orEmpty().map { PeerSnapshot(it.ip(), it.client(), it.downSpeed(), it.upSpeed(), it.progress()) }
    fun trackers(hash: String): List<String> = h(hash)?.trackers().orEmpty().map { it.url() }

    fun pause(hash: String) { h(hash)?.let { it.unsetFlags(TorrentFlags.AUTO_MANAGED); it.pause(); it.saveResumeData() } }
    fun resume(hash: String) { h(hash)?.let { it.setFlags(TorrentFlags.AUTO_MANAGED); it.resume() } }
    fun recheck(hash: String) { h(hash)?.forceRecheck() }
    fun setWanted(hash: String, wanted: Set<Int>) {
        val h = h(hash) ?: return
        val n = h.torrentFile()?.numFiles() ?: return
        h.prioritizeFiles(Array(n) { if (it in wanted) Priority.DEFAULT else Priority.IGNORE })
        h.saveResumeData()
    }
    fun setLimits(hash: String, downBps: Int, upBps: Int) { h(hash)?.let { it.setDownloadLimit(downBps); it.setUploadLimit(upBps) } }
    fun saveResume(hash: String) { h(hash)?.saveResumeData() }
    fun remove(hash: String, deleteFiles: Boolean) {
        val h = h(hash) ?: return
        if (deleteFiles) session.remove(h, org.libtorrent4j.SessionHandle.DELETE_FILES) else session.remove(h)
        handles.remove(hash)
        File(stateDir, "$hash.resume").delete()
    }
    fun magnetOf(hash: String): String? = h(hash)?.makeMagnetUri()
    val sessionDownBps: Long get() = session.downloadRate()
    val sessionUpBps: Long get() = session.uploadRate()
    fun listenPort(): Int = session.listenEndpoints().firstOrNull()?.substringAfterLast(':')?.toIntOrNull() ?: 0
}
