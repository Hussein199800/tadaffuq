package com.tadaffuq.app

import android.app.Notification
import androidx.test.core.app.ApplicationProvider
import com.tadaffuq.app.download.Notifier
import com.tadaffuq.data.TaskEntity
import com.tadaffuq.data.TaskStatus
import com.tadaffuq.engine.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "ar")
class NotifierTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun perTaskNotificationShowsRealProgressAndActions() {
        val n = Notifier(ctx).apply { ensureChannels() }
        val t = TaskEntity(id = 7, url = "https://x/a.iso", fileName = "a.iso", dirPath = "/d", status = TaskStatus.RUNNING, totalBytes = 1000)
        val notif = n.task(t, Snapshot(250, 1000, 2048, 1000, 4096, 90, 4, emptyList()))
        val title = notif.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
        val text = notif.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertTrue(title, title.contains("a.iso") && title.contains("25%"))
        assertTrue(text, text.contains("2"))          // السرعة
        assertTrue(text, text.contains("/"))          // المنجز / الإجمالي
        assertEquals(25, notif.extras.getInt(Notification.EXTRA_PROGRESS))
        assertEquals(100, notif.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertEquals(2, notif.actions.size)           // إيقاف وإلغاء
        assertEquals(Notifier.GROUP_ACTIVE, notif.group)
    }

    @Test fun soundAndVibrationChannels() {
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
        Notifier(ctx).ensureChannels(sound = false, vibrate = true)
        val ch = nm.notificationChannels.single { it.id.startsWith("done") }
        assertEquals("done_qv", ch.id)
        assertTrue(ch.shouldVibrate())
        assertEquals(null, ch.sound)
        Notifier(ctx).ensureChannels(sound = true, vibrate = false)
        assertEquals(listOf("done_sn"), nm.notificationChannels.filter { it.id.startsWith("done") }.map { it.id })
    }
}
