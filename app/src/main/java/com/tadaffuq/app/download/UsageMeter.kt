package com.tadaffuq.app.download

import com.tadaffuq.app.core.Fmt
import com.tadaffuq.data.NetKind
import com.tadaffuq.data.UsageDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * يعدّ البايتات المستلمة فعلاً لكل يوم ونوع شبكة لحظة استلامها، ويكتبها في قاعدة البيانات كل بضع ثوانٍ.
 * ما لم يُكتب بعد (pending) يدخل في المجاميع فوراً، فبلوغ الحد يُكتشف أثناء التنزيل لا بعد الكتابة.
 * «بيانات الجوال» = CELLULAR أو OTHER مقيس (كما في [Policy.hold]).
 */
class UsageMeter(private val dao: UsageDao, private val conditions: Conditions, private val clock: () -> Long = System::currentTimeMillis) {
    private data class Key(val day: String, val net: NetKind)
    private val pending = ConcurrentHashMap<Key, AtomicLong>()
    @Volatile private var storedToday = 0L
    @Volatile private var storedMonth = 0L
    @Volatile private var storedDay = ""
    /** بداية اليوم التالي: تغيير اليوم يُكتشف بمقارنة رقمين لا بتنسيق تاريخ لكل قطعة. */
    @Volatile private var nextMidnight = 0L
    @Volatile private var today = ""

    /** يُستدعى بعد كل إضافة على بيانات الجوال (المدير يقارن بالحدود ويوقظ الطابور). */
    @Volatile var onCellularBytes: (() -> Unit)? = null

    private fun currentDay(): String {
        val now = clock()
        if (now >= nextMidnight) {
            today = Fmt.day(now)
            nextMidnight = java.util.Calendar.getInstance().apply {
                timeInMillis = now; set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0); add(java.util.Calendar.DAY_OF_MONTH, 1)
            }.timeInMillis
        }
        return today
    }

    private fun pendingCellular(match: (String) -> Boolean) =
        pending.entries.sumOf { (k, v) -> if (k.net == NetKind.CELLULAR && match(k.day)) v.get() else 0L }

    val cellularToday: Long get() {
        val d = currentDay()
        return (if (storedDay == d) storedToday else 0L) + pendingCellular { it == d } + unconfirmed.get()
    }

    val cellularMonth: Long get() {
        val d = currentDay()
        val m = d.substring(0, 7)
        return (if (storedDay.startsWith(m)) storedMonth else 0L) + pendingCellular { it.startsWith(m) } + unconfirmed.get()
    }

    fun add(bytes: Long) {
        val st = conditions.state.value
        val net = if (st.net == NetKind.OTHER && st.metered) NetKind.CELLULAR else st.net
        pending.getOrPut(Key(currentDay(), net)) { AtomicLong() }.addAndGet(bytes)
        if (net == NetKind.CELLULAR) onCellularBytes?.invoke()
    }

    fun start(scope: CoroutineScope) = scope.launch {
        runCatching { refresh() }
        while (isActive) {
            delay(5_000)
            flush()
        }
    }

    /**
     * يكتب المعلّق دون فجوة: العدد يبقى في المعلّق حتى يُقرأ المجموع الجديد من القاعدة، ثم يُطرح.
     * في اللحظة بين الخطوتين يُحسب مرتين (تقدير زائد آمن)، لا ناقص أبداً فلا يتجاوز التنزيل الحد.
     * إن فشلت الكتابة يبقى في المعلّق ولا يضيع.
     */
    private val flushLock = kotlinx.coroutines.sync.Mutex()

    suspend fun flush() = flushLock.withLock { flushLocked() }

    private suspend fun flushLocked() {
        val written = ArrayList<Triple<AtomicLong, Long, Boolean>>()
        for ((key, counter) in pending) {
            val n = counter.get()
            if (n <= 0) continue
            try { dao.add(key.day, key.net, n); written += Triple(counter, n, key.net == NetKind.CELLULAR) } catch (e: Exception) { }
        }
        // يُطرح من المعلّق فقط بعد قراءة المجموع الجديد؛ إن فشلت القراءة يُعاد المحاولة لاحقاً
        // (المكتوب يبقى في المعلّق مؤقتاً = تقدير زائد آمن، ويُصحَّح عند نجاح القراءة دون كتابة مكررة)
        if (runCatching { refresh() }.isSuccess) written.forEach { (c, n, _) -> c.addAndGet(-n) }
        else written.forEach { (c, n, cell) -> c.addAndGet(-n); if (cell) unconfirmed.addAndGet(n) }
    }

    /** مكتوب لم يُقرأ مجموعه بعد (فشل refresh): يُحتسب حتى تنجح القراءة التالية. */
    private val unconfirmed = AtomicLong()

    private suspend fun refresh() {
        val day = currentDay()
        val month = day.substring(0, 7) + "-01"
        val rows = dao.range(month, day)
        storedToday = rows.filter { it.day == day && it.net == NetKind.CELLULAR }.sumOf { it.bytes }
        storedMonth = rows.filter { it.net == NetKind.CELLULAR }.sumOf { it.bytes }
        storedDay = day
        unconfirmed.set(0)
    }
}
