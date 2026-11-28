package com.tadaffuq.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tadaffuq.engine.SpeedMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class ListDensity { COMFORTABLE, COMPACT }
enum class ProxyMode { NONE, HTTP, SOCKS5 }
enum class Accent { MINT, BLUE, VIOLET, AMBER, ROSE }
/** ثيمات جاهزة: الأساس (الخلفية والأسطح والأساسي)؛ لون التمييز فوقها. */
enum class Palette { NAVY_MINT, GREEN_GOLD, ORANGE_VIOLET, IVORY_BLACK }
/** حجم خط داخل التطبيق، يُضرب في تكبير خط النظام (لا يلغيه). */
enum class TextSize(val scale: Float) { SMALL(0.9f), DEFAULT(1f), LARGE(1.15f) }

/** إعدادات التطبيق. القيم الافتراضية بسيطة، والمتقدم اختياري. */
data class Settings(
    val maxConnections: Int = 8,
    val maxParallel: Int = 3,
    val globalLimitBps: Long = 0,
    val wifiOnly: Boolean = false,
    val mode: SpeedMode = SpeedMode.NORMAL,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    /** "" = لغة النظام، "ar"، "en". */
    val language: String = "",
    val suggestClipboard: Boolean = false,
    val notifyOnComplete: Boolean = true,
    val sortByCategory: Boolean = true,
    val pauseOnLowBattery: Int = 15,
    val onlyWhileCharging: Boolean = false,
    val pauseWhenHot: Boolean = true,
    val dailyLimitBytes: Long = 0,
    val monthlyLimitBytes: Long = 0,
    val blockPopups: Boolean = true,
    /** رابط صفحة ويب فُتح بتدفّق (متصفحاً افتراضياً): يُفتح في المتصفح المدمج بدل نافذة التنزيل. */
    val openPagesInBrowser: Boolean = true,
    val blockTrackers: Boolean = false,
    val desktopMode: Boolean = false,
    val density: ListDensity = ListDensity.COMFORTABLE,
    /** ترتيب قائمة التنزيلات (اسم DownloadSort)؛ عرض فقط، لا يغيّر أولوية التنفيذ. */
    val downloadsSort: String = "NEWEST",
    val showLiveScene: Boolean = true,
    val userAgent: String = "",
    val warnExecutables: Boolean = true,
    val onboarded: Boolean = false,
    val proxyMode: ProxyMode = ProxyMode.NONE,
    val proxyHost: String = "",
    val proxyPort: Int = 0,
    val proxyUser: String = "",
    val reduceMotion: Boolean = false,
    val torrentDht: Boolean = true,
    /** بايت/ث، 0 = بلا حد. الرفع محدود افتراضياً حفاظاً على بيانات المستخدم. */
    val torrentDownLimit: Int = 0,
    val torrentUpLimit: Int = 256 * 1024,
    val torrentSeedDefault: Boolean = false,
    val torrentRatio: Float = 1f,
    /** قواعد التنظيم بصيغة JSON (انظر Rule). */
    val rulesJson: String = "[]",
    /** فك الأرشيفات تلقائياً بعد اكتمالها. */
    val autoExtract: Boolean = false,
    /** مجلد SAF يُنقل إليه كل ملف بعد اكتماله (بطاقة SD أو USB أو مجلد آخر)، أو فارغ. */
    val moveToTree: String = "",
    /** ترحيل لمرة واحدة: المهام غير المكتملة قبل rc4 تأخذ مجلد SAF العام وقت التحديث. */
    val perTaskTreeMigrated: Boolean = false,
    /** نافذة زمنية يومية للتنزيل (مثلاً الليل). */
    val windowEnabled: Boolean = false,
    val windowStart: Int = 1,
    val windowEnd: Int = 7,
    /** إيقاف الطابور إن قلّت المساحة الحرة عن هذا الحد (م.ب)، 0 = معطّل. */
    val minFreeMb: Long = 500,
    /** واجهة RPC: مغلقة افتراضياً، ومحلية فقط ما لم يُسمح بالشبكة. */
    val rpcEnabled: Boolean = false,
    val rpcAllowNetwork: Boolean = false,
    val rpcPort: Int = 6800,
    /** ألوان Material You من خلفية الجهاز (Android 12+). */
    val dynamicColor: Boolean = false,
    val accent: Accent = Accent.MINT,
    val palette: Palette = Palette.NAVY_MINT,
    val textSize: TextSize = TextSize.DEFAULT,
    /** إشعار لكل ملف يعمل (تحت الإشعار الإجمالي). */
    val notifyPerTask: Boolean = true,
    val notifyOnFail: Boolean = true,
    val notifySound: Boolean = true,
    val notifyVibrate: Boolean = false,
    /** الضبط الذكي لعدد الاتصالات حسب تحسن السرعة. */
    val smartTuning: Boolean = true,
    /** التقسيم الديناميكي لأكبر جزء متبقٍّ. */
    val dynamicSplit: Boolean = true,
    /** الاستئناف التلقائي بعد الانقطاع وإعادة التشغيل. */
    val autoResume: Boolean = true,
    /** نسخة GitHub: فحص تلقائي للتحديثات كل 12 ساعة عند فتح التطبيق. */
    val autoUpdateCheck: Boolean = true,
    val updateCheckedAt: Long = 0,
    /** versionCode لإصدار أخفى المستخدم تنبيهه («لاحقاً»)؛ لا يُعرض مجدداً تلقائياً. */
    val updateDismissedCode: Int = 0,
    /** محاولات إعادة الاتصال لكل جزء قبل الفشل، والانتظار الأولي (يتضاعف). */
    val maxRetries: Int = 8,
    val retryDelaySec: Int = 1,
    /** إعدادات مستقلة لبيانات الهاتف. */
    val separateMobile: Boolean = false,
    val mobileMaxConnections: Int = 4,
    val mobileParallel: Int = 1,
    val mobileLimitBps: Long = 0,
    /** طريقة عرض التنزيلات. */
    val showSegmentsInList: Boolean = true,
) {
    /** الإعدادات الفعلية حسب الشبكة الحالية: على بيانات الهاتف يُطبَّق ملفها المستقل إن فُعّل. */
    fun forNetwork(onCellular: Boolean): Settings =
        if (separateMobile && onCellular) copy(maxConnections = mobileMaxConnections, maxParallel = mobileParallel, globalLimitBps = mobileLimitBps) else this

    val rules: List<Rule> get() = Rule.listFrom(rulesJson)
}


