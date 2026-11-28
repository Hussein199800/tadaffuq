package com.tadaffuq.app.ui.downloads

import com.tadaffuq.app.R
import com.tadaffuq.data.TaskEntity

/**
 * ترتيب عرض قائمة التنزيلات (لا يغيّر أولوية التنفيذ). التعادل يُكسر بوقت الإضافة ثم المعرّف،
 * والحجم المجهول في آخر القائمة دائماً.
 */
enum class DownloadSort(val label: Int) {
    NEWEST(R.string.sort_newest), OLDEST(R.string.sort_oldest), NAME(R.string.sort_name_az),
    LARGEST(R.string.sort_largest), SMALLEST(R.string.sort_smallest), PROGRESS(R.string.sort_progress), PRIORITY(R.string.sort_priority);

    fun apply(list: List<TaskEntity>): List<TaskEntity> {
        val tie = compareByDescending<TaskEntity> { it.createdAt }.thenByDescending { it.id }
        val known = compareBy<TaskEntity> { if (it.totalBytes > 0) 0 else 1 }
        val c: Comparator<TaskEntity> = when (this) {
            NEWEST -> tie
            OLDEST -> compareBy<TaskEntity> { it.createdAt }.thenBy { it.id }
            NAME -> compareBy<TaskEntity, String>(String.CASE_INSENSITIVE_ORDER) { it.fileName }.then(tie)
            LARGEST -> known.thenByDescending { it.totalBytes }.then(tie)
            SMALLEST -> known.thenBy { it.totalBytes }.then(tie)
            // الأقل إنجازاً أولاً: ما يحتاج انتباهاً في الأعلى
            PROGRESS -> known.thenBy { if (it.totalBytes > 0) it.downloadedBytes.toDouble() / it.totalBytes else 0.0 }.then(tie)
            PRIORITY -> compareByDescending<TaskEntity> { it.priority }.then(tie)
        }
        return list.sortedWith(c)
    }

    companion object { fun of(name: String) = entries.firstOrNull { it.name == name } ?: NEWEST }
}
