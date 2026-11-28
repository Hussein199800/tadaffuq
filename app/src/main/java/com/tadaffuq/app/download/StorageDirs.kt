package com.tadaffuq.app.download

import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
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
     * أين يُكتب الملف أثناء التنزيل:
     * - Android 10+: مجلد مرحلي خاص بالتطبيق (Android/data/…/Download/تدفق) ثم يُنشر بعد الاكتمال والتحقق
     *   في «التنزيلات/تدفق» عبر MediaStore (IS_PENDING) — الوجهة العامة الافتراضية، بلا صلاحية وصول شامل.
     * - Android 9: الكتابة المباشرة في «التنزيلات/تدفق» (بإذن التخزين)، وإلا المجلد الخاص مع تنبيه.
     */
    fun root(): File {
        val app = appDir
        if (app != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return app
        return directRoot()
    }

    /** الكتابة المباشرة في «التنزيلات/تدفق» إن أمكنت فعلاً (التورنت وتصدير المجلد الخاص)، وإلا المجلد الخاص. */
    fun directRoot(): File {
        val pub = publicRoot()
        val fallback = appDir ?: return pub
        val now = System.currentTimeMillis()
        if (now - checkedAt > 10_000) {
            publicOk = runCatching { (pub.isDirectory || pub.mkdirs()) && pub.canWrite() }.getOrDefault(false)
            checkedAt = now
        }
        return if (publicOk) pub else fallback
    }

    /** الملفات لن تصل إلى «التنزيلات» العامة (Android 9 بلا إذن التخزين): تنبيه في الواجهة. */
    val usingFallback: Boolean get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && appDir != null && directRoot() != publicRoot()

    fun appRoot(): File? = appDir

    fun isInAppDir(dir: File): Boolean = appDir?.let { dir.absolutePath.startsWith(it.absolutePath) } == true

    /** المسار النسبي المقابل في «التنزيلات» العامة لمجلد داخل المجلد الاحتياطي. */
    fun mediaStorePath(dir: File): String {
        val base = appDir ?: return Environment.DIRECTORY_DOWNLOADS + "/" + ROOT
        val rel = dir.absolutePath.removePrefix(base.absolutePath).trim('/')
        return listOf(Environment.DIRECTORY_DOWNLOADS, ROOT, rel).filter { it.isNotEmpty() }.joinToString("/") + "/"
    }


    /** مسار مختصر للعرض: من «Download» فما بعد. */
    fun display(dir: File): String {
        val p = dir.path
        val i = p.indexOf("/" + Environment.DIRECTORY_DOWNLOADS + "/")
        return if (i >= 0) p.substring(i + 1) else p
    }

    fun trash(): File = File(root(), ".سلة")

    fun folderName(c: FileCategory) = when (c) {
        FileCategory.VIDEO -> "فيديو"
        FileCategory.AUDIO -> "صوت"
        FileCategory.IMAGES -> "صور"
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
    fun partFile(dir: String, taskId: Long) = File(dir, ".$taskId$PART_SUFFIX")

    /** مسار rc2/rc3 (المعرّف + الاسم)؛ يُرحَّل إلى [partFile] لأن الاسم قد يتغير عند الإنهاء. */
    fun rc3PartFile(dir: String, name: String, taskId: Long) = File(dir, ".$taskId-${name.take(80)}$PART_SUFFIX")

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
