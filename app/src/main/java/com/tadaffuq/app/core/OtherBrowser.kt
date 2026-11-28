package com.tadaffuq.app.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.tadaffuq.app.MainActivity

/** يفتح رابطاً في متصفح آخر. حين يكون تدفّق المتصفح الافتراضي، الطلب العادي يعود إليه، فيُستثنى من القائمة. */
object OtherBrowser {
    fun intent(ctx: Context, url: String): Intent {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
        return Intent.createChooser(view, null)
            .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(ctx, MainActivity::class.java)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun open(ctx: Context, url: String) { runCatching { ctx.startActivity(intent(ctx, url)) } }
}
