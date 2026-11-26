package com.tadaffuq.app.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.tadaffuq.app.BuildConfig
import com.tadaffuq.app.download.ClientProvider
import com.tadaffuq.data.SettingsRepository
import com.tadaffuq.engine.GitHubReleases
import com.tadaffuq.engine.SelfUpdate
import com.tadaffuq.engine.SourceException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** حالة التحديث كما تراها الواجهة. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val c: SelfUpdate.Candidate) : UpdateState
    data class Downloading(val c: SelfUpdate.Candidate, val done: Long, val total: Long) : UpdateState
    data class Verifying(val c: SelfUpdate.Candidate) : UpdateState
    /** جاهز؛ ينتظر سماح المستخدم بتثبيت التطبيقات من تدفّق (إعداد النظام). */
    data class NeedsPermission(val c: SelfUpdate.Candidate, val file: File) : UpdateState
    data class Installing(val c: SelfUpdate.Candidate) : UpdateState
    data class Failed(val reason: Reason, val c: SelfUpdate.Candidate? = null) : UpdateState
    enum class Reason { NETWORK, RATE_LIMITED, CHECKSUM, SIGNATURE, SPACE, INSTALL, UNSUPPORTED }
}

/**
 * التحديث من داخل التطبيق (نسخة GitHub): فحص ← تنزيل ← تحقق من البصمة SHA-256 ومن أن الحزمة موقّعة
 * بشهادة التطبيق المثبّت نفسها وبرقم إصدار أعلى ← تثبيت عبر PackageInstaller (Android يعرض نافذة الموافقة).
 * لا يُثبَّت شيء دون هذه الشروط، ولا شيء في نسخة Play.
 */
