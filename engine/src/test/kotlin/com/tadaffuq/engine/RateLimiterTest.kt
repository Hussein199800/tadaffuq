package com.tadaffuq.engine

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * حد السرعة بزمن افتراضي حتمي (ساعة الاختبار)، لا ساعة الجهاز. القياس على نافذة مستقرة بهامش موثّق (±10٪)،
 * لا دقة لحظية: دلو الرموز يسمح برصيد ثانية واحدة.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RateLimiterTest {
    private fun TestScope.limiter(bps: Long) = RateLimiter(bps) { currentTime * 1_000_000 }

    /** عامل يقرأ قطعاً بحجم ثابت ويعدّ ما «نُزّل». */
    private fun TestScope.worker(vararg limiters: RateLimiter, chunk: Int = 16 * 1024, counter: AtomicLong) = launch {
        while (isActive) { limiters.forEach { it.acquire(chunk) }; counter.addAndGet(chunk.toLong()) }
    }

    @Test fun steadyRateWithinTenPercent() = runTest {
        val l = limiter(100_000)
        val got = AtomicLong()
        val w = worker(l, counter = got)
        advanceTimeBy(1_000); val start = got.get() // تجاوز رصيد البداية
        advanceTimeBy(10_000)
        val rate = (got.get() - start) / 10.0
        assertTrue("rate $rate", rate in 90_000.0..110_000.0)
        w.cancelAndJoin()
    }

    /** رفع الحد إلى «بلا حد» أثناء انتظار طويل ينهي الانتظار فوراً، لا بعد سداد الدين القديم. */
    @Test fun switchingToUnlimitedEndsWaitImmediately() = runTest {
        val l = limiter(1_000)
        l.acquire(1_000) // يستنفد الرصيد
        var done = false
        val j = launch { l.acquire(50_000); done = true } // دين 50 ثانية بالحد القديم
        advanceTimeBy(500); runCurrent()
        l.bytesPerSecond = 0
        advanceTimeBy(300); runCurrent()
        assertTrue("still waiting the old 50s debt", done)
        j.join()
    }

    /** رفع الحد أثناء الانتظار يقصّر الانتظار بحسب الحد الجديد. */
    @Test fun raisingLimitShortensWait() = runTest {
        val l = limiter(1_000)
        l.acquire(1_000)
        val t0 = currentTime
        val j = launch { l.acquire(20_000) } // 20 ث بالحد القديم
        advanceTimeBy(200); runCurrent()
        l.bytesPerSecond = 100_000 // الدين المتبقي يُسدَّد في أقل من ثانية
        j.join()
        assertTrue("waited ${currentTime - t0} ms", currentTime - t0 < 2_000)
    }

    /** من «بلا حد» إلى حد: لا رصيد متراكم من فترة بلا حد يسمح بدفعة ضخمة. */
    @Test fun unlimitedToLimitedHasNoHiddenBurst() = runTest {
        val l = limiter(0)
        repeat(100) { l.acquire(1_000_000) } // 100 م.ب بلا حد، بلا انتظار
        assertEquals(0L, currentTime)
        l.bytesPerSecond = 50_000
        val got = AtomicLong()
        val w = worker(l, counter = got)
        advanceTimeBy(4_000)
        assertTrue("burst ${got.get()}", got.get() <= 50_000L * 4 + 50_000 + 16 * 1024)
        w.cancelAndJoin()
    }

    /** إلغاء عامل مدين فوري، والدين المستحق لا يُمسح فيسمح لغيره بتجاوز الحد. */
    @Test fun cancelWithDebtIsPromptAndLimitHolds() = runTest {
        val l = limiter(10_000)
        l.acquire(10_000)
        val j = launch { l.acquire(100_000) } // دين 10 ث
        advanceTimeBy(100); runCurrent()
        val t0 = currentTime
        j.cancelAndJoin()
        assertEquals("cancel not prompt", t0, currentTime)
        val got = AtomicLong()
        val w = worker(l, chunk = 1_000, counter = got)
        advanceTimeBy(5_000)
        // البايتات وصلت فعلاً قبل الإلغاء، فدينها يبقى: المجموع في 5 ث لا يتجاوز الحد
        assertTrue("limit exceeded after cancel: ${got.get()}", got.get() <= 10_000L * 5)
        w.cancelAndJoin()
    }

    /** عاملان على حد عام واحد يتقاسمانه بعدالة تقريبية (±25٪)، ومجموعهما ضمن الحد. */
    @Test fun fairnessAndSumUnderGlobalLimit() = runTest {
        val global = limiter(200_000)
        val a = AtomicLong(); val b = AtomicLong()
        val wa = worker(global, counter = a); val wb = worker(global, counter = b)
        advanceTimeBy(1_000); val a0 = a.get(); val b0 = b.get()
        advanceTimeBy(10_000)
        val ra = (a.get() - a0) / 10.0; val rb = (b.get() - b0) / 10.0
        assertTrue("sum ${ra + rb}", ra + rb in 180_000.0..220_000.0)
        assertTrue("unfair $ra vs $rb", ra / rb in 0.75..1.33)
        wa.cancelAndJoin(); wb.cancelAndJoin()
    }

    /** حد المهمة وحد عام معاً: الأصغر هو الفعّال، ومجموع مهمتين لا يتجاوز العام. */
    @Test fun perTaskAndGlobalTogether() = runTest {
        val global = limiter(150_000)
        val t1 = limiter(50_000); val t2 = limiter(0)
        val a = AtomicLong(); val b = AtomicLong()
        val wa = worker(t1, global, counter = a); val wb = worker(t2, global, counter = b)
        advanceTimeBy(1_000); val a0 = a.get(); val b0 = b.get()
        advanceTimeBy(10_000)
        val ra = (a.get() - a0) / 10.0; val rb = (b.get() - b0) / 10.0
        assertTrue("task cap $ra", ra <= 55_000)
        assertTrue("sum ${ra + rb}", ra + rb <= 165_000)
        wa.cancelAndJoin(); wb.cancelAndJoin()
    }
}