private val Context.store: DataStore<Preferences> by preferencesDataStore("settings")

class SettingsRepository(private val context: Context) {
    private object K {
        val maxConnections = intPreferencesKey("maxConnections")
        val maxParallel = intPreferencesKey("maxParallel")
        val globalLimit = longPreferencesKey("globalLimitBps")
        val wifiOnly = booleanPreferencesKey("wifiOnly")
        val mode = stringPreferencesKey("mode")
        val theme = stringPreferencesKey("theme")
        val language = stringPreferencesKey("language")
        val clipboard = booleanPreferencesKey("suggestClipboard")
        val notify = booleanPreferencesKey("notifyOnComplete")
        val byCategory = booleanPreferencesKey("sortByCategory")
        val lowBattery = intPreferencesKey("pauseOnLowBattery")
        val charging = booleanPreferencesKey("onlyWhileCharging")
        val hot = booleanPreferencesKey("pauseWhenHot")
        val daily = longPreferencesKey("dailyLimitBytes")
        val monthly = longPreferencesKey("monthlyLimitBytes")
        val popups = booleanPreferencesKey("blockPopups")
        val openPages = booleanPreferencesKey("openPagesInBrowser")
        val trackers = booleanPreferencesKey("blockTrackers")
        val desktop = booleanPreferencesKey("desktopMode")
        val density = stringPreferencesKey("density")
        val dlSort = stringPreferencesKey("downloadsSort")
        val scene = booleanPreferencesKey("showLiveScene")
        val ua = stringPreferencesKey("userAgent")
        val warnExe = booleanPreferencesKey("warnExecutables")
        val onboarded = booleanPreferencesKey("onboarded")
        val proxyMode = stringPreferencesKey("proxyMode")
        val proxyHost = stringPreferencesKey("proxyHost")
        val proxyPort = intPreferencesKey("proxyPort")
        val proxyUser = stringPreferencesKey("proxyUser")
        val reduceMotion = booleanPreferencesKey("reduceMotion")
        val tDht = booleanPreferencesKey("torrentDht")
        val tDown = intPreferencesKey("torrentDownLimit")
        val tUp = intPreferencesKey("torrentUpLimit")
        val tSeed = booleanPreferencesKey("torrentSeedDefault")
        val tRatio = androidx.datastore.preferences.core.floatPreferencesKey("torrentRatio")
        val rules = stringPreferencesKey("rulesJson")
        val autoExtract = booleanPreferencesKey("autoExtract")
        val moveTree = stringPreferencesKey("moveToTree")
        val treeMigrated = booleanPreferencesKey("perTaskTreeMigrated")
        val wEnabled = booleanPreferencesKey("windowEnabled")
        val wStart = intPreferencesKey("windowStart")
        val wEnd = intPreferencesKey("windowEnd")
        val minFree = longPreferencesKey("minFreeMb")
        val rpcOn = booleanPreferencesKey("rpcEnabled")
        val rpcNet = booleanPreferencesKey("rpcAllowNetwork")
        val rpcPort = intPreferencesKey("rpcPort")
        val dynamic = booleanPreferencesKey("dynamicColor")
        val accent = stringPreferencesKey("accent")
        val palette = stringPreferencesKey("palette")
        val textSize = stringPreferencesKey("textSize")
        val nPer = booleanPreferencesKey("notifyPerTask")
        val nFail = booleanPreferencesKey("notifyOnFail")
        val nSound = booleanPreferencesKey("notifySound")
        val nVib = booleanPreferencesKey("notifyVibrate")
        val smart = booleanPreferencesKey("smartTuning")
        val split = booleanPreferencesKey("dynamicSplit")
        val autoResume = booleanPreferencesKey("autoResume")
        val autoUpdate = booleanPreferencesKey("autoUpdateCheck")
        val updateAt = longPreferencesKey("updateCheckedAt")
        val updateDismissed = intPreferencesKey("updateDismissedCode")
        val retries = intPreferencesKey("maxRetries")
        val retryDelay = intPreferencesKey("retryDelaySec")
        val sepMobile = booleanPreferencesKey("separateMobile")
        val mConn = intPreferencesKey("mobileMaxConnections")
        val mPar = intPreferencesKey("mobileParallel")
        val mLimit = longPreferencesKey("mobileLimitBps")
        val segList = booleanPreferencesKey("showSegmentsInList2")
    }

