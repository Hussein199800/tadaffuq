package com.tadaffuq.app.core

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/** دور «المتصفح الافتراضي» في أندرويد: هل تدفّق يحمله، وكيف يُطلب (نافذة النظام، وإلا صفحة التطبيقات الافتراضية). */
object BrowserRole {
    fun held(ctx: Context): Boolean = if (Build.VERSION.SDK_INT >= 29) runCatching {
        ctx.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_BROWSER)
    }.getOrDefault(false) else false

    fun requestIntent(ctx: Context): Intent {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = ctx.getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_BROWSER) && !rm.isRoleHeld(RoleManager.ROLE_BROWSER)) return rm.createRequestRoleIntent(RoleManager.ROLE_BROWSER)
        }
        return Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
    }
}
