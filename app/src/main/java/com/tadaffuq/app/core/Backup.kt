package com.tadaffuq.app.core

import com.tadaffuq.data.AppDatabase
import com.tadaffuq.data.BookmarkEntity
import com.tadaffuq.data.SavedLinkEntity
import com.tadaffuq.data.Settings
import com.tadaffuq.data.TaskEntity
import com.tadaffuq.data.TaskStatus
import org.json.JSONArray
import org.json.JSONObject

/**
 * نسخ احتياطي واستعادة للإعدادات والطابور والسجل والروابط والمفضلة بصيغة JSON مقروءة.
 * الأسرار (كلمات المرور والرموز والكوكيز) لا تُصدَّر أبداً. الاستيراد يدمج ولا يحذف شيئاً.
 */
object Backup {
    const val VERSION = 1

    suspend fun export(db: AppDatabase, s: Settings): String {
        val o = JSONObject().put("app", "tadaffuq").put("version", VERSION).put("exportedAt", System.currentTimeMillis())
        o.put("settings", JSONObject()
            .put("maxConnections", s.maxConnections).put("maxParallel", s.maxParallel).put("globalLimitBps", s.globalLimitBps)
            .put("wifiOnly", s.wifiOnly).put("mode", s.mode.name).put("theme", s.theme.name).put("language", s.language)
            .put("sortByCategory", s.sortByCategory).put("pauseOnLowBattery", s.pauseOnLowBattery).put("onlyWhileCharging", s.onlyWhileCharging)
            .put("dailyLimitBytes", s.dailyLimitBytes).put("monthlyLimitBytes", s.monthlyLimitBytes).put("rules", JSONArray(s.rulesJson))
            .put("autoExtract", s.autoExtract).put("windowEnabled", s.windowEnabled).put("windowStart", s.windowStart).put("windowEnd", s.windowEnd))
        o.put("tasks", JSONArray().apply {
            db.tasks().all().filter { it.trashedAt == null }.forEach { t ->
                put(JSONObject().put("url", t.url).put("fileName", t.fileName).put("dirPath", t.dirPath).put("status", t.status.name)
                    .put("totalBytes", t.totalBytes).put("createdAt", t.createdAt).put("completedAt", t.completedAt ?: JSONObject.NULL)
                    .put("filePath", t.filePath ?: JSONObject.NULL).put("expectedHash", t.expectedHash ?: JSONObject.NULL)
                    .put("actualHash", t.actualHash ?: JSONObject.NULL).put("priority", t.priority).put("mirrors", t.mirrors ?: JSONObject.NULL))
                // لا كوكيز ولا Referer ولا User-Agent: قد تحمل جلسات دخول
            }
        })
        o.put("links", JSONArray().apply { db.links().all().forEach { put(JSONObject().put("url", it.url).put("title", it.title ?: JSONObject.NULL)) } })
        o.put("bookmarks", JSONArray().apply { db.browser().allBookmarks().forEach { put(JSONObject().put("url", it.url).put("title", it.title)) } })
        return o.toString(2)
    }

    data class ImportResult(val tasks: Int, val links: Int, val bookmarks: Int, val settings: Settings?)

