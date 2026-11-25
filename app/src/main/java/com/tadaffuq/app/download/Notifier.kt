package com.tadaffuq.app.download

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.tadaffuq.app.MainActivity
import com.tadaffuq.app.R
import com.tadaffuq.app.core.Fmt
import com.tadaffuq.app.core.formatLatin
import com.tadaffuq.data.TaskEntity
import com.tadaffuq.engine.Snapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * رمز عشوائي خاص بالتطبيق يُرفق بإجراءات الإشعارات التي تغيّر الحالة: MainActivity مصدَّر
 * (للمشاركة وفتح الروابط)، فلا يُنفَّذ إجراء داخلي كـ«استئناف الكل» إلا إذا حمل هذا الرمز.
 */
fun internalNonce(ctx: Context): String {
    val p = ctx.getSharedPreferences("internal", Context.MODE_PRIVATE)
    return p.getString("nonce", null) ?: java.util.UUID.randomUUID().toString().also { p.edit().putString("nonce", it).apply() }
}

@Singleton
class Notifier @Inject constructor(@ApplicationContext private val ctx: Context) {
    private val nm = ctx.getSystemService(NotificationManager::class.java)

    @Volatile private var doneChannel = CH_DONE

    fun ensureChannels(sound: Boolean = true, vibrate: Boolean = false) {
        // قنوات أندرويد لا تتغير أصواتها بعد إنشائها: قناة لكل تركيبة صوت/اهتزاز، وتُحذف القديمة
        doneChannel = "done_${if (sound) "s" else "q"}${if (vibrate) "v" else "n"}"
        nm.createNotificationChannel(
            NotificationChannel(CH_ACTIVE, ctx.getString(R.string.channel_active), NotificationManager.IMPORTANCE_LOW).apply {
                description = ctx.getString(R.string.channel_active_desc); setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(doneChannel, ctx.getString(R.string.channel_done), if (sound) NotificationManager.IMPORTANCE_DEFAULT else NotificationManager.IMPORTANCE_LOW).apply {
                description = ctx.getString(R.string.channel_done_desc)
                if (!sound) setSound(null, null)
                enableVibration(vibrate)
                if (vibrate) vibrationPattern = longArrayOf(0, 180, 120, 180)
            },
        )
        nm.notificationChannels.filter { it.id.startsWith("done") && it.id != doneChannel }.forEach { nm.deleteNotificationChannel(it.id) }
    }

    /** يفتح إعدادات النظام لقناة الاكتمال (للتحكم الدقيق بالنغمة). */
    fun channelSettingsIntent(): Intent = Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
        .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, doneChannel)

