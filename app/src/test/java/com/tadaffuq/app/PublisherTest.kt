package com.tadaffuq.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tadaffuq.app.download.PublishTarget
import com.tadaffuq.app.download.Publisher
import com.tadaffuq.data.AppDatabase
import com.tadaffuq.data.TaskEntity
import com.tadaffuq.data.TaskStatus
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
