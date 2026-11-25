package com.tadaffuq.app.download

import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.tadaffuq.engine.FileCategory
import java.io.File

/**
 * الملفات في «التنزيلات/تدفق/<الفئة>». الكتابة بمسار الملف مباشرة مدعومة لملفات التطبيق
 * في مجلد التنزيلات العام من Android 11، وعلى 10 بـ requestLegacyExternalStorage، وعلى 9 بصلاحية الكتابة.
 * أثناء التنزيل يحمل الملف اللاحقة ‎.tdq ثم يُعاد تسميته عند الاكتمال.
 */
object StorageDirs {
    const val ROOT = "تدفق"
    const val PART_SUFFIX = ".tdq"

    @Volatile private var appDir: File? = null
    @Volatile private var checkedAt = 0L
    @Volatile private var publicOk = true

    /** يُستدعى عند بدء التطبيق: مجلد خاص بالتطبيق احتياطاً إن تعذّرت الكتابة في التنزيلات العامة. */
    fun init(context: Context) { appDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { File(it, ROOT) } }

    @Suppress("DEPRECATION")
    fun publicRoot(): File = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), ROOT)

    /**
     * «التنزيلات/تدفق» إن أمكنت الكتابة فيه فعلاً (لا افتراض)، وإلا مجلد التطبيق الخاص
     * (Android/data/…/Download/تدفق) مع تنبيه في الواجهة. يُعاد الفحص كل 10 ثوانٍ على الأكثر.
     */
    fun root(): File {
        val pub = publicRoot()
        val fallback = appDir ?: return pub
        val now = System.currentTimeMillis()
        if (now - checkedAt > 10_000) {
            publicOk = runCatching { (pub.isDirectory || pub.mkdirs()) && pub.canWrite() }.getOrDefault(false)
            checkedAt = now
        }
        return if (publicOk) pub else fallback
    }

    val usingFallback: Boolean get() = appDir != null && root() != publicRoot()

    /** مسار مختصر للعرض: من «Download» فما بعد. */
    fun display(dir: File): String {
        val p = dir.path
        val i = p.indexOf("/" + Environment.DIRECTORY_DOWNLOADS + "/")
        return if (i >= 0) p.substring(i + 1) else p
    }

    fun trash(): File = File(root(), ".سلة")

    fun folderName(c: FileCategory) = when (c) {
        FileCategory.COMPRESSED -> "مضغوطة"
        FileCategory.DOCUMENTS -> "مستندات"
        FileCategory.PROGRAMS -> "برامج"
        FileCategory.DISK_IMAGES -> "صور أقراص"
        FileCategory.MODELS -> "نماذج"
        FileCategory.OTHER -> "أخرى"
    }

    fun dirFor(category: FileCategory, byCategory: Boolean): File =
        if (byCategory) File(root(), folderName(category)) else root()

    /**
     * ملف جزئي خاص بكل مهمة (المعرّف جزء من الاسم): مهمتان بالاسم نفسه لا تشتركان فيه أبداً.
     * الاسم مقصوص كي لا يتجاوز حد أنظمة الملفات (255 بايت).
     */
    fun partFile(dir: String, name: String, taskId: Long) = File(dir, ".$taskId-${name.take(80)}$PART_SUFFIX")

    /** مسار الإصدارات السابقة (الاسم + ‎.tdq)؛ يُرحَّل مرة واحدة إن لم يكن ملتبساً. */
    fun legacyPartFile(dir: String, name: String) = File(dir, name + PART_SUFFIX)

    fun mime(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

    fun uri(context: Context, file: File): Uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)

    fun scan(context: Context, file: File) {
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mime(file.name)), null)
    }

    fun openIntent(context: Context, file: File): Intent =
        Intent(Intent.ACTION_VIEW).setDataAndType(uri(context, file), mime(file.name))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

    /** لملف مؤقت داخل cache (المجلد الخاص): يُفتح عبر FileProvider بمسار share. */
    fun openIntentCache(context: Context, file: File): Intent =
        Intent(Intent.ACTION_VIEW).setDataAndType(FileProvider.getUriForFile(context, context.packageName + ".files", file), mime(file.name))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

    fun shareIntent(context: Context, files: List<File>): Intent {
        val uris = ArrayList(files.map { uri(context, it) })
        val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0]).setType(mime(files[0].name))
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris).setType("*/*")
        return Intent.createChooser(send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
