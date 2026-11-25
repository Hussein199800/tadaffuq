package com.tadaffuq.engine

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * حجز المساحة بين التنزيلات المتزامنة: الملف يُحجز «متناثراً» فلا تنقص المساحة الحرة فوراً،
 * فبدون هذا الدفتر قد تُقبل تنزيلات متزامنة يتجاوز مجموعها المساحة الفعلية.
 * المفتاح وحدة التخزين (FileStore)؛ وإن تعذّر معرفتها فالمسار الكامل للمجلد.
 */
object SpaceLedger {
    class Ticket internal constructor(val volume: String, internal val bytes: AtomicLong)

    private val tickets = ConcurrentHashMap.newKeySet<Ticket>()

    fun volumeOf(dir: File): String = runCatching { java.nio.file.Files.getFileStore(dir.toPath()).toString() }.getOrNull() ?: dir.absolutePath

    fun reserve(dir: File, bytes: Long): Ticket = Ticket(volumeOf(dir), AtomicLong(bytes.coerceAtLeast(0))).also { tickets += it }
    fun update(t: Ticket, remaining: Long) = t.bytes.set(remaining.coerceAtLeast(0))
    fun release(t: Ticket) { tickets -= t }
    /** للاختبارات والتشخيص: مجموع المحجوز على وحدة تخزين. */
    fun reservedOn(dir: File): Long { val v = volumeOf(dir); return tickets.filter { it.volume == v }.sumOf { it.bytes.get() } }
    fun reservedByOthers(dir: File, mine: Ticket): Long {
        val v = volumeOf(dir)
        return tickets.filter { it !== mine && it.volume == v }.sumOf { it.bytes.get() }
    }
}
