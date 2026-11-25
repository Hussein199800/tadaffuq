package com.tadaffuq.app.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * سجل تقني في الذاكرة (آخر 500 حدث) للإبلاغ عن المشكلات. كل نص يُنقّح قبل الحفظ:
 * لا استعلامات الروابط (قد تحمل رموزاً موقّعة)، ولا بيانات دخول، ولا كوكيز، ولا رموز Bearer.
 */
object TechLog {
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

    fun sanitize(s: String): String = s
        .replace(Regex("""(?i)\b((?:https?|ftps?|sftp)://)[^/\s@]+@"""), "$1***@")
        .replace(Regex("""(?i)(https?://[^\s?#]+)\?[^\s]*"""), "$1?…")
        .replace(Regex("""(?i)bearer\s+[\w.\-~+/=]+"""), "Bearer ***")
        .replace(Regex("""(?i)(authorization|cookie|token|password|passwd|secret)\s*[:=]\s*(?!Bearer \*\*\*)\S+"""), "$1=***")
        .replace(Regex("""\bhf_[A-Za-z0-9]{8,}"""), "hf_***")

    @Synchronized fun log(event: String) {
        lines.addLast("${fmt.format(Date())}  ${sanitize(event)}")
        while (lines.size > 500) lines.removeFirst()
    }

    @Synchronized fun export(header: String): String = buildString {
        appendLine(header)
        appendLine("---")
        lines.forEach { appendLine(it) }
    }
}
