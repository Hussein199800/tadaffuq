package com.tadaffuq.app

import com.tadaffuq.app.ui.downloads.DownloadSort
import com.tadaffuq.data.TaskEntity
import com.tadaffuq.data.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/** 1.4.0: ترتيب قائمة التنزيلات؛ التعادل بوقت الإضافة ثم المعرّف، والحجم المجهول آخراً. */
class DownloadSortTest {
    private fun t(id: Long, name: String, size: Long, done: Long = 0, at: Long = id, prio: Int = 0) =
        TaskEntity(id = id, url = "https://x/$name", fileName = name, dirPath = "/d", status = TaskStatus.PAUSED, totalBytes = size, downloadedBytes = done, createdAt = at, priority = prio)
    private val list = listOf(t(1, "b.mp4", 300, 30), t(2, "a.zip", -1), t(3, "C.iso", 900, 900), t(4, "d.bin", 300, 150, at = 1))

    private fun ids(s: DownloadSort) = s.apply(list).map { it.id }

    @Test fun orders() {
        assertEquals(listOf(3L, 2L, 4L, 1L), ids(DownloadSort.NEWEST))   // 4 و1 بالوقت نفسه: الأعلى معرّفاً أولاً
        assertEquals(listOf(1L, 4L, 2L, 3L), ids(DownloadSort.OLDEST))
        assertEquals(listOf(2L, 1L, 3L, 4L), ids(DownloadSort.NAME))
        assertEquals(listOf(3L, 4L, 1L, 2L), ids(DownloadSort.LARGEST))  // المجهول آخراً
        assertEquals(listOf(4L, 1L, 3L, 2L), ids(DownloadSort.SMALLEST))
        assertEquals(listOf(1L, 4L, 3L, 2L), ids(DownloadSort.PROGRESS))
        assertEquals(DownloadSort.NEWEST, DownloadSort.of("bogus"))
    }
}