@Singleton
class AppUpdater @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val clients: ClientProvider,
    private val settings: SettingsRepository,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    val enabled: Boolean get() = BuildConfig.SELF_UPDATE

    /** آخر فحص كان بطلب المستخدم (يُظهر النافذة حتى لإصدار سبق تأجيله). */
    @Volatile var lastCheckManual = false
        private set

    private suspend fun client() = clients.get(settings.current())

    /** @param manual فحص يطلبه المستخدم (يُظهر «لديك أحدث إصدار»)؛ التلقائي يحترم المهلة وإعداد الإيقاف. */
    suspend fun check(manual: Boolean): SelfUpdate.Candidate? = withContext(Dispatchers.IO) {
        if (!enabled) { _state.value = UpdateState.Failed(UpdateState.Reason.UNSUPPORTED); return@withContext null }
        val s = settings.current()
        if (!manual && (!s.autoUpdateCheck || !SelfUpdate.shouldCheck(s.updateCheckedAt, System.currentTimeMillis()))) return@withContext null
        lastCheckManual = manual
        _state.value = UpdateState.Checking
        try {
            val gh = GitHubReleases(client())
            val releases = gh.releases(SelfUpdate.REPO, 10)
            // بصمات أحدث ثلاثة إصدارات فقط (ملف صغير لكل منها)
            val sums = releases.take(3).associateWith { runCatching { gh.checksums(it) }.getOrDefault(emptyMap()) }
            val c = SelfUpdate.pick(releases, BuildConfig.VERSION_CODE, Build.SUPPORTED_ABIS.toList(), { sums[it].orEmpty() })
            settings.update { it.copy(updateCheckedAt = System.currentTimeMillis()) }
            _state.value = if (c != null) UpdateState.Available(c) else if (manual) UpdateState.UpToDate else UpdateState.Idle
            c
        } catch (e: SourceException) {
            _state.value = when {
                e.kind == SourceException.Kind.NOT_FOUND -> if (manual) UpdateState.UpToDate else UpdateState.Idle // لا إصدارات منشورة بعد
                !manual -> UpdateState.Idle
                e.kind == SourceException.Kind.RATE_LIMITED -> UpdateState.Failed(UpdateState.Reason.RATE_LIMITED)
                else -> UpdateState.Failed(UpdateState.Reason.NETWORK)
            }
            null
        }
    }

    suspend fun dismiss(c: SelfUpdate.Candidate) {
        settings.update { it.copy(updateDismissedCode = c.versionCode) }
        _state.value = UpdateState.Idle
    }

    /** تنزيل وتحقق ثم تثبيت. يعيد بعد بدء التثبيت أو عند الحاجة لإذن. */
    suspend fun downloadAndInstall(c: SelfUpdate.Candidate) = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "update").apply { mkdirs() }
        dir.listFiles()?.forEach { if (it.name != c.asset.name) it.delete() } // تنظيف تحديثات قديمة
        val file = File(dir, c.asset.name)
        if (c.asset.size > 0 && dir.usableSpace in 0 until c.asset.size + 20L * 1024 * 1024) { _state.value = UpdateState.Failed(UpdateState.Reason.SPACE, c); return@withContext }
        val ok = if (file.isFile && sha256(file) == c.sha256) true else download(c, file)
        if (!ok) return@withContext
        _state.value = UpdateState.Verifying(c)
        if (sha256(file) != c.sha256) { file.delete(); _state.value = UpdateState.Failed(UpdateState.Reason.CHECKSUM, c); return@withContext }
        if (!sameSignerAndNewer(file)) { file.delete(); _state.value = UpdateState.Failed(UpdateState.Reason.SIGNATURE, c); return@withContext }
        install(c, file)
    }

    private suspend fun download(c: SelfUpdate.Candidate, file: File): Boolean {
        val part = File(file.path + ".part")
        val md = MessageDigest.getInstance("SHA-256")
        try {
            client().newCall(Request.Builder().url(c.asset.url).header("User-Agent", "Tadaffuq/${BuildConfig.VERSION_NAME}").build()).execute().use { r ->
                if (!r.isSuccessful) { _state.value = UpdateState.Failed(UpdateState.Reason.NETWORK, c); return false }
                val body = r.body ?: run { _state.value = UpdateState.Failed(UpdateState.Reason.NETWORK, c); return false }
                val total = body.contentLength().takeIf { it > 0 } ?: c.asset.size
                var done = 0L
                var lastEmit = 0L
                part.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n); md.update(buf, 0, n); done += n
                            val now = System.currentTimeMillis()
                            if (now - lastEmit > 150) { _state.value = UpdateState.Downloading(c, done, total); lastEmit = now }
                        }
                    }
                }
            }
        } catch (e: java.io.IOException) {
            part.delete(); _state.value = UpdateState.Failed(UpdateState.Reason.NETWORK, c); return false
        }
        val hex = md.digest().joinToString("") { "%02x".format(it) }
        if (hex != c.sha256) { part.delete(); _state.value = UpdateState.Failed(UpdateState.Reason.CHECKSUM, c); return false }
        return part.renameTo(file)
    }

    /** الحزمة للتطبيق نفسه، برقم إصدار أعلى، وموقّعة بالشهادات نفسها (لا تحديث بتوقيع غريب). */
    private fun sameSignerAndNewer(file: File): Boolean = runCatching {
        val pm = ctx.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val apk = pm.getPackageArchiveInfo(file.path, flags) ?: return false
        val mine = pm.getPackageInfo(ctx.packageName, flags)
        if (apk.packageName != ctx.packageName || apk.longVersionCode <= mine.longVersionCode) return false
        fun certs(info: android.content.pm.SigningInfo?) = info?.let { (if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory)?.map { s -> s.toCharsString() }?.toSet() }.orEmpty()
        val a = certs(apk.signingInfo); val b = certs(mine.signingInfo)
        a.isNotEmpty() && b.isNotEmpty() && a.intersect(b).isNotEmpty()
    }.getOrDefault(false)

    /** يُستدعى أيضاً بعد عودة المستخدم من إعداد «تثبيت التطبيقات غير المعروفة». */
    fun install(c: SelfUpdate.Candidate, file: File) {
        val pm = ctx.packageManager
        if (!pm.canRequestPackageInstalls()) { _state.value = UpdateState.NeedsPermission(c, file); return }
        _state.value = UpdateState.Installing(c)
        runCatching {
            val installer = pm.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(ctx.packageName)
                if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, file.length()).use { out -> file.inputStream().use { it.copyTo(out) }; session.fsync(out) }
                val intent = Intent(ctx, UpdateInstallReceiver::class.java).setPackage(ctx.packageName)
                val pi = PendingIntent.getBroadcast(ctx, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0))
                session.commit(pi.intentSender)
            }
        }.onFailure { _state.value = UpdateState.Failed(UpdateState.Reason.INSTALL, c) }
    }

    fun permissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    internal fun onInstallResult(status: Int) {
        val c = (state.value as? UpdateState.Installing)?.c
        _state.value = if (status == PackageInstaller.STATUS_SUCCESS) UpdateState.Idle else UpdateState.Failed(UpdateState.Reason.INSTALL, c)
    }

    private fun sha256(f: File) = runCatching { com.tadaffuq.engine.Hashes.of(f, com.tadaffuq.engine.HashAlgo.SHA256) }.getOrNull()
}
