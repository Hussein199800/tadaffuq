package com.tadaffuq.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tadaffuq.app.download.PublishTarget
import com.tadaffuq.app.download.Publisher
import com.tadaffuq.data.AppDatabase
import com.tadaffuq.data.TaskEntity
import com.tadaffuq.data.TaskStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.random.Random

/** rc3 P0: نقل الملف إلى SAF/MediaStore قابل للاستعادة عند موت العملية في كل مرحلة. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PublisherTest {
    @get:Rule val tmp = TemporaryFolder()
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private val data = Random(21).nextBytes(150_000)

    /** وجهة وهمية: مجلد عادي، معرّف المستند = مساره. */
    private class DirTarget(val dir: File, val failWrite: Boolean = false) : PublishTarget {
        override fun create(name: String, mime: String): String {
            var n = name; var i = 1
            while (File(dir, n).exists()) n = "${name.substringBeforeLast('.')} (${i++}).${name.substringAfterLast('.')}"
            return File(dir, n).apply { createNewFile() }.path
        }
        override fun write(id: String, src: File) {
            if (failWrite) { File(id).writeBytes(src.readBytes().copyOf(10)); error("disk") }
            File(id).writeBytes(src.readBytes())
        }
        override fun length(id: String) = File(id).length()
        override fun delete(id: String) = File(id).delete()
    }

    /** موت العملية: خطأ لا يلتقطه منطق التراجع (كما لو قُتلت العملية). */
    private class Death : Error()

    @Before fun setUp() { db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build() }
    @After fun tearDown() { db.close() }

    private fun setup(): Triple<TaskEntity, File, DirTarget> = runBlocking {
        val local = File(tmp.newFolder(), "a.bin").apply { writeBytes(data) }
        val id = db.tasks().insertAll(listOf(TaskEntity(url = "https://x/a.bin", fileName = "a.bin", dirPath = local.parent, status = TaskStatus.COMPLETED,
            filePath = local.path, totalBytes = data.size.toLong(), treeUri = "content://tree"))).single()
        Triple(db.tasks().get(id)!!, local, DirTarget(tmp.newFolder()))
    }

    @Test fun normalMove() = runBlocking {
        val (t, local, target) = setup()
        val done = Publisher(db.tasks()).publish(t, local, target)
        assertFalse(local.exists())
        assertArrayEquals(data, File(done.filePath!!).readBytes())
        assertNull(done.publishUri); assertNull(done.publishLocal)
    }

    @Test fun writeFailureKeepsOriginal() = runBlocking {
        val (t, local, _) = setup()
        val target = DirTarget(tmp.newFolder("bad"), failWrite = true)
        try { Publisher(db.tasks()).publish(t, local, target); fail() } catch (e: Publisher.PublishFailed) { }
        val after = db.tasks().get(t.id)!!
        assertEquals(local.path, after.filePath)
        assertArrayEquals(data, local.readBytes())
        assertTrue("partial copy removed", target.dir.listFiles()!!.isEmpty())
        assertNull(after.publishUri)
    }

    /** الموت بعد إنشاء المستند أو بعد النسخ وقبل التثبيت: الاستعادة تحذف النسخة والأصل يبقى، والنشر من جديد بلا تكرار. */
    @Test fun deathBeforeCommitRollsBack() = runBlocking {
        for (stage in listOf(Publisher.Stage.CREATED, Publisher.Stage.WRITTEN)) {
            val (t, local, target) = setup()
            try { Publisher(db.tasks()) { if (it == stage) throw Death() }.publish(t, local, target); fail() } catch (e: Death) { }
            val dead = db.tasks().get(t.id)!!
            assertEquals(local.path, dead.filePath)
            assertTrue(Publisher(db.tasks()).recover(dead, target))
            assertTrue("$stage: orphan copy deleted", target.dir.listFiles()!!.isEmpty())
            assertArrayEquals(data, local.readBytes())
            val again = Publisher(db.tasks()).publish(db.tasks().get(t.id)!!, local, target)
            assertEquals("a.bin", File(again.filePath!!).name) // لا «a (1).bin»
        }
    }

    /** الموت بعد التثبيت وقبل حذف الأصل: الاستعادة تنظف الأصل، والسجل يشير إلى الوجهة. */
    @Test fun deathAfterCommitCleansOriginal() = runBlocking {
        val (t, local, target) = setup()
        try { Publisher(db.tasks()) { if (it == Publisher.Stage.COMMITTED) throw Death() }.publish(t, local, target); fail() } catch (e: Death) { }
        val dead = db.tasks().get(t.id)!!
        assertEquals(File(target.dir, "a.bin").path, dead.filePath)
        assertTrue(local.exists())
        assertFalse(Publisher(db.tasks()).recover(dead, target))
        assertFalse(local.exists())
        assertArrayEquals(data, File(dead.filePath!!).readBytes())
        assertNull(db.tasks().get(t.id)!!.publishLocal)
    }
}

