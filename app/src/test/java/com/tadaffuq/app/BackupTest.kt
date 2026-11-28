package com.tadaffuq.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tadaffuq.app.core.Backup
import com.tadaffuq.data.AppDatabase
import com.tadaffuq.data.SavedLinkEntity
import com.tadaffuq.data.Settings
import com.tadaffuq.data.TaskEntity
import com.tadaffuq.data.TaskStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class BackupTest {
    @Test fun exportImportRoundTripWithoutSecrets() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val a = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        a.tasks().insert(TaskEntity(url = "https://x/a.zip", fileName = "a.zip", dirPath = "/d", status = TaskStatus.COMPLETED, totalBytes = 10, cookies = "session=SECRET", referer = "https://private"))
        val real = java.io.File.createTempFile("have", ".bin").apply { deleteOnExit(); writeBytes(ByteArray(10)) }
        a.tasks().insert(TaskEntity(url = "https://x/c.bin", fileName = "c.bin", dirPath = real.parent, status = TaskStatus.COMPLETED, totalBytes = 10, filePath = real.path))
        a.tasks().insert(TaskEntity(url = "https://x/b.iso", fileName = "b.iso", dirPath = "/d", status = TaskStatus.RUNNING, totalBytes = 99, downloadedBytes = 50))
        a.links().insert(listOf(SavedLinkEntity(url = "https://x/c")))
        val json = Backup.export(a, Settings(maxConnections = 16, rulesJson = "[{\"name\":\"r\"}]"))
        assertFalse("cookies never exported", json.contains("SECRET"))
        assertFalse(json.contains("https://private"))

        val b = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        val r = Backup.import(b, json, Settings())
        assertEquals(3, r.tasks)
        assertEquals(16, r.settings!!.maxConnections)
        val imported = b.tasks().all().associateBy { it.fileName }
        assertEquals("file present on this device", TaskStatus.COMPLETED, imported["c.bin"]!!.status)
        // 1.4.0 (مراجعة Codex): مكتمل بلا ملف على الجهاز لا يُستورد «مكتملاً»
        assertEquals(TaskStatus.PAUSED, imported["a.zip"]!!.status)
        assertEquals("IMPORT_MISSING", imported["a.zip"]!!.errorCode)
        assertEquals("unfinished imported paused from zero", TaskStatus.PAUSED, imported["b.iso"]!!.status)
        assertEquals(0L, imported["b.iso"]!!.downloadedBytes)
        assertEquals(1, b.links().all().size)
        // استيراد ثانٍ لا يكرر
        assertEquals(0, Backup.import(b, json, Settings()).tasks)
    }
}