    val settings: Flow<Settings> = context.store.data.map { p ->
        val s = readFrom(p)
        s.copy(maxConnections = s.maxConnections.coerceIn(1, 32), maxParallel = s.maxParallel.coerceIn(1, 10))
    }

    suspend fun current(): Settings = settings.first()

    suspend fun update(transform: (Settings) -> Settings) {
        context.store.edit { p ->
            val s = transform(readFrom(p))
            p[K.maxConnections] = s.maxConnections.coerceIn(1, 32)
            p[K.maxParallel] = s.maxParallel.coerceIn(1, 10)
            p[K.globalLimit] = s.globalLimitBps.coerceAtLeast(0)
            p[K.wifiOnly] = s.wifiOnly
            p[K.mode] = s.mode.name
            p[K.theme] = s.theme.name
            p[K.language] = s.language
            p[K.clipboard] = s.suggestClipboard
            p[K.notify] = s.notifyOnComplete
            p[K.byCategory] = s.sortByCategory
            p[K.lowBattery] = s.pauseOnLowBattery.coerceIn(0, 50)
            p[K.charging] = s.onlyWhileCharging
            p[K.hot] = s.pauseWhenHot
            p[K.daily] = s.dailyLimitBytes.coerceAtLeast(0)
            p[K.monthly] = s.monthlyLimitBytes.coerceAtLeast(0)
            p[K.popups] = s.blockPopups
            p[K.openPages] = s.openPagesInBrowser
            p[K.trackers] = s.blockTrackers
            p[K.desktop] = s.desktopMode
            p[K.density] = s.density.name
            p[K.dlSort] = s.downloadsSort
            p[K.scene] = s.showLiveScene
            p[K.ua] = s.userAgent
            p[K.warnExe] = s.warnExecutables
            p[K.onboarded] = s.onboarded
            p[K.proxyMode] = s.proxyMode.name
            p[K.proxyHost] = s.proxyHost
            p[K.proxyPort] = s.proxyPort
            p[K.proxyUser] = s.proxyUser
            p[K.reduceMotion] = s.reduceMotion
            p[K.tDht] = s.torrentDht
            p[K.tDown] = s.torrentDownLimit.coerceAtLeast(0)
            p[K.tUp] = s.torrentUpLimit.coerceAtLeast(0)
            p[K.tSeed] = s.torrentSeedDefault
            p[K.tRatio] = s.torrentRatio.coerceIn(0.1f, 100f)
            p[K.rules] = s.rulesJson
            p[K.autoExtract] = s.autoExtract
            p[K.moveTree] = s.moveToTree
            p[K.treeMigrated] = s.perTaskTreeMigrated
            p[K.wEnabled] = s.windowEnabled
            p[K.wStart] = s.windowStart.coerceIn(0, 23)
            p[K.wEnd] = s.windowEnd.coerceIn(0, 23)
            p[K.minFree] = s.minFreeMb.coerceAtLeast(0)
            p[K.rpcOn] = s.rpcEnabled
            p[K.rpcNet] = s.rpcAllowNetwork
            p[K.rpcPort] = s.rpcPort.coerceIn(1024, 65535)
            p[K.dynamic] = s.dynamicColor
            p[K.accent] = s.accent.name
            p[K.palette] = s.palette.name
            p[K.textSize] = s.textSize.name
            p[K.nPer] = s.notifyPerTask
            p[K.nFail] = s.notifyOnFail
            p[K.nSound] = s.notifySound
            p[K.nVib] = s.notifyVibrate
            p[K.smart] = s.smartTuning
            p[K.split] = s.dynamicSplit
            p[K.autoResume] = s.autoResume
            p[K.autoUpdate] = s.autoUpdateCheck
            p[K.updateAt] = s.updateCheckedAt
            p[K.updateDismissed] = s.updateDismissedCode
            p[K.retries] = s.maxRetries.coerceIn(0, 50)
            p[K.retryDelay] = s.retryDelaySec.coerceIn(1, 60)
            p[K.sepMobile] = s.separateMobile
            p[K.mConn] = s.mobileMaxConnections.coerceIn(1, 32)
            p[K.mPar] = s.mobileParallel.coerceIn(1, 10)
            p[K.mLimit] = s.mobileLimitBps.coerceAtLeast(0)
            p[K.segList] = s.showSegmentsInList
        }
    }

