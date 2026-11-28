package com.tadaffuq.app.ui.add

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tadaffuq.app.download.DownloadManager
import com.tadaffuq.app.download.ErrorCodes
import com.tadaffuq.app.download.NewTask
import com.tadaffuq.app.download.StorageDirs
import com.tadaffuq.data.AppDatabase
import com.tadaffuq.data.PackageEntity
import com.tadaffuq.data.SettingsRepository
import com.tadaffuq.data.TaskEntity
import com.tadaffuq.engine.DownloadException
import com.tadaffuq.engine.FileNames
import com.tadaffuq.engine.Hashes
import com.tadaffuq.engine.LinkTools
import com.tadaffuq.engine.ProbeResult
import com.tadaffuq.engine.RequestSpec
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject

/** قاعدة نطاق أسرار الجلسة؛ منطق خالص قابل للاختبار. */
object SessionScope {
    /** رابط واحد: كما التُقط (المحرك يحصرها في مضيفه عند التحويل). عدة روابط: المضيف والمنفذ نفسهما فقط. */
    fun applies(origin: String, url: String, count: Int): Boolean =
        count <= 1 || RequestSpec.authorityOf(origin) == RequestSpec.authorityOf(url)
}

/** ما يصل من المشاركة أو المتصفح أو الحافظة. */
data class AddRequest(
    val text: String,
    val referer: String? = null,
    val userAgent: String? = null,
    val cookies: String? = null,
    /** ترويسات إضافية من التطبيق المرسِل (منقّاة، بلا Referer وUser-Agent والكوكيز). */
    val headers: Map<String, String> = emptyMap(),
    val suggestedName: String? = null,
    /** نوع الملف كما أعلنه التطبيق المرسِل (video/mp4…)؛ يُستعمل للامتداد إن لم يحدده الخادم. */
    val mimeType: String? = null,
    /** جاء من تطبيق آخر («فتح باستخدام» أو المشاركة). */
    val external: Boolean = false,
    /** فُتح رابطاً (ACTION_VIEW)، كأن تدفّق المتصفح الافتراضي. */
    val opened: Boolean = false,
    /** فُتحت من بلاطة النسخ. */
    val fromClipboard: Boolean = false,
    val clipboardEmpty: Boolean = false,
)

enum class DuplicatePolicy { RENAME, REPLACE, SKIP }

data class Item(
    val url: String,
    val probing: Boolean = true,
    val probe: ProbeResult? = null,
    val errorCode: String? = null,
    /** بصمة مفتاح SSH حين يكون المضيف جديداً. */
    val errorDetail: String? = null,
    val name: String = "",
    val selected: Boolean = true,
    val duplicates: List<TaskEntity> = emptyList(),
)

data class AddState(
    val input: String = "",
    val items: List<Item> = emptyList(),
    val connections: Int = 0,
    val expectedHash: String = "",
    val wifiOnly: Boolean = false,
    val startPaused: Boolean = false,
    val referer: String = "",
    val userAgent: String = "",
    val cookies: String = "",
    /** ترويسات إضافية، سطر لكل ترويسة «الاسم: القيمة». */
    val headers: String = "",
    /** ما وصل مع الرابط من التطبيق المرسِل (Referer، User-Agent…) لعرضه. */
    val received: List<String> = emptyList(),
    val mirrors: String = "",
    /** كلمة مرور FTP/SFTP: تُحفظ مشفّرة ولا تبقى في الرابط. */
    val remotePassword: String = "",
    /** رابط Magnet في النص: يُفتح في نافذة التورنت. */
    val magnet: String? = null,
    val scheduledAt: Long? = null,
    /** مجلد فرعي داخل التنزيلات/تدفق لهذه الإضافة (يتقدم على القواعد والتصنيف). */
    val subfolder: String = "",
    val extract: Boolean = false,
    val packageName: String = "",
    val duplicatePolicy: DuplicatePolicy = DuplicatePolicy.RENAME,
    val submitting: Boolean = false,
    val done: Boolean = false,
    /** رابط واحد من تطبيق آخر تبيّن أنه صفحة ويب لا ملف. */
    val webPage: String? = null,
    /** تُفتح الصفحة في المتصفح المدمج فوراً (رابط مفتوح + الإعداد مفعّل). */
    val autoOpenPage: Boolean = false,
) {
    val single get() = items.size == 1
    val rejectedHeaders get() = com.tadaffuq.engine.ExtraHeaders.rejectedLines(headers)
    val hashValid get() = expectedHash.isBlank() || Hashes.detect(expectedHash) != null
    val anyDuplicate get() = items.any { it.selected && it.duplicates.isNotEmpty() }
    val anyRemote get() = items.any { com.tadaffuq.engine.Protocols.isRemote(it.url) }
    val canSubmit get() = !submitting && hashValid && items.any { it.selected && !it.probing && it.probe != null }
}

