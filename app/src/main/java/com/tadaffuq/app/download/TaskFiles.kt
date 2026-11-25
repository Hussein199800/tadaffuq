package com.tadaffuq.app.download

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * ملفات خارج مسار التطبيق (بطاقة SD أو USB أو مجلد اختاره المستخدم) تُدار عبر SAF فقط،
 * بلا صلاحية الوصول الشامل للملفات. السجل يحفظ رابط content:// لها.
 */
object TaskFiles {
    fun isContent(path: String?) = path?.startsWith("content://") == true

    /** ينسخ إلى مجلد SAF ويعيد الرابط الجديد؛ لا يكتب فوق ملف بنفس الاسم. */
    fun copyToTree(ctx: Context, src: File, treeUri: String): Uri {
        val tree = DocumentFile.fromTreeUri(ctx, Uri.parse(treeUri)) ?: error("tree")
        var name = src.name
        var i = 1
        while (tree.findFile(name) != null) {
            val dot = src.name.lastIndexOf('.').takeIf { it > 0 }
            name = if (dot != null) "${src.name.substring(0, dot)} ($i)${src.name.substring(dot)}" else "${src.name} ($i)"
            i++
        }
        val doc = tree.createFile(StorageDirs.mime(src.name), name) ?: error("create")
        try {
            ctx.contentResolver.openOutputStream(doc.uri)!!.use { out -> src.inputStream().use { it.copyTo(out, 256 * 1024) } }
        } catch (e: Exception) {
            doc.delete(); throw e
        }
        // تحقق أن الحجم المنسوخ مطابق قبل أي حذف للأصل
        val copied = doc.length()
        if (copied != src.length()) { doc.delete(); error("size mismatch") }
        return doc.uri
    }

    fun treeName(ctx: Context, treeUri: String): String =
        runCatching { DocumentFile.fromTreeUri(ctx, Uri.parse(treeUri))?.name }.getOrNull() ?: treeUri

    fun openIntent(ctx: Context, path: String): Intent =
        if (isContent(path)) Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(path), ctx.contentResolver.getType(Uri.parse(path)) ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        else StorageDirs.openIntent(ctx, File(path))

    fun exists(ctx: Context, path: String?): Boolean = when {
        path == null -> false
        isContent(path) -> runCatching { DocumentFile.fromSingleUri(ctx, Uri.parse(path))?.exists() == true }.getOrDefault(false)
        else -> File(path).exists()
    }
}