/** rc3 P1: عداد الاستهلاك لا ينخفض عند نقل المعلّق إلى القاعدة ولا يضيع عند فشل الكتابة. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class UsageMeterTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test fun countersNeverDropAcrossFlush() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        val cond = com.tadaffuq.app.download.Conditions(ctx).apply { set(com.tadaffuq.app.download.DeviceState(connected = true, net = com.tadaffuq.data.NetKind.CELLULAR, metered = true)) }
        val m = com.tadaffuq.app.download.UsageMeter(db.usage(), cond)
        m.add(1000)
        assertEquals(1000, m.cellularToday)
        // قراءات متكررة أثناء نقل المعلّق إلى القاعدة: لا تنخفض أبداً تحت 1000
        val reader = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).async {
            var min = Long.MAX_VALUE
            repeat(2000) { min = minOf(min, m.cellularToday) }
            min
        }
        m.flush()
        assertTrue(reader.await() >= 1000)
        assertEquals(1000, m.cellularToday)
        m.flush() // لا كتابة مكررة
        assertEquals(1000, m.cellularToday)
        db.close()
    }
}

/** RC4: سحب إذن مجلد SAF أو نسخة منشورة لا تطابق البصمة: لا حذف للأصل ولا سجل مضلل. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PublishSafetyTest {
    @get:Rule val tmp = TemporaryFolder()
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val data = Random(31).nextBytes(80_000)

    private fun task(db: AppDatabase, local: File, hash: String? = null) = runBlocking {
        db.tasks().get(db.tasks().insertAll(listOf(TaskEntity(url = "https://x/a.bin", fileName = local.name, dirPath = local.parent, status = TaskStatus.COMPLETED,
            filePath = local.path, totalBytes = data.size.toLong(), expectedHash = hash))).single())!!
    }

    @Test fun revokedFolderKeepsOriginal() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        val local = File(tmp.newFolder(), "a.bin").apply { writeBytes(data) }
        val t = task(db, local)
        val revoked = object : PublishTarget {
            override fun create(name: String, mime: String): String = throw SecurityException("Permission Denial: no persistable grant")
            override fun write(id: String, src: File) = Unit
            override fun length(id: String) = 0L
            override fun delete(id: String) = true
        }
        try { Publisher(db.tasks()).publish(t, local, revoked); fail() } catch (e: Publisher.PublishFailed) { assertTrue(e.cause is SecurityException) }
        val after = db.tasks().get(t.id)!!
        assertEquals(local.path, after.filePath); assertNull(after.publishUri); assertArrayEquals(data, local.readBytes())
        db.close()
    }

    /** النسخة المنشورة تُقرأ وتُطابق بالبصمة قبل حذف الأصل؛ نسخة تالفة بالحجم نفسه تُرفض. */
    @Test fun corruptedPublishedCopyIsRejected() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        val local = File(tmp.newFolder(), "b.bin").apply { writeBytes(data) }
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        val t = task(db, local, sha)
        val dir = tmp.newFolder()
        val corrupting = object : PublishTarget {
            override fun create(name: String, mime: String) = File(dir, name).apply { createNewFile() }.path
            override fun write(id: String, src: File) { File(id).writeBytes(src.readBytes().also { it[100] = (it[100] + 1).toByte() }) }
            override fun length(id: String) = File(id).length()
            override fun delete(id: String) = File(id).delete()
            override fun open(id: String) = File(id).inputStream()
        }
        try { Publisher(db.tasks()).publish(t, local, corrupting); fail("corrupted copy accepted") } catch (e: Publisher.PublishFailed) { }
        assertTrue(local.exists()); assertTrue(dir.listFiles()!!.isEmpty())
        assertEquals(local.path, db.tasks().get(t.id)!!.filePath)
        db.close()
    }

    /** الوجهة تعرف مسارها الحقيقي (MediaStore): السجل يشير إلى المسار والاسم الفعلي بعد إعادة التسمية التلقائية. */
    @Test fun resolvedPathIsRecorded() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        val local = File(tmp.newFolder(), "c.bin").apply { writeBytes(data) }
        val t = task(db, local)
        val dir = tmp.newFolder()
        val ms = object : PublishTarget {
            override fun create(name: String, mime: String) = "content://media/external/downloads/42"
            override fun write(id: String, src: File) { File(dir, "c (1).bin").writeBytes(src.readBytes()) }
            override fun length(id: String) = File(dir, "c (1).bin").length()
            override fun delete(id: String) = File(dir, "c (1).bin").delete()
            override fun resolvedPath(id: String) = File(dir, "c (1).bin").path
        }
        val done = Publisher(db.tasks()).publish(t, local, ms)
        assertEquals(File(dir, "c (1).bin").path, done.filePath)
        assertEquals("c (1).bin", done.fileName)
        assertFalse(local.exists())
        assertNull(done.publishUri)
        db.close()
    }
}
