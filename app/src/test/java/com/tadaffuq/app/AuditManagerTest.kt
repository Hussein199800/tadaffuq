package com.tadaffuq.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.tadaffuq.app.download.Conditions
import com.tadaffuq.app.download.DeviceState
import com.tadaffuq.app.download.DownloadManager
import com.tadaffuq.app.download.NewTask
import com.tadaffuq.app.download.Notifier
import com.tadaffuq.app.download.StorageDirs
import com.tadaffuq.data.AppDatabase
import com.tadaffuq.data.NetKind
import com.tadaffuq.data.SettingsRepository
import com.tadaffuq.data.TaskEntity
import com.tadaffuq.data.TaskStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/** اختبارات انحدار لعيوب P0 على مستوى المدير (وثيقة التدقيق)؛ كُتبت قبل الإصلاح. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AuditManagerTest {
    @get:Rule val tmp = TemporaryFolder()
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var server: MockWebServer
    /** محتوى مختلف لكل مسار: /a و/b ملفان مختلفان بالاسم نفسه. */
    private val contents = mapOf("a" to Random(11).nextBytes(2 * 1024 * 1024 + 3), "b" to Random(12).nextBytes(2 * 1024 * 1024 + 5))
    @Volatile private var throttle = 0L
    private val requests = AtomicInteger()
    private val cookieHosts = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val m = mutableListOf<DownloadManager>()

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.incrementAndGet()
                request.getHeader("Cookie")?.let { cookieHosts[request.path!!] = it }
                val key = request.path!!.trimStart('/').substringBefore('/')
                val content = contents[key] ?: contents.getValue("a")
                val range = request.getHeader("Range")?.removePrefix("bytes=")
                val start = range?.substringBefore('-')?.toLong() ?: 0
                val end = range?.substringAfter('-')?.toLongOrNull() ?: (content.size - 1L)
                val slice = content.copyOfRange(start.toInt(), minOf(end, content.size - 1L).toInt() + 1)
                return MockResponse().setResponseCode(if (range != null) 206 else 200)
                    .setHeader("ETag", "\"$key\"")
                    .apply { if (range != null) setHeader("Content-Range", "bytes $start-${start + slice.size - 1}/${content.size}") }
                    .setBody(Buffer().write(slice))
                    .apply { if (throttle > 0) throttleBody(64 * 1024, throttle, TimeUnit.MILLISECONDS) }
            }
        }
        server.start()
    }

    @After fun tearDown() {
        m.forEach { it.kill() }
        runBlocking { settings.update { com.tadaffuq.data.Settings() } }
        runCatching { server.shutdown() }; db.close()
    }

    private val settings by lazy { SettingsRepository(ctx) }
    private lateinit var conditions: Conditions

    private fun manager(net: NetKind = NetKind.WIFI): DownloadManager {
        conditions = Conditions(ctx)
        val dm = DownloadManager(ctx, db, settings, com.tadaffuq.app.download.ClientProvider(com.tadaffuq.app.core.SecretStore(ctx)), com.tadaffuq.app.download.AppCredentials(ctx, com.tadaffuq.app.core.SecretStore(ctx)), conditions, Notifier(ctx))
        dm.start()
        conditions.set(DeviceState(connected = true, net = net, metered = net == NetKind.CELLULAR, batteryPercent = 80))
        dm.wake()
        m += dm
        return dm
    }

    private suspend fun waitFor(id: Long, vararg status: TaskStatus, timeoutMs: Long = 60_000): TaskEntity {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val t = db.tasks().get(id)
            if (t?.status in status) return t!!
            delay(50)
        }
        throw AssertionError("timeout waiting ${status.toList()}, task=${db.tasks().get(id)}")
    }

    /** P0-02: مهمتان متزامنتان بالاسم نفسه لا تشتركان في ملف جزئي ولا في الاسم النهائي. */
    @Test fun p0_02_sameNameConcurrentTasksStaySeparate() = runBlocking {
        throttle = 150
        val dir = tmp.newFolder("same")
        val dm = manager()
        val ids = dm.enqueue(listOf(
            NewTask(server.url("/a/file.bin").toString(), "file.bin", dir.path, connections = 2),
            NewTask(server.url("/b/file.bin").toString(), "file.bin", dir.path, connections = 2),
        ))
        val names = ids.map { db.tasks().get(it)!!.fileName }
        assertNotEquals("final name reserved at enqueue", names[0], names[1])
        val done = ids.map { waitFor(it, TaskStatus.COMPLETED, TaskStatus.FAILED) }
        done.forEach { assertEquals(it.errorCode, TaskStatus.COMPLETED, it.status) }
        assertArrayEquals(contents["a"], File(done[0].filePath!!).readBytes())
        assertArrayEquals(contents["b"], File(done[1].filePath!!).readBytes())
    }

    /** P0-02: نفس الرابط مرتين، ثم حذف إحداهما أثناء العمل: الأخرى تكتمل سليمة. */
    @Test fun p0_02_deletingOneOfTwoDoesNotHurtTheOther() = runBlocking {
        throttle = 300
        val dir = tmp.newFolder("del")
        val dm = manager()
        val url = server.url("/a/same.bin").toString()
        val ids = dm.enqueue(listOf(NewTask(url, "same.bin", dir.path, connections = 2), NewTask(url, "same.bin", dir.path, connections = 2)))
        ids.forEach { waitFor(it, TaskStatus.RUNNING) }
        withTimeout(30_000) { while ((db.tasks().get(ids[1])?.downloadedBytes ?: 0) < 200_000) delay(50) }
        dm.delete(listOf(ids[0]), withFiles = true)
        throttle = 0
        val t = waitFor(ids[1], TaskStatus.COMPLETED, TaskStatus.FAILED)
        assertEquals(t.errorCode, TaskStatus.COMPLETED, t.status)
        assertArrayEquals(contents["a"], File(t.filePath!!).readBytes())
    }

    /** P0-02: ملف جزئي قديم (الاسم + ‎.tdq) يُرحَّل ويُستأنف إن لم يكن ملتبساً. */
    @Test fun p0_02_legacyPartIsMigrated() = runBlocking {
        val dir = tmp.newFolder("legacy")
        val data = contents.getValue("a")
        val half = data.size / 2L
        File(dir, "old.bin" + StorageDirs.PART_SUFFIX).writeBytes(data.copyOf(data.size).also { java.util.Arrays.fill(it, half.toInt(), it.size, 0) })
        val id = db.tasks().insertAll(listOf(TaskEntity(url = server.url("/a/old.bin").toString(), fileName = "old.bin", dirPath = dir.path, status = TaskStatus.PAUSED,
            totalBytes = data.size.toLong(), acceptRanges = true, etag = "\"a\"", downloadedBytes = half))).single()
        db.tasks().saveProgress(id, listOf(com.tadaffuq.data.SegmentEntity(id, 0, 0, data.size - 1L, half)), half, data.size.toLong(), "\"a\"", null, true, server.url("/a/old.bin").toString())
        val dm = manager()
        dm.resume(listOf(id))
        val t = waitFor(id, TaskStatus.COMPLETED, TaskStatus.FAILED)
        assertEquals(TaskStatus.COMPLETED, t.status)
        assertArrayEquals(data, File(t.filePath!!).readBytes())
        assertFalse(File(dir, "old.bin" + StorageDirs.PART_SUFFIX).exists())
    }

    /** P0-05: فشل البصمة ليس «مكتملاً»، والملف لا يُنشر باسمه النهائي. */
    @Test fun p0_05_checksumFailureIsNotCompleted() = runBlocking {
        val dir = tmp.newFolder("hash")
        val dm = manager()
        val id = dm.enqueue(listOf(NewTask(server.url("/a/h.bin").toString(), "h.bin", dir.path, expectedHash = "0".repeat(64)))).single()
        val t = waitFor(id, TaskStatus.COMPLETED, TaskStatus.FAILED)
        assertEquals(TaskStatus.FAILED, t.status)
        assertEquals("CHECKSUM", t.errorCode)
        assertEquals(false, t.hashOk)
        assertFalse("not published", File(dir, "h.bin").exists())
    }

    /** P0-05: مرجع بصمة غير صالح لا يمر بصمت. */
    @Test fun p0_05_invalidReferenceIsReported() = runBlocking {
        val dir = tmp.newFolder("bad")
        val dm = manager()
        val id = dm.enqueue(listOf(NewTask(server.url("/a/r.bin").toString(), "r.bin", dir.path, expectedHash = "not-a-hash"))).single()
        val t = waitFor(id, TaskStatus.COMPLETED, TaskStatus.FAILED)
        assertEquals(TaskStatus.FAILED, t.status)
        assertEquals("HASH_INVALID", t.errorCode)
    }

    /** P0-05: موت العملية بعد إعادة التسمية وقبل تسجيل الاكتمال → يُستعاد مكتملاً دون إعادة تنزيل. */
    @Test fun p0_05_deathAfterRenameRecovers() = runBlocking {
        val dir = tmp.newFolder("fin")
        val data = contents.getValue("a")
        val dest = File(dir, "f.bin").apply { writeBytes(data) }
        val id = db.tasks().insertAll(listOf(TaskEntity(url = server.url("/a/f.bin").toString(), fileName = "f.bin", dirPath = dir.path, status = TaskStatus.VERIFYING,
            totalBytes = data.size.toLong(), downloadedBytes = data.size.toLong(), filePath = dest.path))).single()
        manager()
        val t = waitFor(id, TaskStatus.COMPLETED, TaskStatus.FAILED, TaskStatus.RUNNING, timeoutMs = 10_000)
        assertEquals(TaskStatus.COMPLETED, t.status)
        assertEquals(0, requests.get())
        assertEquals(dest.path, t.filePath)
        assertFalse(File(dir, "f (1).bin").exists())
    }

    private fun sha256(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** rc3: موت قبل rename بعد تغيير الاسم في Room (تعارض) مع بصمة متوقعة: الجزئي يُعثر عليه بمعرّفه ويكتمل مع البصمة. */
    @Test fun rc3_deathBeforeRenameAfterNameChange() = runBlocking {
        val dir = tmp.newFolder("ren")
        val data = contents.getValue("a")
        File(dir, "f.bin").writeText("someone else's file")
        val id = db.tasks().insertAll(listOf(TaskEntity(url = server.url("/a/f.bin").toString(), fileName = "f (1).bin", dirPath = dir.path, status = TaskStatus.VERIFYING,
            totalBytes = data.size.toLong(), acceptRanges = true, etag = "\"a\"", downloadedBytes = data.size.toLong(), expectedHash = sha256(data),
            filePath = File(dir, "f (1).bin").path))).single()
        StorageDirs.partFile(dir.path, id).writeBytes(data)
        db.tasks().saveProgress(id, listOf(com.tadaffuq.data.SegmentEntity(id, 0, 0, data.size - 1L, data.size.toLong())), data.size.toLong(), data.size.toLong(), "\"a\"", null, true, server.url("/a/f.bin").toString())
        manager()
        val t = waitFor(id, TaskStatus.COMPLETED, TaskStatus.FAILED)
        assertEquals(t.errorCode, TaskStatus.COMPLETED, t.status)
        assertEquals(true, t.hashOk)
        assertArrayEquals(data, File(t.filePath!!).readBytes())
        assertEquals("someone else's file", File(dir, "f.bin").readText())
        assertFalse(StorageDirs.partFile(dir.path, id).exists())
    }

    /** rc3: موت بعد rename: نتيجة البصمة المحفوظة قبل rename تبقى بعد الاستعادة. */
    @Test fun rc3_deathAfterRenameKeepsHashResult() = runBlocking {
        val dir = tmp.newFolder("fin2")
        val data = contents.getValue("a")
        val dest = File(dir, "g.bin").apply { writeBytes(data) }
        val id = db.tasks().insertAll(listOf(TaskEntity(url = server.url("/a/g.bin").toString(), fileName = "g.bin", dirPath = dir.path, status = TaskStatus.VERIFYING,
            totalBytes = data.size.toLong(), expectedHash = sha256(data), hashOk = true, actualHash = sha256(data), filePath = dest.path))).single()
        manager()
        val t = waitFor(id, TaskStatus.COMPLETED, TaskStatus.FAILED, timeoutMs = 10_000)
        assertEquals(TaskStatus.COMPLETED, t.status)
        assertEquals(true, t.hashOk)
        assertEquals(sha256(data), t.actualHash)
        assertEquals(0, requests.get())
    }

    /** P0-08: «Wi-Fi فقط» لكل مهمة يُطبَّق على المهمة العاملة عند التحول إلى بيانات الجوال. */
    @Test fun p0_08_perTaskWifiOnlyStopsOnCellular() = runBlocking {
        throttle = 400
        val dir = tmp.newFolder("wifi")
        val dm = manager()
        val ids = dm.enqueue(listOf(
            NewTask(server.url("/a/w.bin").toString(), "w.bin", dir.path, connections = 2, wifiOnly = true),
            NewTask(server.url("/b/x.bin").toString(), "x.bin", dir.path, connections = 2),
        ))
        ids.forEach { waitFor(it, TaskStatus.RUNNING) }
        conditions.set(DeviceState(connected = true, net = NetKind.CELLULAR, metered = true, batteryPercent = 80))
        val w = waitFor(ids[0], TaskStatus.WAITING_NETWORK, TaskStatus.PAUSED, TaskStatus.QUEUED, timeoutMs = 5_000)
        assertTrue(w.downloadedBytes < contents.getValue("a").size)
        assertEquals("the other keeps running", TaskStatus.RUNNING, db.tasks().get(ids[1])!!.status)
    }

    /** P0-09: بلوغ الحد اليومي أثناء تنزيل مستمر يوقفه فوراً تقريباً (لا بعد 30 ثانية). */
    @Test fun p0_09_dailyLimitStopsRunningDownload() = runBlocking {
        throttle = 250
        settings.update { it.copy(dailyLimitBytes = 600_000) }
        val dir = tmp.newFolder("limit")
        val dm = manager(NetKind.CELLULAR)
        val id = dm.enqueue(listOf(NewTask(server.url("/a/l.bin").toString(), "l.bin", dir.path, connections = 2))).single()
        waitFor(id, TaskStatus.RUNNING)
        val t = waitFor(id, TaskStatus.WAITING_NETWORK, TaskStatus.PAUSED, TaskStatus.COMPLETED, TaskStatus.FAILED, timeoutMs = 20_000)
        assertNotEquals(TaskStatus.COMPLETED, t.status)
        assertEquals(com.tadaffuq.app.download.Hold.DAILY_LIMIT, dm.hold.value)
        assertTrue("overshoot ${t.downloadedBytes}", t.downloadedBytes < 600_000 + 400_000)
    }

    /** rc3 P1: بيانات التورنت (تنزيل + رفع) تدخل حدود الجوال وتوقف الطابور. */
    @Test fun rc3_torrentBytesCountTowardLimit() = runBlocking {
        settings.update { it.copy(dailyLimitBytes = 500_000) }
        val dm = manager(NetKind.CELLULAR)
        delay(300)
        assertEquals(null, dm.hold.value)
        dm.recordExternalBytes(600_000)
        withTimeout(5_000) { while (dm.hold.value == null) delay(50) }
        assertEquals(com.tadaffuq.app.download.Hold.DAILY_LIMIT, dm.hold.value)
    }

    /** rc3 P1: التنظيف يحدد اليتيم بمعرّفات المهام، ولا يلمس جزئياً قابلاً للاستئناف ولا ملف المستخدم. */
    @Test fun rc3_orphanCleanupIsSafe() = runBlocking {
        val dir = tmp.newFolder("orph")
        val dm = manager()
        val id = db.tasks().insertAll(listOf(TaskEntity(url = "https://x/a.bin", fileName = "a.bin", dirPath = dir.path, status = TaskStatus.PAUSED))).single()
        val live = StorageDirs.partFile(dir.path, id).apply { writeText("resumable") }
        val orphan = StorageDirs.partFile(dir.path, 9999).apply { writeText("orphan") }
        val user = File(dir, "notes.txt").apply { writeText("mine") }
        val found = dm.orphanParts()
        assertEquals(listOf(orphan.absolutePath), found.map { it.absolutePath })
        // 1.4.0 (مراجعة Codex): قائمة قديمة أو خاطئة تضم جزءاً قابلاً للاستئناف لا تحذفه (يُعاد الفحص لحظة الحذف)
        assertEquals(1, dm.cleanOrphans(found + live))
        assertTrue(live.exists()); assertTrue(user.exists()); assertFalse(orphan.exists())
    }

    /** P0-10: الكوكيز لا تُرسل لمضيف آخر غير الذي التُقطت منه (روابط متعددة/مرايا). */
    @Test fun p0_10_cookiesAreScopedAndEncrypted() = runBlocking {
        val dir = tmp.newFolder("ck")
        val dm = manager()
        val id = dm.enqueue(listOf(NewTask(server.url("/a/c.bin").toString(), "c.bin", dir.path, cookies = "sid=SECRET"))).single()
        assertFalse("stored encrypted", db.tasks().get(id)!!.cookies.orEmpty().contains("SECRET"))
        val t = waitFor(id, TaskStatus.COMPLETED, TaskStatus.FAILED)
        assertEquals(TaskStatus.COMPLETED, t.status)
        assertTrue("origin still gets its cookie", cookieHosts.values.all { it == "sid=SECRET" } && cookieHosts.isNotEmpty())
    }
}