@HiltViewModel
class AddViewModel @Inject constructor(
    private val manager: DownloadManager,
    private val db: AppDatabase,
    private val settingsRepo: SettingsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(AddState())
    val state: StateFlow<AddState> = _state
    private var parseJob: Job? = null
    private var request: AddRequest? = null

    fun open(req: AddRequest) {
        request = req
        _state.value = AddState(
            input = req.text,
            referer = req.referer.orEmpty(),
            userAgent = req.userAgent.orEmpty(),
            cookies = req.cookies.orEmpty(),
            headers = com.tadaffuq.engine.ExtraHeaders.format(req.headers),
            received = if (!req.external) emptyList() else listOfNotNull(
                req.referer?.let { "Referer" }, req.userAgent?.let { "User-Agent" }, req.cookies?.let { "Cookies" },
            ) + req.headers.keys,
        )
        parse(immediate = true)
    }

    private fun isHtml(mime: String?) = mime?.substringBefore(';')?.trim()?.lowercase() in setOf("text/html", "application/xhtml+xml")

    fun setInput(text: String) { _state.update { it.copy(input = text) }; parse(immediate = false) }

    private fun parse(immediate: Boolean) {
        parseJob?.cancel()
        parseJob = viewModelScope.launch {
            if (!immediate) delay(500)
            val text = _state.value.input
            val magnet = Regex("""magnet:\?[^\s<>"']+""", RegexOption.IGNORE_CASE).find(text)?.value
            _state.update { it.copy(magnet = magnet, webPage = null, autoOpenPage = false) }
            val raw = LinkTools.extract(text).flatMap { if (LinkTools.hasSequence(it)) LinkTools.expandSequence(it) else listOf(it) }
                .filter(com.tadaffuq.engine.Protocols::isSupported)
            // كلمة مرور داخل رابط FTP/SFTP تُنقل إلى المخزن المشفّر وتُحذف من الرابط
            raw.forEach { u ->
                com.tadaffuq.engine.RemoteTarget.passwordInUrl(u)?.let { pw ->
                    com.tadaffuq.engine.RemoteTarget.parse(u)?.let { manager.credentials.setPassword(it, pw) }
                    _state.update { st -> st.copy(remotePassword = pw) }
                }
            }
            val urls = raw.map(com.tadaffuq.engine.RemoteTarget::stripPassword).distinct().take(LinkTools.MAX_GENERATED)
            _state.update { s -> s.copy(items = urls.map { u -> s.items.firstOrNull { it.url == u } ?: Item(u) }) }
            probeAll()
        }
    }

    private suspend fun probeAll() {
        val sem = Semaphore(4)
        val s = _state.value
        val settings = settingsRepo.current().also { settingsCache = it }
        kotlinx.coroutines.coroutineScope {
            s.items.filter { it.probing }.map { item ->
                async {
                    sem.withPermit {
                        val result = runCatching { manager.probe(spec(item.url)) }
                        val probe = result.getOrNull()
                        val err = (result.exceptionOrNull() as? DownloadException)?.error
                        val code = err?.let(ErrorCodes::of) ?: result.exceptionOrNull()?.let { "NETWORK" }
                        val detail = err?.let(ErrorCodes::detail)
                        val name = when {
                            // عنوان الحلقة من التطبيق المرسِل: يأخذ امتداد الخادم أو نوع الملف إن خلا منه
                            s.single && request?.suggestedName != null ->
                                FileNames.withExtension(request!!.suggestedName!!, probe?.fileName, probe?.mimeType ?: request?.mimeType)
                            probe != null -> probe.fileName
                            else -> FileNames.resolve(null, item.url, null)
                        }
                        val page = request?.external == true && s.single && probe != null && isHtml(probe.mimeType)
                        if (page) _state.update { it.copy(webPage = probe!!.finalUrl, autoOpenPage = request?.opened == true && settings.openPagesInBrowser) }
                        val dir = StorageDirs.dirFor(FileNames.category(name), settings.sortByCategory).path
                        val dups = manager.duplicates(item.url, dir, name)
                        _state.update { st ->
                            st.copy(items = st.items.map {
                                if (it.url == item.url) it.copy(probing = false, probe = probe, errorCode = code, errorDetail = detail, name = name, duplicates = dups) else it
                            })
                        }
                    }
                }
            }.awaitAll()
        }
    }

    private fun spec(url: String): RequestSpec {
        val s = _state.value
        val own = sessionApplies(url)
        return RequestSpec(url, headers = headersFor(url), referer = s.referer.ifBlank { null }?.takeIf { own }, userAgent = s.userAgent.ifBlank { null }, cookies = s.cookies.ifBlank { null }?.takeIf { own })
    }

    /** الترويسات الإضافية تخص الرابط الذي جاءت معه كالكوكيز؛ Referer وUser-Agent والكوكيز لها حقولها. */
    private fun headersFor(url: String): Map<String, String> {
        if (!sessionApplies(url)) return emptyMap()
        return com.tadaffuq.engine.ExtraHeaders.without(com.tadaffuq.engine.ExtraHeaders.parse(_state.value.headers), com.tadaffuq.engine.ExtraHeaders.dedicated)
    }

    /** الكوكيز وReferer تخص الرابط الذي التُقطت معه؛ في الروابط المتعددة تُطبَّق على المضيف نفسه فقط. */
    private fun sessionApplies(url: String): Boolean {
        val s = _state.value
        val origin = request?.text?.let { LinkTools.extract(it).firstOrNull() } ?: s.items.firstOrNull()?.url ?: return true
        return SessionScope.applies(origin, url, s.items.size)
    }

    /** المستخدم وافق على بصمة مفتاح الخادم بعد مقارنتها. */
    fun trustHostKey(item: Item) {
        val t = com.tadaffuq.engine.RemoteTarget.parse(item.url) ?: return
        item.errorDetail?.let { manager.credentials.trustHostKey(t.host, t.port, it) }
        reprobe()
    }

    fun reprobe() {
        val pw = _state.value.remotePassword
        if (pw.isNotEmpty()) _state.value.items.forEach { i -> com.tadaffuq.engine.RemoteTarget.parse(i.url)?.let { manager.credentials.setPassword(it, pw) } }
        _state.update { s -> s.copy(items = s.items.map { it.copy(probing = true, errorCode = null) }) }
        viewModelScope.launch { probeAll() }
    }

    fun rename(url: String, name: String) = _state.update { s -> s.copy(items = s.items.map { if (it.url == url) it.copy(name = name) else it }) }
    fun toggle(url: String) = _state.update { s -> s.copy(items = s.items.map { if (it.url == url) it.copy(selected = !it.selected) else it }) }
    fun selectAll(v: Boolean) = _state.update { s -> s.copy(items = s.items.map { it.copy(selected = v) }) }
    fun update(f: (AddState) -> AddState) = _state.update(f)

    /** [start] = «بدء» الآن، وإلا «إضافة» إلى القائمة متوقفاً. */
    fun submit(start: Boolean = true) {
        _state.update { it.copy(startPaused = !start) }
        val s = _state.value
        if (s.submitting) return
        _state.update { it.copy(submitting = true) }
        viewModelScope.launch {
            val settings = settingsRepo.current()
            val packageId = if (s.items.count { it.selected } > 1)
                db.links().insertPackage(PackageEntity(name = s.packageName.ifBlank { "حزمة ${com.tadaffuq.app.core.Fmt.date(System.currentTimeMillis())}" }))
            else null
            val toAdd = s.items.filter { it.selected && !it.probing && it.probe != null }.mapNotNull { item ->
                if (item.duplicates.isNotEmpty()) when (s.duplicatePolicy) {
                    DuplicatePolicy.SKIP -> return@mapNotNull null
                    // الاستبدال بموافقة صريحة: القديم يُنقل إلى السلة (قابل للاستعادة) لا يُحذف، فلا يضيع إن فشل الجديد
                    DuplicatePolicy.REPLACE -> manager.trash(item.duplicates.map { it.id })
                    DuplicatePolicy.RENAME -> Unit // الاسم الفريد يُحجز عند الإضافة
                }
                val name = FileNames.sanitize(item.name.ifBlank { item.probe?.fileName ?: "download" })
                // قواعد التنظيم: أول قاعدة مطابقة تحدد المجلد والأولوية والسرعة والفك
                val rule = com.tadaffuq.data.Rule.firstMatch(settings.rules, name, item.url, item.probe?.totalBytes ?: -1)
                val dir = destination(item, settings, s).path
                NewTask(
                    url = item.url,
                    fileName = name,
                    dirPath = dir,
                    priority = rule?.priority ?: 0,
                    speedLimitBps = (rule?.speedLimitKbps ?: 0) * 1024,
                    postExtract = s.extract || rule?.extract == true,
                    scheduledAt = s.scheduledAt,
                    probe = item.probe,
                    connections = s.connections,
                    expectedHash = if (s.single) s.expectedHash.ifBlank { null } else null,
                    userAgent = s.userAgent.ifBlank { null },
                    referer = s.referer.ifBlank { null }?.takeIf { sessionApplies(item.url) },
                    cookies = s.cookies.ifBlank { null }?.takeIf { sessionApplies(item.url) },
                    headers = headersFor(item.url),
                    packageId = packageId,
                    wifiOnly = s.wifiOnly,
                    startPaused = s.startPaused,
                    mirrors = if (s.single) s.mirrors.ifBlank { null } else null,
                )
            }
            manager.enqueue(toAdd)
            _state.update { it.copy(submitting = false, done = true) }
        }
    }

    @Volatile private var settingsCache: com.tadaffuq.data.Settings? = null

    /** مجلد الحفظ الفعلي لعنصر (المجلد الفرعي ← قاعدة مطابقة ← الفئة)، نفسه المعروض والمستعمل عند الإضافة. */
    private fun destination(item: Item, settings: com.tadaffuq.data.Settings, s: AddState): java.io.File {
        val name = FileNames.sanitize(item.name.ifBlank { item.probe?.fileName ?: "download" })
        val rule = com.tadaffuq.data.Rule.firstMatch(settings.rules, name, item.url, item.probe?.totalBytes ?: -1)
        return com.tadaffuq.data.Rule.safeFolder(s.subfolder).takeIf { it.isNotEmpty() }?.let { java.io.File(StorageDirs.root(), it) }
            ?: rule?.folder?.let(com.tadaffuq.data.Rule::safeFolder)?.takeIf { it.isNotEmpty() }?.let { java.io.File(StorageDirs.root(), it) }
            ?: StorageDirs.dirFor(FileNames.category(name), settings.sortByCategory)
    }

    /** للعرض: «Download/تدفق/…» بدل المسار الكامل. */
    fun displayDir(item: Item): String? {
        val st = settingsCache ?: return null
        return StorageDirs.display(destination(item, st, _state.value))
    }

    fun saveForLater() = viewModelScope.launch {
        db.links().insert(_state.value.items.filter { it.selected }.map { com.tadaffuq.data.SavedLinkEntity(url = it.url, title = it.name.ifBlank { null }) })
        _state.update { it.copy(done = true) }
    }
}