    private fun readFrom(p: Preferences): Settings {
        val d = Settings()
        return Settings(
            maxConnections = p[K.maxConnections] ?: d.maxConnections,
            maxParallel = p[K.maxParallel] ?: d.maxParallel,
            globalLimitBps = p[K.globalLimit] ?: d.globalLimitBps,
            wifiOnly = p[K.wifiOnly] ?: d.wifiOnly,
            mode = p[K.mode]?.let { runCatching { SpeedMode.valueOf(it) }.getOrNull() } ?: d.mode,
            theme = p[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.theme,
            language = p[K.language] ?: d.language,
            suggestClipboard = p[K.clipboard] ?: d.suggestClipboard,
            notifyOnComplete = p[K.notify] ?: d.notifyOnComplete,
            sortByCategory = p[K.byCategory] ?: d.sortByCategory,
            pauseOnLowBattery = p[K.lowBattery] ?: d.pauseOnLowBattery,
            onlyWhileCharging = p[K.charging] ?: d.onlyWhileCharging,
            pauseWhenHot = p[K.hot] ?: d.pauseWhenHot,
            dailyLimitBytes = p[K.daily] ?: d.dailyLimitBytes,
            monthlyLimitBytes = p[K.monthly] ?: d.monthlyLimitBytes,
            blockPopups = p[K.popups] ?: d.blockPopups,
            openPagesInBrowser = p[K.openPages] ?: d.openPagesInBrowser,
            blockTrackers = p[K.trackers] ?: d.blockTrackers,
            desktopMode = p[K.desktop] ?: d.desktopMode,
            density = p[K.density]?.let { runCatching { ListDensity.valueOf(it) }.getOrNull() } ?: d.density,
            downloadsSort = p[K.dlSort] ?: d.downloadsSort,
            showLiveScene = p[K.scene] ?: d.showLiveScene,
            userAgent = p[K.ua] ?: d.userAgent,
            warnExecutables = p[K.warnExe] ?: d.warnExecutables,
            onboarded = p[K.onboarded] ?: d.onboarded,
            proxyMode = p[K.proxyMode]?.let { runCatching { ProxyMode.valueOf(it) }.getOrNull() } ?: d.proxyMode,
            proxyHost = p[K.proxyHost] ?: d.proxyHost,
            proxyPort = p[K.proxyPort] ?: d.proxyPort,
            proxyUser = p[K.proxyUser] ?: d.proxyUser,
            reduceMotion = p[K.reduceMotion] ?: d.reduceMotion,
            torrentDht = p[K.tDht] ?: d.torrentDht,
            torrentDownLimit = p[K.tDown] ?: d.torrentDownLimit,
            torrentUpLimit = p[K.tUp] ?: d.torrentUpLimit,
            torrentSeedDefault = p[K.tSeed] ?: d.torrentSeedDefault,
            torrentRatio = p[K.tRatio] ?: d.torrentRatio,
            rulesJson = p[K.rules] ?: d.rulesJson,
            autoExtract = p[K.autoExtract] ?: d.autoExtract,
            moveToTree = p[K.moveTree] ?: d.moveToTree,
            perTaskTreeMigrated = p[K.treeMigrated] ?: d.perTaskTreeMigrated,
            windowEnabled = p[K.wEnabled] ?: d.windowEnabled,
            windowStart = p[K.wStart] ?: d.windowStart,
            windowEnd = p[K.wEnd] ?: d.windowEnd,
            minFreeMb = p[K.minFree] ?: d.minFreeMb,
            rpcEnabled = p[K.rpcOn] ?: d.rpcEnabled,
            rpcAllowNetwork = p[K.rpcNet] ?: d.rpcAllowNetwork,
            rpcPort = p[K.rpcPort] ?: d.rpcPort,
            dynamicColor = p[K.dynamic] ?: d.dynamicColor,
            accent = p[K.accent]?.let { runCatching { Accent.valueOf(it) }.getOrNull() } ?: d.accent,
            palette = p[K.palette]?.let { runCatching { Palette.valueOf(it) }.getOrNull() } ?: d.palette,
            textSize = p[K.textSize]?.let { runCatching { TextSize.valueOf(it) }.getOrNull() } ?: d.textSize,
            notifyPerTask = p[K.nPer] ?: d.notifyPerTask,
            notifyOnFail = p[K.nFail] ?: d.notifyOnFail,
            notifySound = p[K.nSound] ?: d.notifySound,
            notifyVibrate = p[K.nVib] ?: d.notifyVibrate,
            smartTuning = p[K.smart] ?: d.smartTuning,
            dynamicSplit = p[K.split] ?: d.dynamicSplit,
            autoResume = p[K.autoResume] ?: d.autoResume,
            autoUpdateCheck = p[K.autoUpdate] ?: d.autoUpdateCheck,
            updateCheckedAt = p[K.updateAt] ?: d.updateCheckedAt,
            updateDismissedCode = p[K.updateDismissed] ?: d.updateDismissedCode,
            maxRetries = p[K.retries] ?: d.maxRetries,
            retryDelaySec = p[K.retryDelay] ?: d.retryDelaySec,
            separateMobile = p[K.sepMobile] ?: d.separateMobile,
            mobileMaxConnections = p[K.mConn] ?: d.mobileMaxConnections,
            mobileParallel = p[K.mPar] ?: d.mobileParallel,
            mobileLimitBps = p[K.mLimit] ?: d.mobileLimitBps,
            showSegmentsInList = p[K.segList] ?: d.showSegmentsInList,
        )
    }
}
