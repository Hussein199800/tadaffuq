package com.tadaffuq.engine

import kotlinx.coroutines.delay

/**
 * دلو رموز (token bucket) بنموذج «الدَّين»: كل قراءة تخصم من الرصيد، وإن صار سالباً
 * ينتظر المستدعي حتى يُسدَّد. السعة ثانية واحدة. 0 = بلا حد. قابل للتغيير أثناء التشغيل.
 */
class RateLimiter(bytesPerSecond: Long = 0, private val clock: () -> Long = System::nanoTime) {
    @Volatile var bytesPerSecond: Long = bytesPerSecond.coerceAtLeast(0)
        set(value) { field = value.coerceAtLeast(0) }

    private val lock = Any()
    private var tokens = 0.0
    private var last = clock()

    /**
     * يخصم [bytes] من الرصيد وينتظر حتى يُسدَّد نصيب هذا المستدعي من الدين (بترتيب الوصول).
     * الانتظار على شرائح قصيرة (≤100 ملّي ث) تُعيد قراءة الحد: رفعه يقصّر الانتظار فوراً،
     * و«بلا حد» ينهيه ويمسح الدين. الإلغاء فوري، ودين البايتات التي وصلت فعلاً يبقى فلا يُتجاوز الحد.
     */
    suspend fun acquire(bytes: Int) {
        if (bytesPerSecond <= 0) {
            synchronized(lock) { last = clock(); tokens = 0.0 }
            return
        }
        // دين هذا المستدعي = العجز بعد خصمه (يشمل من سبقه)
        var debt = synchronized(lock) {
            refill(bytesPerSecond)
            tokens -= bytes
            -tokens
        }
        var mark = clock()
        while (debt > 0) {
            val rate = bytesPerSecond
            if (rate <= 0) {
                synchronized(lock) { last = clock(); tokens = 0.0 }
                return
            }
            val waitMs = (debt / rate * 1000).toLong().coerceIn(1, SLICE_MS)
            delay(waitMs)
            val now = clock()
            debt -= (now - mark) / 1e9 * bytesPerSecond.coerceAtLeast(0)
            mark = now
        }
    }

    private fun refill(rate: Long) {
        val now = clock()
        tokens = (tokens + (now - last) / 1e9 * rate).coerceAtMost(rate.toDouble())
        last = now
    }

    private companion object { const val SLICE_MS = 100L }
}
