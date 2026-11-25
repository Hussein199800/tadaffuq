package com.tadaffuq.engine

/**
 * «طبيب التحميل»: قواعد ثابتة (ليست ذكاءً اصطناعياً) تقرأ نتيجة الفحص والقراءات الحقيقية
 * وتعيد تشخيصات؛ الواجهة تترجمها إلى نص عربي واضح مع حل مقترح.
 */
object Doctor {
    enum class Finding {
        NO_RESUME,            // الخادم لا يدعم Range: انقطاع = بداية من الصفر، واتصال واحد فقط
        UNKNOWN_SIZE,         // لا يُعرف الحجم: لا تقدير للوقت ولا تقسيم
        LINK_EXPIRED,         // 401/403/410: حدّث الرابط من الصفحة الأصلية
        NOT_FOUND,
        CHANGED_ON_SERVER,    // الملف تغيّر: بدء جديد آمن بدل خلط نسختين
        SERVER_ERRORS,        // 5xx/429 متكررة: الخادم مزدحم
        NETWORK_UNSTABLE,     // إعادة محاولات كثيرة بسبب الشبكة
        DNS_FAILURE,          // تعذّر تحويل اسم الخادم
        TLS_FAILURE,          // شهادة غير موثوقة أو مصافحة فاشلة: لا تجاوز للحماية
        TIMEOUT,              // انتهت المهلة
        CONNECTION_REFUSED,   // الخادم رفض الاتصال
        SERVER_BUSY,          // 429/503: تراجع تلقائي عن الاتصالات
        PERMISSION,           // لا صلاحية للكتابة في المجلد
        BACKGROUND_RESTRICTED, // النظام يقيّد التطبيق في الخلفية
        SLOW_SERVER,          // السرعة لا تزيد مع الاتصالات: الحد من الخادم
        SINGLE_CONNECTION_CAP, // سرعة الاتصال الواحد منخفضة والمزيد يساعد
        LOW_SPACE,
        STORAGE_ERROR,
        CHECKSUM_MISMATCH,
        HEALTHY,
    }

    data class Input(
        val probe: ProbeResult?,
        val error: DownloadError?,
        val retries: Int = 0,
        val networkRetries: Int = 0,
        val serverRetries: Int = 0,
        val averageBps: Long = 0,
        val connections: Int = 0,
        val autoTuneStopped: Boolean = false,
        val freeBytes: Long = -1,
        val remainingBytes: Long = -1,
        val backgroundRestricted: Boolean = false,
    )

    fun diagnose(i: Input): List<Finding> {
        val out = mutableListOf<Finding>()
        when (i.error) {
            is DownloadError.LinkExpired -> out += Finding.LINK_EXPIRED
            DownloadError.NotFound -> out += Finding.NOT_FOUND
            DownloadError.ChangedOnServer -> out += Finding.CHANGED_ON_SERVER
            is DownloadError.InsufficientSpace -> out += Finding.LOW_SPACE
            is DownloadError.Storage -> out += if (i.error.detail.contains("EACCES") || i.error.detail.contains("ermission")) Finding.PERMISSION else Finding.STORAGE_ERROR
            is DownloadError.ChecksumMismatch -> out += Finding.CHECKSUM_MISMATCH
            is DownloadError.Http -> out += if (i.error.code == 429 || i.error.code == 503) Finding.SERVER_BUSY else Finding.SERVER_ERRORS
            is DownloadError.Network -> out += when (i.error.kind) {
                NetFail.DNS -> Finding.DNS_FAILURE
                NetFail.TLS -> Finding.TLS_FAILURE
                NetFail.TIMEOUT -> Finding.TIMEOUT
                NetFail.REFUSED -> Finding.CONNECTION_REFUSED
                NetFail.RESET -> Finding.NETWORK_UNSTABLE
            }
            DownloadError.RangeNotSupported -> out += Finding.NO_RESUME
            null -> Unit
        }
        i.probe?.let { p ->
            if (!p.acceptRanges && Finding.NO_RESUME !in out) out += Finding.NO_RESUME
            if (p.totalBytes <= 0) out += Finding.UNKNOWN_SIZE
        }
        if (i.networkRetries >= 3 && Finding.NETWORK_UNSTABLE !in out) out += Finding.NETWORK_UNSTABLE
        if (i.serverRetries >= 3 && Finding.SERVER_ERRORS !in out) out += Finding.SERVER_ERRORS
        if (i.autoTuneStopped && i.connections > 1 && i.averageBps in 1 until 512 * 1024) out += Finding.SLOW_SERVER
        if (i.connections == 1 && i.probe?.resumable == true && i.averageBps in 1 until 256 * 1024) out += Finding.SINGLE_CONNECTION_CAP
        if (i.freeBytes >= 0 && i.remainingBytes > 0 && i.freeBytes < i.remainingBytes * 11 / 10) {
            if (Finding.LOW_SPACE !in out) out += Finding.LOW_SPACE
        }
        if (i.backgroundRestricted) out += Finding.BACKGROUND_RESTRICTED
        if (out.isEmpty()) out += Finding.HEALTHY
        return out
    }
}