    private fun canPost() = android.os.Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(action: String? = null, requestCode: Int = 0): PendingIntent =
        PendingIntent.getActivity(
            ctx, requestCode,
            Intent(ctx, MainActivity::class.java).setAction(action ?: MainActivity.ACTION_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_NONCE, internalNonce(ctx)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun action(name: String, id: Long = -1) = PendingIntent.getBroadcast(
        ctx, (name.hashCode() * 31 + id).toInt(),
        Intent(ctx, NotificationActionReceiver::class.java).setAction(name).putExtra(NotificationActionReceiver.EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * إشعار ملف واحد: الاسم والنسبة وشريط التقدم والسرعة اللحظية والمنجز من الإجمالي والوقت المتبقي،
     * وأزرار إيقاف وإلغاء. يندرج تحت الإشعار الإجمالي.
     */
    fun task(t: TaskEntity, s: Snapshot?): Notification {
        val done = s?.downloaded ?: t.downloadedBytes
        val total = s?.total ?: t.totalBytes
        val pct = if (total > 0) (done * 100 / total).toInt() else -1
        val title = if (pct >= 0) "${t.fileName} · ${Fmt.iso("$pct%")}" else t.fileName
        val line1 = s?.let { Fmt.speed(it.speedBps) } ?: ctx.getString(R.string.notif_preparing)
        val line2 = buildString {
            append(Fmt.bytes(done)).append(" / ").append(Fmt.bytes(total))
            if (s != null && s.etaSeconds >= 0) append(" · ").append(ctx.getString(R.string.eta, Fmt.duration(s.etaSeconds)))
        }
        return NotificationCompat.Builder(ctx, CH_ACTIVE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText("$line1 · $line2")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$line1\n$line2"))
            .setProgress(100, pct.coerceAtLeast(0), pct < 0)
            .setOnlyAlertOnce(true).setSilent(true).setOngoing(true)
            .setGroup(GROUP_ACTIVE)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openApp(requestCode = t.id.toInt()))
            .addAction(0, ctx.getString(R.string.action_pause), action(NotificationActionReceiver.PAUSE, t.id))
            .addAction(0, ctx.getString(R.string.cancel), action(NotificationActionReceiver.CANCEL, t.id))
            .build()
    }

    /** بعد الإيقاف من الإشعار: إشعار قابل للإزالة باستئناف أو إلغاء. */
    fun paused(t: TaskEntity) {
        if (!canPost()) return
        val pct = if (t.totalBytes > 0) (t.downloadedBytes * 100 / t.totalBytes).toInt() else 0
        nm.notify(taskId(t.id), NotificationCompat.Builder(ctx, CH_ACTIVE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(t.fileName)
            .setContentText(ctx.getString(R.string.st_paused) + " · " + Fmt.bytes(t.downloadedBytes) + " / " + Fmt.bytes(t.totalBytes))
            .setProgress(100, pct, false)
            .setSilent(true)
            .setContentIntent(openApp(requestCode = t.id.toInt()))
            .addAction(0, ctx.getString(R.string.action_resume), action(NotificationActionReceiver.RESUME, t.id))
            .addAction(0, ctx.getString(R.string.cancel), action(NotificationActionReceiver.CANCEL, t.id))
            .build())
    }

    fun postTasks(list: List<Pair<TaskEntity, Snapshot?>>) {
        if (!canPost()) return
        val ids = list.map { taskId(it.first.id) }.toSet()
        list.forEach { (t, s) -> nm.notify(taskId(t.id), task(t, s)) }
        // إزالة إشعارات المهام التي توقفت عن العمل (عدا المتوقفة يدوياً من الإشعار)
        nm.activeNotifications.filter { it.groupKey?.contains(GROUP_ACTIVE) == true && it.id !in ids && it.id != FG_ID && it.notification.actions?.size == 2 && it.isOngoing }
            .forEach { nm.cancel(it.id) }
    }

    fun clearTask(id: Long) = nm.cancel(taskId(id))
    fun clearAllTasks() = nm.activeNotifications.filter { it.id >= TASK_BASE && it.id < TASK_BASE + 1_000_000 }.forEach { nm.cancel(it.id) }
    private fun taskId(id: Long) = (TASK_BASE + id).toInt()

    /** إشعار موحد لكل المهام العاملة، بأرقام حقيقية فقط. */
    fun summary(live: Map<Long, Snapshot>, runningHttp: Int, hold: Hold?, ext: ExternalLoad = ExternalLoad()): Notification {
        val running = runningHttp + ext.count
        val speed = live.values.sumOf { it.speedBps } + ext.speed
        val done = live.values.sumOf { it.downloaded } + ext.done
        val known = (live.isNotEmpty() || ext.count > 0) && live.values.all { it.total > 0 } && (ext.count == 0 || ext.total > 0)
        val total = if (known) live.values.sumOf { it.total } + ext.total else -1
        val title = if (running > 0) formatLatin(ctx.resources.getQuantityString(R.plurals.notif_running, running), arrayOf(running))
        else ctx.getString(R.string.notif_preparing)
        val text = buildString {
            append(Fmt.speed(speed))
            if (total > 0) append(" · ").append(Fmt.percent(done, total))
            if (hold != null) append(" · ").append(ctx.getString(R.string.notif_held))
        }
        return NotificationCompat.Builder(ctx, CH_ACTIVE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setGroup(GROUP_ACTIVE).setGroupSummary(true)
            .setContentIntent(openApp())
            .apply {
                if (total > 0) setProgress(1000, (done * 1000 / total).toInt(), false) else if (running > 0) setProgress(0, 0, true)
            }
            .addAction(0, ctx.getString(R.string.action_pause_all), action(NotificationActionReceiver.PAUSE_ALL))
            .build()
    }

    fun completed(t: TaskEntity) {
        if (!canPost()) return
        val file = t.filePath?.let(::File)
        // البرامج والحزم (apk/exe…) والملف غير المتحقق منه لا تُفتح مباشرة من الإشعار: تُفتح التنزيلات بتحذيرها
        val risky = file == null || t.hashOk == false || com.tadaffuq.engine.FileNames.category(file.name) == com.tadaffuq.engine.FileCategory.PROGRAMS
        val open = file?.takeIf { it.exists() && !risky }?.let {
            runCatching {
                PendingIntent.getActivity(ctx, t.id.toInt(), StorageDirs.openIntent(ctx, it), PendingIntent.FLAG_IMMUTABLE)
            }.getOrNull()
        } ?: openApp()
        val text = if (t.hashOk == false) ctx.getString(R.string.notif_hash_bad) else Fmt.bytes(t.totalBytes)
        nm.notify(
            (DONE_BASE + t.id).toInt(),
            NotificationCompat.Builder(ctx, doneChannel)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(ctx.getString(R.string.notif_done, t.fileName))
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(open)
                .setGroup(GROUP_DONE)
                .build(),
        )
    }

    fun torrentCompleted(name: String) {
        if (!canPost()) return
        nm.notify(
            (DONE_BASE + 500_000 + name.hashCode() % 100_000).toInt(),
            NotificationCompat.Builder(ctx, doneChannel)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(ctx.getString(R.string.notif_done, name))
                .setContentText(ctx.getString(R.string.torrent_done_text))
                .setAutoCancel(true)
                .setContentIntent(openApp())
                .build(),
        )
    }

    fun failed(t: TaskEntity) {
        if (!canPost()) return
        nm.notify(
            (DONE_BASE + t.id).toInt(),
            NotificationCompat.Builder(ctx, doneChannel)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(ctx.getString(R.string.notif_failed, t.fileName))
                .setContentText(ctx.getString(R.string.notif_failed_text))
                .setAutoCancel(true)
                .setContentIntent(openApp())
                .setGroup(GROUP_DONE)
                .build(),
        )
    }

    /** حين يمنع النظام بدء خدمة أمامية من الخلفية: نطلب من المستخدم لمسة للاستئناف بدل الادعاء بالعمل. */
    fun tapToResume() {
        if (!canPost()) return
        nm.notify(
            RESUME_ID,
            NotificationCompat.Builder(ctx, doneChannel)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(ctx.getString(R.string.notif_tap_resume))
                .setContentText(ctx.getString(R.string.notif_tap_resume_text))
                .setAutoCancel(true)
                .setContentIntent(openApp(MainActivity.ACTION_RESUME_ALL, 7))
                .build(),
        )
    }

    fun timedOut() {
        if (!canPost()) return
        nm.notify(
            RESUME_ID,
            NotificationCompat.Builder(ctx, doneChannel)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(ctx.getString(R.string.notif_timeout))
                .setContentText(ctx.getString(R.string.notif_tap_resume_text))
                .setAutoCancel(true)
                .setContentIntent(openApp(MainActivity.ACTION_RESUME_ALL, 8))
                .build(),
        )
    }

    companion object {
        const val CH_ACTIVE = "active"
        const val CH_DONE = "done"
        const val FG_ID = 1
        const val RESUME_ID = 2
        const val DONE_BASE = 1000L
        const val GROUP_DONE = "done"
        const val GROUP_ACTIVE = "active"
        const val TASK_BASE = 100_000L
    }
}
