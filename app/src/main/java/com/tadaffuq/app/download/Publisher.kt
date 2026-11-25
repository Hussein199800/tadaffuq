package com.tadaffuq.app.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.tadaffuq.data.TaskDao
import com.tadaffuq.data.TaskEntity
import java.io.File

/** وجهة نشر خارج المسار المحلي (مجلد SAF أو MediaStore). المعرّف نص رابط content://. */
interface PublishTarget {
    /** ينشئ مستنداً فارغاً باسم غير مستعمل ويعيد معرّفه. */
    fun create(name: String, mime: String): String
    fun write(id: String, src: File)
    fun length(id: String): Long
    /** يجعل المستند مرئياً (IS_PENDING=0 في MediaStore). */
    fun finish(id: String) {}
    fun delete(id: String): Boolean
}

/**
 * نقل ملف مكتمل إلى وجهة خارجية بخطوات قابلة للاستعادة، مسجّلة في Room قبل كل خطوة خطرة:
 * 1) إنشاء مستند الوجهة وتسجيله (publishUri + publishLocal).
 * 2) النسخ ثم التحقق من الحجم ثم إظهاره.
 * 3) تثبيت filePath = الوجهة.
 * 4) حذف الأصل والتحقق من نتيجته، ثم مسح السجل.
 * موت العملية بين أي خطوتين يُعالج في [recover]: نسخة غير مثبتة تُحذف ويبقى الأصل؛
 * ونسخة مثبتة يُنظَّف أصلها. لا حالة يضيع فيها الملف أو يشير السجل إلى ملف غير موجود.
 */
class Publisher(private val dao: TaskDao, private val onStage: (Stage) -> Unit = {}) {
    enum class Stage { CREATED, WRITTEN, COMMITTED }

    class PublishFailed(cause: Throwable) : Exception(cause)

    /** يعيد المهمة بعد النقل. عند الفشل يبقى الملف المحلي والسجل كما كانا (ويُرمى [PublishFailed]). */
    suspend fun publish(t: TaskEntity, local: File, target: PublishTarget): TaskEntity {
        val id = try { target.create(local.name, StorageDirs.mime(local.name)) } catch (e: Exception) { throw PublishFailed(e) }
        dao.setPublish(t.id, id, local.path)
        onStage(Stage.CREATED)
        try {
            target.write(id, local)
            val copied = target.length(id)
            if (copied != local.length()) error("size mismatch $copied != ${local.length()}")
            target.finish(id)
        } catch (e: Exception) {
            runCatching { target.delete(id) }
            dao.setPublish(t.id, null, null)
            throw PublishFailed(e)
        }
        onStage(Stage.WRITTEN)
        dao.commitPublish(t.id, id)
        onStage(Stage.COMMITTED)
        cleanup(t.id, local)
        return dao.get(t.id) ?: t
    }

    private suspend fun cleanup(taskId: Long, local: File) {
        if (!local.exists() || local.delete()) dao.setPublish(taskId, null, null)
        // إن تعذّر الحذف يبقى publishLocal فيُعاد المحاولة عند الإقلاع التالي، والسجل يشير إلى الوجهة الصحيحة
    }

    /**
     * استعادة بعد موت العملية. يعيد true إن لزم النشر من جديد (النسخة السابقة لم تُثبَّت فحُذفت).
     */
    suspend fun recover(t: TaskEntity, target: PublishTarget?): Boolean {
        val uri = t.publishUri
        val local = t.publishLocal
        if (uri == null && local == null) return false
        return if (uri != null && t.filePath != uri) {
            // لم يُثبَّت: النسخة ناقصة أو غير متحقق منها → تُحذف والأصل باقٍ
            target?.let { runCatching { it.delete(uri) } }
            dao.setPublish(t.id, null, null)
            true
        } else {
            local?.let { cleanup(t.id, File(it)) } ?: dao.setPublish(t.id, null, null)
            false
        }
    }
}

/** مجلد اختاره المستخدم عبر SAF (بطاقة SD أو USB أو غيرهما). */
class SafTarget(private val ctx: Context, treeUri: String) : PublishTarget {
    private val tree = DocumentFile.fromTreeUri(ctx, Uri.parse(treeUri)) ?: error("tree")

    override fun create(name: String, mime: String): String {
        var n = name
        var i = 1
        val dot = name.lastIndexOf('.').takeIf { it > 0 }
        while (tree.findFile(n) != null) {
            n = if (dot != null) "${name.substring(0, dot)} ($i)${name.substring(dot)}" else "$name ($i)"
            i++
        }
        return (tree.createFile(mime, n) ?: error("create")).uri.toString()
    }

    override fun write(id: String, src: File) {
        (ctx.contentResolver.openOutputStream(Uri.parse(id), "w") ?: error("open")).use { out -> src.inputStream().use { it.copyTo(out, 256 * 1024) } }
    }

    override fun length(id: String): Long = DocumentFile.fromSingleUri(ctx, Uri.parse(id))?.length() ?: -1

    override fun delete(id: String): Boolean = DocumentFile.fromSingleUri(ctx, Uri.parse(id))?.delete() == true
}

/**
 * «التنزيلات» العامة عبر MediaStore (Android 10+) حين لا تُتاح الكتابة المباشرة:
 * يُنشأ الملف مخفياً (IS_PENDING) ولا يظهر للتطبيقات الأخرى إلا بعد اكتمال النسخ والتحقق.
 */
@androidx.annotation.RequiresApi(29)
class MediaStoreTarget(private val ctx: Context, private val relativePath: String) : PublishTarget {
    override fun create(name: String, mime: String): String {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return (ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("insert")).toString()
    }

    override fun write(id: String, src: File) {
        (ctx.contentResolver.openOutputStream(Uri.parse(id), "w") ?: error("open")).use { out -> src.inputStream().use { it.copyTo(out, 256 * 1024) } }
    }

    override fun length(id: String): Long =
        ctx.contentResolver.openFileDescriptor(Uri.parse(id), "r")?.use { it.statSize } ?: -1

    override fun finish(id: String) {
        ctx.contentResolver.update(Uri.parse(id), ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    }

    override fun delete(id: String): Boolean = runCatching { ctx.contentResolver.delete(Uri.parse(id), null, null) > 0 }.getOrDefault(false)
}
