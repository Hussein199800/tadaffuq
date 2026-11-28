package com.tadaffuq.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import com.tadaffuq.app.core.ExternalIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 1.2.0: «فتح باستخدام»/«تنزيل خارجي» من تطبيقات المشاهدة بكل صيغ الإضافات الشائعة. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ExternalIntentTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val url = "https://cdn.example.tv/v/abc?token=1"

    private fun view(type: String? = "video/mp4") = Intent(Intent.ACTION_VIEW).apply {
        if (type != null) setDataAndType(Uri.parse(url), type) else data = Uri.parse(url)
    }

    /** المرشّح بلا نوع لا يطابق نية معلنة النوع؛ لذلك كان تدفّق لا يظهر لتطبيقات الأنمي. */
    @Test fun appearsForTypedAndUntypedLinks() {
        val pm = ctx.packageManager
        for (type in listOf("video/mp4", "video/*", "audio/mpeg", "application/octet-stream", "application/x-mpegURL", null)) {
            val i = view(type).setPackage(ctx.packageName)
            assertTrue("type=$type", pm.queryIntentActivities(i, 0).any { it.activityInfo.name.endsWith(".MainActivity") })
        }
    }

    /** صيغة MX Player: مصفوفة [اسم، قيمة…] و«title». */
    @Test fun mxStyleArray() {
        val r = ExternalIntent.request(view().putExtra("headers", arrayOf("Referer", "https://site.tv/ep5", "User-Agent", "UA/1", "Origin", "https://site.tv")).putExtra("title", "الحلقة 5"))!!
        assertEquals(url, r.text); assertEquals("https://site.tv/ep5", r.referer); assertEquals("UA/1", r.userAgent)
        assertEquals(mapOf("Origin" to "https://site.tv"), r.headers)
        assertEquals("الحلقة 5", r.suggestedName); assertEquals("video/mp4", r.mimeType); assertTrue(r.external)
    }

    /** صيغة متصفح أندرويد: حزمة Bundle تحت com.android.browser.headers. */
    @Test fun browserBundle() {
        val b = Bundle().apply { putString("referer", "https://site.tv/"); putString("Cookie", "sid=1"); putString("X-Requested-With", "com.anime") }
        val r = ExternalIntent.request(view(null).putExtra("com.android.browser.headers", b))!!
        assertEquals("https://site.tv/", r.referer); assertEquals("sid=1", r.cookies)
        assertEquals(mapOf("X-Requested-With" to "com.anime"), r.headers); assertNull(r.mimeType)
    }

    /** مفاتيح منفصلة بأسماء مختلفة، ونص بأسطر، ومصفوفة «اسم: قيمة»، والمفتاح المنفصل يغلب. */
    @Test fun separateKeysLinesAndColonArrays() {
        val r = ExternalIntent.request(view().putExtra("extra_referer", "https://a.tv/x").putExtra("android.intent.extra.User-Agent", "UA/2")
            .putExtra("extra_cookies", "c=2").putExtra("extra_filename", "Ep 7").putExtra("title", "ignored")
            .putExtra("headers", "Referer: https://other/\nAccept-Language: ar\n"))!!
        assertEquals("https://a.tv/x", r.referer); assertEquals("UA/2", r.userAgent); assertEquals("c=2", r.cookies)
        assertEquals("Ep 7", r.suggestedName); assertEquals(mapOf("Accept-Language" to "ar"), r.headers)
        val arr = ExternalIntent.request(view().putExtra("headers", arrayListOf("Origin: https://o", "X-A: 1")))!!
        assertEquals(mapOf("Origin" to "https://o", "X-A" to "1"), arr.headers)
        val map = ExternalIntent.request(view().putExtra("headers", hashMapOf("Origin" to "https://m")))!!
        assertEquals(mapOf("Origin" to "https://m"), map.headers)
    }

    /** ما يرسله تطبيق آخر غير موثوق: لا حقن أسطر، ولا Range/Host، ولا Referer ليس رابط ويب، ولا اسم بأسطر. */
    @Test fun hostileExtrasAreCleaned() {
        val r = ExternalIntent.request(view().putExtra("headers", arrayOf("Range", "bytes=5-", "Host", "evil", "X-Ok", "a\r\nX-Injected: 1", "Bad Name", "v"))
            .putExtra(Intent.EXTRA_REFERRER, Uri.parse("android-app://com.anime")).putExtra("title", "a\nb"))!!
        assertTrue(r.headers.toString(), r.headers.isEmpty())
        assertNull(r.referer)
        assertEquals("a b", r.suggestedName)
    }

    /** «فتح» رابط (تدفّق متصفحاً افتراضياً) يختلف عن المشاركة: الصفحة تُفتح في المتصفح المدمج تلقائياً للأول فقط. */
    @Test fun openedFlagAndOtherBrowserExcludesUs() {
        assertTrue(ExternalIntent.request(view(null))!!.opened)
        assertTrue(!ExternalIntent.request(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url))!!.opened)
        val chooser = com.tadaffuq.app.core.OtherBrowser.intent(ctx, url)
        @Suppress("DEPRECATION") val excluded = chooser.getParcelableArrayExtra(Intent.EXTRA_EXCLUDE_COMPONENTS)!!.map { it as android.content.ComponentName }
        assertTrue(excluded.any { it.className.endsWith(".MainActivity") })
        @Suppress("DEPRECATION") val inner = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(url, inner.dataString)
    }

    @Test fun sendAndEmpty() {
        val r = ExternalIntent.request(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "شاهد $url").putExtra("referer", "https://s/"))!!
        assertEquals("شاهد $url", r.text); assertEquals("https://s/", r.referer)
        assertNull(ExternalIntent.request(Intent(Intent.ACTION_VIEW)))
        assertNull(ExternalIntent.request(Intent(Intent.ACTION_MAIN)))
    }
}
