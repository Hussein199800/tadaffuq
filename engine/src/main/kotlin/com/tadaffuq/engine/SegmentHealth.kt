package com.tadaffuq.engine

/**
 * يراقب سرعة كل اتصال ويحدد ما يجب قطعه وإعادة فتحه. السبب: خوادم الفيديو كثيراً ما تُبطئ اتصالاً واحداً
 * إلى بضع بايتات في الثانية أو تجمده دون إغلاق؛ مهلة القراءة لا تنطلق ما دامت بايتات قليلة تصل،
 * فيبقى الملف عند 95٪. اتصال جديد للموضع نفسه يعود عادةً بسرعة طبيعية.
 *
 * - **متجمد:** أقل من [FROZEN_BPS] خلال نافذة القياس، بعد [STALL_NS] من فتح الاتصال.
 * - **بطيء جداً:** أبطأ من عُشر أفضل سرعة رآها أي اتصال في هذا التنزيل، وأبطأ من [SLOW_FLOOR_BPS]،
 *   والمتبقي يحتاج أكثر من [MIN_GAIN_NS] بهذه السرعة. شبكة بطيئة كلها لا تُعدّ بطيئة لأن المرجع منها.
 * لكل جزء مهلة بعد الفتح [GRACE_NS] وسقف إعادة [MAX_RESTARTS] حتى لا يدور بلا نهاية.
 */
internal class SegmentHealth {
    private class Track(var startNs: Long, var startBytes: Long = 0, val samples: ArrayDeque<Pair<Long, Long>> = ArrayDeque(), var restarts: Int = 0, var finished: Boolean = false)
    private val tracks = HashMap<Int, Track>()
    /** أفضل سرعة لاتصال واحد في هذا التنزيل (بايت/ث). */
    var bestRate = 0L
        private set

    @Synchronized fun launched(index: Int, downloaded: Long, now: Long) {
        val t = tracks.getOrPut(index) { Track(now) }
        t.startNs = now
        t.startBytes = downloaded
        t.finished = false
        t.samples.clear()
        t.samples.addLast(now to downloaded)
    }

    /** [all] كل الأجزاء: المنتهي منها يُحتسب متوسط سرعته منذ فتحه (أجزاء سريعة تنتهي في أقل من ثانية تبقى مرجعاً). */
    @Synchronized fun sample(all: List<Segment>, active: Set<Segment>, now: Long) {
        for (seg in all) {
            val t = tracks[seg.index] ?: continue
            if (seg.done) {
                if (!t.finished) {
                    t.finished = true
                    val elapsed = (now - t.startNs).coerceAtLeast(200_000_000L)
                    bestRate = maxOf(bestRate, ((seg.downloaded - t.startBytes) * 1e9 / elapsed).toLong())
                }
                continue
            }
            if (seg !in active) continue
            t.samples.addLast(now to seg.downloaded)
            while (t.samples.size > 2 && now - t.samples.first().first > WINDOW_NS) t.samples.removeFirst()
            rate(t)?.let { if (now - t.startNs >= 2_000_000_000L) bestRate = maxOf(bestRate, it) }
        }
    }

    private fun rate(t: Track): Long? {
        if (t.samples.size < 2) return null
        val (t0, b0) = t.samples.first(); val (t1, b1) = t.samples.last()
        if (t1 - t0 < 1_000_000_000L) return null
        return ((b1 - b0) * 1e9 / (t1 - t0)).toLong()
    }

    @Synchronized fun toRestart(active: List<Segment>, now: Long): List<Segment> = active.filter { seg ->
        val t = tracks[seg.index] ?: return@filter false
        if (seg.done || t.restarts >= MAX_RESTARTS || now - t.startNs < GRACE_NS) return@filter false
        val span = t.samples.last().first - t.samples.first().first
        val r = rate(t) ?: return@filter false
        // نافذة قياس كاملة تقريباً ومضى على الفتح ما يكفي
        val frozen = r < FROZEN_BPS && span >= WINDOW_NS - 1_000_000_000L && now - t.startNs >= STALL_NS
        val slow = r < SLOW_FLOOR_BPS && bestRate > 0 && r * 10 < bestRate &&
            (r == 0L || seg.remaining * 1_000_000_000L / r > MIN_GAIN_NS)
        (frozen || slow).also { if (it) t.restarts++ }
    }

    companion object {
        const val GRACE_NS = 6_000_000_000L
        const val STALL_NS = 8_000_000_000L
        const val WINDOW_NS = 5_000_000_000L
        const val MIN_GAIN_NS = 10_000_000_000L
        const val SLOW_FLOOR_BPS = 64L * 1024
        /** أقل من هذا لمدة [STALL_NS] = متجمد حتى بلا مرجع (اتصال واحد). */
        const val FROZEN_BPS = 1024L
        const val MAX_RESTARTS = 8
    }
}
