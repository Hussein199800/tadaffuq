package com.tadaffuq.app

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.tadaffuq.app.download.DownloadManager
import com.tadaffuq.app.ui.MainViewModel
import com.tadaffuq.app.ui.add.AddRequest
import com.tadaffuq.app.ui.add.AddSheet
import com.tadaffuq.app.ui.browser.BrowserScreen
import com.tadaffuq.app.ui.downloads.DownloadsScreen
import com.tadaffuq.app.ui.files.FilesScreen
import com.tadaffuq.app.ui.home.HomeScreen
import com.tadaffuq.app.ui.more.AboutScreen
import com.tadaffuq.app.ui.more.LinksScreen
import com.tadaffuq.app.ui.more.MoreScreen
import com.tadaffuq.app.ui.more.ReportsScreen
import com.tadaffuq.app.ui.more.SettingsScreen
import com.tadaffuq.app.ui.more.TrashScreen
import com.tadaffuq.app.ui.theme.TadaffuqTheme
import com.tadaffuq.engine.LinkTools
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject lateinit var manager: DownloadManager
    @Inject lateinit var updater: com.tadaffuq.app.update.AppUpdater
    private val vm: MainViewModel by viewModels()
    private var pending by mutableStateOf<AddRequest?>(null)
    private var pendingTorrent by mutableStateOf<com.tadaffuq.app.ui.torrent.TorrentRequest?>(null)
    private var jumpTo by mutableStateOf<String?>(null)
    private var clipboardLink by mutableStateOf<String?>(null)
    private var lastClip: String? = null

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    /** Android 9: تُنتظر نتيجة إذن التخزين قبل فتح نافذة الإضافة (لا تُضاف مهمة إلى مجلد لا يُكتب فيه). */
    private var awaitingStorage: AddRequest? = null
    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val r = awaitingStorage ?: return@registerForActivityResult
        awaitingStorage = null
        if (!granted) android.widget.Toast.makeText(this, R.string.storage_denied, android.widget.Toast.LENGTH_LONG).show()
        pending = r
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handle(intent)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            val settings by vm.settings.collectAsState()
            TadaffuqTheme(settings.theme, fontScale = settings.textSize.scale, dynamic = settings.dynamicColor, accent = settings.accent, palette = settings.palette) {
                androidx.compose.runtime.CompositionLocalProvider(com.tadaffuq.app.ui.components.LocalReduceMotion provides (settings.reduceMotion || systemReducedMotion())) { App() }
            }
        }
    }

    /** «إزالة الرسوم المتحركة» في إعدادات إمكانية الوصول تُحترم تلقائياً. */
    private fun systemReducedMotion(): Boolean =
        android.provider.Settings.Global.getFloat(contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        // عاد المستخدم من إعداد «تثبيت التطبيقات غير المعروفة»: يكمل التثبيت تلقائياً إن سمح
        (updater.state.value as? com.tadaffuq.app.update.UpdateState.NeedsPermission)?.let { updater.install(it.c, it.file) }
        // اقتراح الرابط المنسوخ فقط إن فعّله المستخدم؛ Android 10+ يسمح بقراءة الحافظة والتطبيق في المقدمة.
        if (vm.settings.value.suggestClipboard) window.decorView.post {
            val text = runCatching { getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() }.getOrNull()
            val link = text?.let { LinkTools.extract(it).firstOrNull(LinkTools::isSupported) }
            if (link != null && link != lastClip) { clipboardLink = link; lastClip = link }
        }
    }

    private fun handle(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> com.tadaffuq.app.core.ExternalIntent.request(intent)?.let(::add)
            Intent.ACTION_VIEW -> {
                val data = intent.data
                when {
                    data?.scheme == "magnet" -> pendingTorrent = com.tadaffuq.app.ui.torrent.TorrentRequest(magnet = data.toString())
                    data?.scheme == "content" || data?.scheme == "file" -> readTorrent(data)
                    // رابط من تطبيق مشاهدة أو متصفح: مع Referer والترويسات والعنوان إن أرسلها
                    else -> com.tadaffuq.app.core.ExternalIntent.request(intent)?.let(::add)
                }
            }
            ACTION_ADD -> add(AddRequest(""))
            ACTION_DOWNLOADS -> jumpTo = "downloads"
            ACTION_RESUME_ALL -> {
                jumpTo = "downloads"
                if (intent.getStringExtra(EXTRA_NONCE) == com.tadaffuq.app.download.internalNonce(this)) lifecycleScope.launch { manager.resumeAll() }
            }
        }
    }

    private fun readTorrent(uri: android.net.Uri) {
        val bytes = runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        if (bytes != null && bytes.size < 20_000_000) pendingTorrent = com.tadaffuq.app.ui.torrent.TorrentRequest(raw = bytes)
    }

    private fun add(req: AddRequest) {
        if (com.tadaffuq.torrent.TorrentEngine.isMagnet(req.text)) { pendingTorrent = com.tadaffuq.app.ui.torrent.TorrentRequest(magnet = req.text.trim()); return }
        if (Build.VERSION.SDK_INT <= 28 && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            awaitingStorage = req
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        pending = req
    }

    /** لكل تبويب لونه: أيقونة مشبعة دائماً، وخلفية باستيل للمختار (لا كحلي ورمادي فقط). */
    private data class Tab(val route: String, val label: Int, val icon: ImageVector, val tint: com.tadaffuq.app.ui.components.Tint)

    @Composable
    private fun UpdateHost(dismissedCode: Int) {
        if (!updater.enabled) return
        val upd by updater.state.collectAsState()
        // فحص تلقائي هادئ عند الفتح (كل 12 ساعة، ويحترم إعداد الإيقاف)
        LaunchedEffect(Unit) { updater.check(manual = false) }
        var closed by remember { mutableStateOf(false) }
        val code = when (val s = upd) {
            is com.tadaffuq.app.update.UpdateState.Available -> s.c.versionCode
            is com.tadaffuq.app.update.UpdateState.Downloading, is com.tadaffuq.app.update.UpdateState.Verifying,
            is com.tadaffuq.app.update.UpdateState.NeedsPermission, is com.tadaffuq.app.update.UpdateState.Installing,
            is com.tadaffuq.app.update.UpdateState.Failed -> -1
            else -> null
        }
        LaunchedEffect(upd) { if (upd is com.tadaffuq.app.update.UpdateState.Checking) closed = false }
        // يظهر لكل إصدار جديد مرة، ودائماً بعد فحص يدوي أو أثناء التنزيل والتثبيت
        val show = !closed && code != null && (code == -1 || code != dismissedCode || updater.lastCheckManual)
        if (show) com.tadaffuq.app.update.UpdateDialog(updater) { closed = true }
    }

    @Composable
    private fun App() {
        val nav = rememberNavController()
        val settings by vm.settings.collectAsState()
        val tabs = listOf(
            Tab("home", R.string.tab_home, Icons.Rounded.Home, com.tadaffuq.app.ui.components.Tint.BLUE),
            Tab("browser", R.string.tab_browser, Icons.Rounded.Language, com.tadaffuq.app.ui.components.Tint.VIOLET),
            Tab("downloads", R.string.tab_downloads, Icons.Rounded.Download, com.tadaffuq.app.ui.components.Tint.MINT),
            Tab("files", R.string.tab_files, Icons.Rounded.Folder, com.tadaffuq.app.ui.components.Tint.AMBER),
            Tab("more", R.string.tab_more, Icons.Rounded.MoreHoriz, com.tadaffuq.app.ui.components.Tint.CORAL),
        )
        val entry by nav.currentBackStackEntryAsState()
        val route = entry?.destination?.route
        val tabRoute = when (route) { "settings", "advanced", "reports", "links", "trash", "about", "rules", "vault", "backup", "dev", "rpc" -> "more"; else -> route }
        fun go(r: String) = nav.navigate(r) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
        LaunchedEffect(jumpTo) { jumpTo?.let { go(it); jumpTo = null } }

        // الشاشات الكبيرة والأجهزة اللوحية: شريط تنقل جانبي ومحتوى بعرض مريح للقراءة
        val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 600
        Row(Modifier.fillMaxSize()) {
        if (wide) androidx.compose.material3.NavigationRail(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars),
            header = {
                androidx.compose.material3.FloatingActionButton(onClick = { add(AddRequest("")) }, containerColor = MaterialTheme.colorScheme.primary) {
                    Icon(Icons.Rounded.Add, stringResource(R.string.add_title))
                }
            },
        ) {
            tabs.forEach { t ->
                val c = if (com.tadaffuq.app.ui.components.isDark()) t.tint.dark else t.tint.light
                androidx.compose.material3.NavigationRailItem(
                    selected = tabRoute == t.route, onClick = { go(t.route) },
                    icon = { Icon(t.icon, null) },
                    label = { Text(stringResource(t.label), style = MaterialTheme.typography.labelSmall) },
                    colors = androidx.compose.material3.NavigationRailItemDefaults.colors(
                        selectedIconColor = c, selectedTextColor = c, indicatorColor = c.copy(alpha = 0.16f),
                        unselectedIconColor = c.copy(alpha = 0.72f), unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            }
        }
        Scaffold(
            modifier = Modifier.weight(1f),
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                if (!wide) NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    tabs.forEach { t ->
                        val c = if (com.tadaffuq.app.ui.components.isDark()) t.tint.dark else t.tint.light
                        NavigationBarItem(
                            selected = tabRoute == t.route,
                            onClick = { go(t.route) },
                            icon = { Icon(t.icon, null) },
                            label = { Text(stringResource(t.label), style = MaterialTheme.typography.labelSmall) },
                            colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                                selectedIconColor = c, selectedTextColor = c, indicatorColor = c.copy(alpha = 0.16f),
                                unselectedIconColor = c.copy(alpha = 0.72f), unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            },
            floatingActionButton = {
                if (!wide && (tabRoute == "home" || tabRoute == "downloads")) ExtendedFloatingActionButton(
                    onClick = { add(AddRequest("")) },
                    icon = { Icon(Icons.Rounded.Add, null) },
                    text = { Text(stringResource(R.string.add_short)) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            },
        ) { inner ->
            // الحاوية نفسها تبتعد عن شريط الحالة وفتحة الكاميرا (الأفقي)، فلا يمرّ المحتوى تحتها عند التمرير
            val padding = PaddingValues(top = 0.dp, bottom = inner.calculateBottomPadding())
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(androidx.compose.foundation.layout.WindowInsetsSides.Top + androidx.compose.foundation.layout.WindowInsetsSides.Horizontal)), contentAlignment = Alignment.TopCenter) {
            val motion = !com.tadaffuq.app.ui.components.LocalReduceMotion.current
            NavHost(nav, startDestination = "home", modifier = Modifier.fillMaxSize().then(if (wide && route != "browser") Modifier.widthIn(max = 840.dp) else Modifier),
                // انتقالات قصيرة (≤220 ملّي ث) لا تؤخر الاستجابة؛ تختفي مع «تقليل الحركة»
                enterTransition = { if (motion) androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200)) + androidx.compose.animation.scaleIn(androidx.compose.animation.core.tween(220), initialScale = 0.97f) else androidx.compose.animation.EnterTransition.None },
                exitTransition = { if (motion) androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)) else androidx.compose.animation.ExitTransition.None },
                popEnterTransition = { if (motion) androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200)) + androidx.compose.animation.scaleIn(androidx.compose.animation.core.tween(220), initialScale = 1.03f) else androidx.compose.animation.EnterTransition.None },
                popExitTransition = { if (motion) androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)) else androidx.compose.animation.ExitTransition.None },
            ) {
                composable("home") {
                    HomeScreen(vm, clipboardLink, onAdd = { link -> clipboardLink = null; add(AddRequest(link.orEmpty())) }, onOpenDownloads = { go("downloads") }, padding = padding)
                }
                composable("browser") {
                    BrowserScreen(PaddingValues(top = padding.calculateTopPadding(), bottom = inner.calculateBottomPadding()), onDownload = { add(it) },
                        blockPopups = settings.blockPopups, blockTrackers = settings.blockTrackers, desktop = settings.desktopMode)
                }
                composable("downloads") { DownloadsScreen(vm, { add(AddRequest("")) }, padding) }
                composable("files") { FilesScreen(vm, padding) }
                composable("more") { MoreScreen(padding) { nav.navigate(it) } }
                composable("settings") { SettingsScreen(vm, padding, { nav.popBackStack() }) { nav.navigate(it) } }
                composable("advanced") { com.tadaffuq.app.ui.more.AdvancedSettingsScreen(vm, padding) { nav.popBackStack() } }
                composable("reports") { ReportsScreen(vm, padding) { nav.popBackStack() } }
                composable("links") { LinksScreen(vm, padding, { nav.popBackStack() }) { add(it) } }
                composable("trash") { TrashScreen(vm, padding) { nav.popBackStack() } }
                composable("about") {
                    AboutScreen(padding, updates = {
                        com.tadaffuq.app.update.UpdateCard(updater, settings) { v -> vm.updateSettings { it.copy(autoUpdateCheck = v) } }
                    }) { nav.popBackStack() }
                }
                composable("rules") { com.tadaffuq.app.ui.more.RulesScreen(vm, padding) { nav.popBackStack() } }
                composable("vault") { com.tadaffuq.app.ui.more.VaultScreen(vm, padding) { nav.popBackStack() } }
                composable("dev") { com.tadaffuq.app.ui.more.DevScreen(padding, { nav.popBackStack() }) }
                composable("rpc") { com.tadaffuq.app.ui.more.RpcScreen(vm, padding, { nav.popBackStack() }) }
                composable("backup") { com.tadaffuq.app.ui.more.BackupScreen(vm, padding) { nav.popBackStack() } }
            }
        }
        }
        }
        UpdateHost(settings.updateDismissedCode)
        pending?.let { req -> AddSheet(req, onDismiss = { pending = null }, onTorrent = { m -> pending = null; pendingTorrent = com.tadaffuq.app.ui.torrent.TorrentRequest(magnet = m) }) }
        pendingTorrent?.let { r -> com.tadaffuq.app.ui.torrent.TorrentAddSheet(r, onDismiss = { pendingTorrent = null }) }
    }

    companion object {
        const val ACTION_ADD = "com.tadaffuq.app.ADD"
        const val ACTION_DOWNLOADS = "com.tadaffuq.app.DOWNLOADS"
        const val ACTION_RESUME_ALL = "com.tadaffuq.app.RESUME_ALL_UI"
        const val EXTRA_NONCE = "nonce"
    }
}
