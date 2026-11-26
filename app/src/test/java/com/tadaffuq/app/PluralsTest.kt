package com.tadaffuq.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tadaffuq.app.core.pr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** صيغ الجمع العربية الست مع أرقام لاتينية معزولة اتجاهياً (‎U+2068…U+2069). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "ar")
class PluralsTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private fun plain(s: String) = s.replace("⁨", "").replace("⁩", "").replace("⁦", "").replace("⁧", "")

    @Test fun arabicSixForms() {
        val r = R.plurals.links_n
        assertEquals("لا روابط", plain(ctx.pr(r, 0, 0)))
        assertEquals("رابط واحد", plain(ctx.pr(r, 1, 1)))
        assertEquals("رابطان", plain(ctx.pr(r, 2, 2)))
        assertEquals("3 روابط", plain(ctx.pr(r, 3, 3)))
        assertEquals("11 رابطاً", plain(ctx.pr(r, 11, 11)))
        assertEquals("100 رابط", plain(ctx.pr(r, 100, 100)))
    }

    @Test fun digitsAreLatinAndIsolated() {
        val s = ctx.pr(R.plurals.connections_n, 11, 11)
        assertTrue("isolated: $s", s.contains("⁨11⁩") || s.contains("⁦11⁩"))
        assertTrue("no Arabic-Indic digits: $s", s.none { it in '٠'..'٩' })
    }

    @Test fun multiArgPluralKeepsOtherArgs() {
        assertEquals("ملفان · 5 م.ب", plain(ctx.pr(R.plurals.files_sub, 2, 2, "5 م.ب")))
        assertEquals("3 من 12 ملفاً · 1 غ.ب", plain(ctx.pr(R.plurals.torrent_summary, 12, 3, 12, "1 غ.ب")))
    }
}
