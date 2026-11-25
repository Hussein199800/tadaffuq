package com.tadaffuq.app.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tadaffuq.app.R
import com.tadaffuq.data.AppDatabase
import com.tadaffuq.data.TaskStatus
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * يستأنف الطابور بعد إعادة تشغيل الجهاز أو عودة الشبكة والتطبيق مغلق.
 * يعمل كخدمة أمامية عبر WorkManager ما دام هناك عمل؛ إن رفض النظام ذلك يطلب لمسة من المستخدم.
 */
@HiltWorker
class ResumeWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val manager: DownloadManager,
    private val notifier: Notifier,
    private val db: AppDatabase,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val pending = db.tasks().withStatus(listOf(TaskStatus.QUEUED, TaskStatus.WAITING_NETWORK, TaskStatus.RUNNING))
        if (pending.isEmpty()) return Result.success()
        try {
            setForeground(foregroundInfo())
        } catch (e: Exception) {
            notifier.tapToResume()
            return Result.success()
        }
        DownloadService.workerInForeground = true
        try {
            manager.start()
            manager.wake()
            delay(3_000)
            while (manager.hasWork()) {
                setForeground(foregroundInfo())
                delay(2_000)
            }
        } finally {
            DownloadService.workerInForeground = false
        }
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo()

    private fun foregroundInfo(): ForegroundInfo {
        val n = notifier.summary(manager.live.value, manager.runningIds.value.size, manager.hold.value)
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(Notifier.FG_ID + 10, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(Notifier.FG_ID + 10, n)
    }

    companion object {
        const val NAME = "resume"

        fun enqueue(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<ResumeWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, req)
        }

        fun enqueueExpedited(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<ResumeWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, req)
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            ResumeWorker.enqueue(context)
        }
    }
}

@AndroidEntryPoint
class NotificationActionReceiver : BroadcastReceiver() {
    @Inject lateinit var manager: DownloadManager
    @Inject lateinit var notifier: Notifier

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                manager.start()
                val id = intent.getLongExtra(EXTRA_ID, -1)
                when (intent.action) {
                    PAUSE_ALL -> manager.pauseAll()
                    RESUME_ALL -> manager.resumeAll()
                    PAUSE -> { manager.pause(listOf(id)); notifier.clearTask(id); kotlinx.coroutines.delay(400); manager.task(id)?.let(notifier::paused) }
                    RESUME -> { notifier.clearTask(id); manager.resume(listOf(id)) }
                    CANCEL -> { notifier.clearTask(id); manager.delete(listOf(id), withFiles = false) }
                }
            } finally { pending.finish() }
        }
    }

    companion object {
        const val PAUSE_ALL = "com.tadaffuq.app.PAUSE_ALL"
        const val RESUME_ALL = "com.tadaffuq.app.RESUME_ALL"
        const val PAUSE = "com.tadaffuq.app.PAUSE"
        const val RESUME = "com.tadaffuq.app.RESUME"
        const val CANCEL = "com.tadaffuq.app.CANCEL"
        const val EXTRA_ID = "id"
    }
}

/**
 * بلاطة «تدفّق»: انسخ رابطاً من أي تطبيق، اسحب الإعدادات السريعة واضغط البلاطة، فتظهر نافذة الإضافة
 * فوق التطبيق الحالي مع الرابط المنسوخ. البلاطة لا تستطيع قراءة الحافظة (Android 10+ يقصرها على
 * النافذة المركّزة)، فتفتح نشاطاً شفافاً صغيراً يقرأها لحظة حصوله على التركيز، دون الواجهة الرئيسية.
 */
@AndroidEntryPoint
class QuickTileService : TileService() {
    @Inject lateinit var manager: DownloadManager

    override fun onStartListening() {
        val tile = qsTile ?: return
        val active = manager.runningIds.value.size
        tile.state = Tile.STATE_ACTIVE
        tile.label = getString(R.string.tile_label)
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = if (active > 0) getString(R.string.tile_active_n, active) else getString(R.string.tile_add_copied)
        tile.updateTile()
    }

    override fun onClick() {
        val intent = Intent(this, com.tadaffuq.app.QuickAddActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        // فتح النشاط من شاشة القفل يتطلب فتح القفل أولاً (الحافظة والملفات خاصة)
        val launch = {
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(android.app.PendingIntent.getActivity(this, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT))
            } else {
                @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated") startActivityAndCollapse(intent)
            }
        }
        if (isLocked) unlockAndRun(launch) else launch()
    }
}

/**
 * يوقظ الطابور في موعد مجدول. WorkManager لا يضمن الدقة الزمنية (Doze وتوفير الطاقة)،
 * فالموعد تقريبي وتقول الواجهة ذلك صراحة. لا نطلب صلاحية المنبهات الدقيقة.
 */
@HiltWorker
class ScheduleWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val manager: DownloadManager,
) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        manager.start()
        manager.wake()
        ResumeWorker.enqueueExpedited(applicationContext)
        return Result.success()
    }

    companion object {
        fun at(ctx: Context, timeMs: Long) {
            val delay = (timeMs - System.currentTimeMillis()).coerceAtLeast(0)
            val req = OneTimeWorkRequestBuilder<ScheduleWorker>()
                .setInitialDelay(delay, java.util.concurrent.TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("schedule", ExistingWorkPolicy.REPLACE, req)
        }
    }
}

/** طلب إضافة بلاطة النسخ من داخل التطبيق (Android 13+)؛ الأقدم يعرض خطوات يدوية. */
object TileRequest {
    /** يعيد false إن لم يكن الطلب البرمجي متاحاً (تُعرض الخطوات اليدوية). */
    fun request(context: Context, onResult: (Boolean) -> Unit): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 33) return false
        val sbm = context.getSystemService(android.app.StatusBarManager::class.java) ?: return false
        return runCatching {
            sbm.requestAddTileService(
                android.content.ComponentName(context, QuickTileService::class.java),
                context.getString(com.tadaffuq.app.R.string.tile_label),
                android.graphics.drawable.Icon.createWithResource(context, com.tadaffuq.app.R.drawable.ic_notification),
                context.mainExecutor,
            ) { code -> onResult(code == android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED || code == android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) }
            true
        }.getOrDefault(false)
    }
}
