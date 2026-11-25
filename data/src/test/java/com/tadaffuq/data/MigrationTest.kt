package com.tadaffuq.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** كل ترحيل يحفظ بيانات المستخدم: يُنشأ إصدار قديم بمهمة حقيقية ثم يُرحَّل ويُتحقق منها. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test fun migrateAllKeepsTasks() {
        helper.createDatabase("m.db", 1).apply {
            execSQL("INSERT INTO tasks(url, fileName, dirPath, status, totalBytes, downloadedBytes, acceptRanges, connections, speedLimitBps, priority, retries, networkRetries, serverRetries, averageBps, maxBps, createdAt, wifiOnly) VALUES ('https://x/a.zip','a.zip','/d','PAUSED',100,40,1,0,0,0,0,0,0,0,0,1,0)")
            execSQL("INSERT INTO segments(taskId, idx, start, endInclusive, downloaded) VALUES (1,0,0,99,40)")
            close()
        }
        val db = helper.runMigrationsAndValidate("m.db", AppDatabase.VERSION, true)
        db.query("SELECT fileName, downloadedBytes, mirrors FROM tasks").use { c ->
            c.moveToFirst()
            assertEquals("a.zip", c.getString(0)); assertEquals(40, c.getLong(1)); assertEquals(true, c.isNull(2))
        }
        db.query("SELECT COUNT(*) FROM segments").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
    }
}