    suspend fun import(db: AppDatabase, json: String, current: Settings): ImportResult {
        val o = JSONObject(json)
        require(o.optString("app") == "tadaffuq") { "not a tadaffuq backup" }
        val existing = db.tasks().all().map { it.url to it.fileName }.toSet()
        val tasks = o.optJSONArray("tasks") ?: JSONArray()
        val toInsert = (0 until tasks.length()).map { tasks.getJSONObject(it) }.filter { (it.getString("url") to it.getString("fileName")) !in existing }.map { t ->
            val status0 = runCatching { TaskStatus.valueOf(t.getString("status")) }.getOrDefault(TaskStatus.PAUSED)
            val path = t.optString("filePath").takeIf { !t.isNull("filePath") }
            // «مكتمل» فقط إن كان الملف على هذا الجهاز فعلاً؛ وإلا (هاتف جديد، أو رابط SAF بلا إذن هنا) سجل متوقف
            // بسبب واضح، والاستئناف يعيد تنزيله
            val present = path != null && !path.startsWith("content://") && java.io.File(path).isFile
            val missing = status0 == TaskStatus.COMPLETED && !present
            val status = if (missing) TaskStatus.PAUSED else status0
            TaskEntity(
                url = t.getString("url"), fileName = t.getString("fileName"), dirPath = t.getString("dirPath"),
                // ما لم يكتمل يُستورد متوقفاً بلا أجزاء: يبدأ من جديد بأمان عند الاستئناف
                status = if (status == TaskStatus.COMPLETED) TaskStatus.COMPLETED else TaskStatus.PAUSED,
                totalBytes = t.optLong("totalBytes", -1), createdAt = t.optLong("createdAt", System.currentTimeMillis()),
                completedAt = t.optLong("completedAt").takeIf { !t.isNull("completedAt") }, filePath = t.optString("filePath").takeIf { !t.isNull("filePath") },
                expectedHash = t.optString("expectedHash").takeIf { !t.isNull("expectedHash") }, actualHash = t.optString("actualHash").takeIf { !t.isNull("actualHash") },
                priority = t.optInt("priority"), mirrors = t.optString("mirrors").takeIf { !t.isNull("mirrors") },
                downloadedBytes = if (status == TaskStatus.COMPLETED) t.optLong("totalBytes", 0) else 0,
                errorCode = if (missing) "IMPORT_MISSING" else null,
            )
        }
        db.tasks().insertAll(toInsert)
        val links = o.optJSONArray("links") ?: JSONArray()
        db.links().insert((0 until links.length()).map { links.getJSONObject(it) }.map { SavedLinkEntity(url = it.getString("url"), title = it.optString("title").takeIf { _ -> !it.isNull("title") }) })
        val bm = o.optJSONArray("bookmarks") ?: JSONArray()
        (0 until bm.length()).map { bm.getJSONObject(it) }.forEach { db.browser().upsertBookmark(BookmarkEntity(url = it.getString("url"), title = it.getString("title"))) }
        val st = o.optJSONObject("settings")?.let { j ->
            current.copy(
                maxConnections = j.optInt("maxConnections", current.maxConnections), maxParallel = j.optInt("maxParallel", current.maxParallel),
                globalLimitBps = j.optLong("globalLimitBps", current.globalLimitBps), wifiOnly = j.optBoolean("wifiOnly", current.wifiOnly),
                mode = runCatching { com.tadaffuq.engine.SpeedMode.valueOf(j.getString("mode")) }.getOrDefault(current.mode),
                theme = runCatching { com.tadaffuq.data.ThemeMode.valueOf(j.getString("theme")) }.getOrDefault(current.theme),
                language = j.optString("language", current.language), sortByCategory = j.optBoolean("sortByCategory", current.sortByCategory),
                pauseOnLowBattery = j.optInt("pauseOnLowBattery", current.pauseOnLowBattery), onlyWhileCharging = j.optBoolean("onlyWhileCharging", current.onlyWhileCharging),
                dailyLimitBytes = j.optLong("dailyLimitBytes", current.dailyLimitBytes), monthlyLimitBytes = j.optLong("monthlyLimitBytes", current.monthlyLimitBytes),
                rulesJson = j.optJSONArray("rules")?.toString() ?: current.rulesJson, autoExtract = j.optBoolean("autoExtract", current.autoExtract),
                windowEnabled = j.optBoolean("windowEnabled", current.windowEnabled), windowStart = j.optInt("windowStart", current.windowStart), windowEnd = j.optInt("windowEnd", current.windowEnd),
            )
        }
        return ImportResult(toInsert.size, links.length(), bm.length(), st)
    }
}
