package com.tadaffuq.app.core

/** خلية CSV آمنة: مقتبسة دائماً، وما يبدأ بـ = + - @ أو محرف تحكم يُسبق بـ ' كي لا تنفذه برامج الجداول صيغة. */
object Csv {
    fun cell(v: String): String {
        val safe = if (v.isNotEmpty() && v[0] in "=+-@\t\r") "'$v" else v
        return "\"" + safe.replace("\"", "\"\"") + "\""
    }
}