/** RC4: حد السرعة العام يشمل التورنت (ما تتركه التنزيلات العادية، بحد أدنى موثّق 32 ك.ب/ث). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class TorrentShareTest {
    @Test fun torrentGetsRemainderOfGlobalLimit() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        val settings = SettingsRepository(ctx)
        val dm = DownloadManager(ctx, db, settings, com.tadaffuq.app.download.ClientProvider(com.tadaffuq.app.core.SecretStore(ctx)), com.tadaffuq.app.download.AppCredentials(ctx, com.tadaffuq.app.core.SecretStore(ctx)), Conditions(ctx), Notifier(ctx))
        val tm = com.tadaffuq.app.download.TorrentManager(ctx, db, settings, dm, Notifier(ctx))
        val s = com.tadaffuq.data.Settings(torrentDownLimit = 0)
        dm.globalLimiter.bytesPerSecond = 0
        assertEquals(0, tm.shareOfGlobal(s, 0))                       // بلا حد عام: حد التورنت الخاص (لا شيء)
        dm.globalLimiter.bytesPerSecond = 200_000
        assertEquals(50_000, tm.shareOfGlobal(s, 150_000))            // الباقي من الحد
        assertEquals(32 * 1024, tm.shareOfGlobal(s, 199_000))         // حد أدنى كي لا يتجمد
        assertEquals(20_000, tm.shareOfGlobal(s.copy(torrentDownLimit = 20_000), 0)) // الأصغر يغلب
        db.close()
    }

    /** 1.4.0 (مراجعة Codex): «إيقاف الكل» يوقف مشاركة المكتمل ويُحفظ، فلا يعود الرفع بعد إعادة التشغيل. */
    @Test fun pauseAllStopsSeedingPersistently() = kotlinx.coroutines.runBlocking {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        val settings = SettingsRepository(ctx)
        val dm = DownloadManager(ctx, db, settings, com.tadaffuq.app.download.ClientProvider(com.tadaffuq.app.core.SecretStore(ctx)), com.tadaffuq.app.download.AppCredentials(ctx, com.tadaffuq.app.core.SecretStore(ctx)), Conditions(ctx), Notifier(ctx))
        val tm = com.tadaffuq.app.download.TorrentManager(ctx, db, settings, dm, Notifier(ctx))
        db.torrents().upsert(com.tadaffuq.data.TorrentEntity("abc", "x", "/d", ByteArray(0), "0", 1, 1, com.tadaffuq.data.TorrentRunState.COMPLETED, seedAfter = true))
        tm.pauseAll()
        assertEquals(com.tadaffuq.data.TorrentRunState.PAUSED, db.torrents().get("abc")!!.state)
        db.close()
    }
}
