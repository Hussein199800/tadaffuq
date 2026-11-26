package com.tadaffuq.app

import android.Manifest
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.tadaffuq.app.ui.add.AddRequest
import com.tadaffuq.app.ui.add.AddSheet
import com.tadaffuq.app.ui.theme.TadaffuqTheme
import com.tadaffuq.app.ui.torrent.TorrentAddSheet
import com.tadaffuq.app.ui.torrent.TorrentRequest
import com.tadaffuq.data.SettingsRepository
import com.tadaffuq.engine.LinkTools
import com.tadaffuq.engine.Protocols
import com.tadaffuq.torrent.TorrentEngine
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * نشاط شفاف بلا واجهة رئيسية: يقرأ الحافظة حين تُركَّز نافذته (الشرط الذي يفرضه Android 10+)
 * ويعرض نافذة الإضافة فوق التطبيق الذي كان المستخدم فيه، ثم يُغلق.
 */
@AndroidEntryPoint
class QuickAddActivity : AppCompatActivity() {
    @Inject lateinit var settings: SettingsRepository
    private var request by mutableStateOf<AddRequest?>(null)
    private var torrent by mutableStateOf<TorrentRequest?>(null)
    private var read = false
    private val storage = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT <= 28 && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            storage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            // البلاطة قد تُستعمل قبل فتح التطبيق: بلا هذا الإذن لا يظهر تقدم التنزيل ولا أزراره
            storage.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            val s by settings.settings.collectAsState(initial = com.tadaffuq.data.Settings())
            TadaffuqTheme(s.theme, fontScale = s.textSize.scale, dynamic = s.dynamicColor, accent = s.accent, palette = s.palette) {
                torrent?.let { TorrentAddSheet(it, onDismiss = { finish() }) }
                    ?: request?.let { AddSheet(it, onDismiss = { finish() }, onTorrent = { m -> torrent = TorrentRequest(magnet = m) }) }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || read) return
        read = true
        val text = runCatching {
            getSystemService(ClipboardManager::class.java).primaryClip?.let { c -> (0 until c.itemCount).joinToString("\n") { c.getItemAt(it).coerceToText(this).toString() } }
        }.getOrNull().orEmpty()
        when {
            TorrentEngine.isMagnet(text.trim()) -> torrent = TorrentRequest(magnet = text.trim())
            else -> {
                // نمرر النص كاملاً: نافذة الإضافة تستخرج الروابط (واحداً أو عدة) وتتجاهل غيرها
                val links = LinkTools.extract(text).filter(Protocols::isSupported)
                request = AddRequest(if (links.isEmpty()) "" else text, fromClipboard = true, clipboardEmpty = links.isEmpty())
            }
        }
    }
}
