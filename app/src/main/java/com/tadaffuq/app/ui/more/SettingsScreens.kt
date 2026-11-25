package com.tadaffuq.app.ui.more

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.tadaffuq.app.BuildConfig
import com.tadaffuq.app.R
import com.tadaffuq.app.core.Fmt
import com.tadaffuq.app.core.sr
import com.tadaffuq.app.download.StorageDirs
import com.tadaffuq.app.download.TaskFiles
import com.tadaffuq.app.ui.MainViewModel
import com.tadaffuq.app.ui.components.Card
import com.tadaffuq.app.ui.components.Hairline
import com.tadaffuq.app.ui.components.SectionHeader
import com.tadaffuq.app.ui.components.SettingRow
import com.tadaffuq.app.ui.components.SliderRow
import com.tadaffuq.app.ui.components.ToggleRow
import com.tadaffuq.app.ui.downloads.SpeedLimitDialog
import com.tadaffuq.app.ui.theme.Navy
import com.tadaffuq.app.ui.theme.T
import com.tadaffuq.app.ui.theme.accentPair
import com.tadaffuq.data.Accent
import com.tadaffuq.data.ListDensity
import com.tadaffuq.data.ProxyMode
import com.tadaffuq.data.Settings
import com.tadaffuq.data.ThemeMode
import com.tadaffuq.engine.SpeedMode

private fun hours(n: Int) = Fmt.iso("%02d:00".format(java.util.Locale.ROOT, n))

/** الإعدادات الأساسية: ما يحتاجه أغلب الناس، وكل خيار بسطر شرح. المتقدم في شاشة منفصلة. */
@Composable
fun SettingsScreen(vm: MainViewModel, padding: PaddingValues, onBack: () -> Unit, openAdvanced: () -> Unit) {
    val s by vm.settings.collectAsState()
    val ctx = LocalContext.current
    fun set(f: (Settings) -> Settings) = vm.updateSettings(f)
    var dialog by remember { mutableStateOf<String?>(null) }
    val tree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            set { it.copy(moveToTree = uri.toString()) }
        }
    }

    LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp)) {
        item { SubTitle(stringResource(R.string.settings), onBack = onBack) }

        item { SectionHeader(stringResource(R.string.sec_download)) }
        item {
            Card(padding = PaddingValues(vertical = 4.dp)) {
                SettingRow(stringResource(R.string.save_folder), stringResource(R.string.save_folder_desc),
                    value = if (s.moveToTree.isBlank()) sr(R.string.default_folder, StorageDirs.ROOT) else TaskFiles.treeName(ctx, s.moveToTree),
                    onClick = { tree.launch(null) }) {
                    if (s.moveToTree.isNotBlank()) TextButton(onClick = { set { it.copy(moveToTree = "") } }) { Text(stringResource(R.string.reset_default)) }
                }
                Hairline()
                ToggleRow(stringResource(R.string.by_category), sr(R.string.by_category_desc, StorageDirs.ROOT), s.sortByCategory) { v -> set { it.copy(sortByCategory = v) } }
                Hairline()
                var par by remember(s.maxParallel) { mutableFloatStateOf(s.maxParallel.toFloat()) }
                SliderRow(stringResource(R.string.max_parallel), stringResource(R.string.max_parallel_desc), par, 1f..10f, 8, Fmt.iso(par.toInt().toString()), { par = it }) { set { it.copy(maxParallel = par.toInt()) } }
                Hairline()
                var conn by remember(s.maxConnections) { mutableFloatStateOf(s.maxConnections.toFloat()) }
                SliderRow(stringResource(R.string.max_connections), stringResource(R.string.max_connections_live_desc), conn, 1f..32f, 30, Fmt.iso(conn.toInt().toString()), { conn = it }) { set { it.copy(maxConnections = conn.toInt()) } }
                Hairline()
                SettingRow(stringResource(R.string.global_limit), stringResource(R.string.global_limit_desc),
                    value = if (s.globalLimitBps > 0) Fmt.speed(s.globalLimitBps) else stringResource(R.string.unlimited), onClick = { dialog = "limit" })
                Hairline()
                SettingRow(stringResource(R.string.speed_mode), stringResource(R.string.speed_mode_desc), value = stringResource(modeLabel(s.mode)), onClick = { dialog = "mode" })
            }
        }

        item { SectionHeader(stringResource(R.string.sec_network_basic)) }
        item {
            Card(padding = PaddingValues(vertical = 4.dp)) {
                ToggleRow(stringResource(R.string.wifi_only), stringResource(R.string.wifi_only_desc), s.wifiOnly) { v -> set { it.copy(wifiOnly = v) } }
                Hairline()
                ToggleRow(stringResource(R.string.auto_resume), stringResource(R.string.auto_resume_desc), s.autoResume) { v -> set { it.copy(autoResume = v) } }
            }
        }

        item { SectionHeader(stringResource(R.string.sec_capture)) }
        item {
            Card(padding = PaddingValues(vertical = 4.dp)) {
                SettingRow(stringResource(R.string.tile_add), stringResource(R.string.tile_add_desc), onClick = {
                    if (!com.tadaffuq.app.download.TileRequest.request(ctx) { ok -> if (ok) android.widget.Toast.makeText(ctx, R.string.tile_add_done, android.widget.Toast.LENGTH_SHORT).show() }) dialog = "tile"
                })
            }
        }

        item { SectionHeader(stringResource(R.string.sec_notifications)) }
        item {
            Card(padding = PaddingValues(vertical = 4.dp)) {
                ToggleRow(stringResource(R.string.notify_per_task), stringResource(R.string.notify_per_task_desc), s.notifyPerTask) { v -> set { it.copy(notifyPerTask = v) } }
                Hairline()
                ToggleRow(stringResource(R.string.notify_done), stringResource(R.string.notify_done_desc), s.notifyOnComplete) { v -> set { it.copy(notifyOnComplete = v) } }
                Hairline()
                ToggleRow(stringResource(R.string.notify_fail), stringResource(R.string.notify_fail_desc), s.notifyOnFail) { v -> set { it.copy(notifyOnFail = v) } }
                Hairline()
                ToggleRow(stringResource(R.string.notify_sound), stringResource(R.string.notify_sound_desc), s.notifySound) { v -> set { it.copy(notifySound = v) } }
                Hairline()
                ToggleRow(stringResource(R.string.notify_vibrate), stringResource(R.string.notify_vibrate_desc), s.notifyVibrate) { v -> set { it.copy(notifyVibrate = v) } }
                Hairline()
                SettingRow(stringResource(R.string.notify_system), stringResource(R.string.notify_system_desc), onClick = {
                    runCatching { ctx.startActivity(vm.manager.notificationSettingsIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                })
            }
        }

        item { SectionHeader(stringResource(R.string.sec_look)) }
        item {
            Card(padding = PaddingValues(vertical = 4.dp)) {
                SettingRow(stringResource(R.string.theme), stringResource(R.string.theme_desc), value = stringResource(themeLabel(s.theme)), onClick = { dialog = "theme" })
                Hairline()
                if (Build.VERSION.SDK_INT >= 31) {
                    ToggleRow(stringResource(R.string.dynamic_color), stringResource(R.string.dynamic_color_desc), s.dynamicColor) { v -> set { it.copy(dynamicColor = v) } }
                    Hairline()
                }
                if (!s.dynamicColor || Build.VERSION.SDK_INT < 31) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(stringResource(R.string.accent), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.accent_desc), style = MaterialTheme.typography.bodySmall, color = T.extra.secondaryText)
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Accent.entries.forEach { a ->
                                val (bright, deep) = accentPair(a)
                                val selected = s.accent == a
                                Box(
                                    Modifier.size(if (selected) 40.dp else 34.dp).clip(CircleShape)
                                        .background(Brush.linearGradient(listOf(bright, deep)))
                                        .clickable(onClickLabel = stringResource(accentName(a))) { set { it.copy(accent = a) } },
                                    contentAlignment = Alignment.Center,
                                ) { if (selected) Box(Modifier.size(12.dp).clip(CircleShape).background(Color.White)) }
                            }
                        }
                    }
                    Hairline()
                }
                ToggleRow(stringResource(R.string.compact), stringResource(R.string.compact_desc), s.density == ListDensity.COMPACT) { v ->
                    set { it.copy(density = if (v) ListDensity.COMPACT else ListDensity.COMFORTABLE) }
                }
                Hairline()
                ToggleRow(stringResource(R.string.segments_in_list), stringResource(R.string.segments_in_list_desc), s.showSegmentsInList) { v -> set { it.copy(showSegmentsInList = v) } }
                Hairline()
                ToggleRow(stringResource(R.string.live_scene), stringResource(R.string.live_scene_desc), s.showLiveScene) { v -> set { it.copy(showLiveScene = v) } }
                Hairline()
                ToggleRow(stringResource(R.string.reduce_motion), stringResource(R.string.reduce_motion_desc), s.reduceMotion) { v -> set { it.copy(reduceMotion = v) } }
                Hairline()
                SettingRow(stringResource(R.string.language), stringResource(R.string.language_desc), value = stringResource(langLabel(s.language)), onClick = { dialog = "lang" })
            }
        }

        item {
            Card(onClick = openAdvanced, padding = PaddingValues(vertical = 4.dp)) {
                SettingRow(stringResource(R.string.advanced_settings), stringResource(R.string.advanced_settings_desc), Icons.Rounded.Tune, onClick = openAdvanced)
            }
        }
    }

    when (dialog) {
        "mode" -> ChoiceDialog(stringResource(R.string.speed_mode), SpeedMode.entries.map { it to (stringResource(modeLabel(it)) + "\n" + stringResource(modeDesc(it))) }, s.mode, { dialog = null }) { m -> set { it.copy(mode = m) } }
        "theme" -> ChoiceDialog(stringResource(R.string.theme), ThemeMode.entries.map { it to stringResource(themeLabel(it)) }, s.theme, { dialog = null }) { m -> set { it.copy(theme = m) } }
        "lang" -> ChoiceDialog(stringResource(R.string.language), listOf("", "ar", "en").map { it to stringResource(langLabel(it)) }, s.language, { dialog = null }) { l ->
            set { it.copy(language = l) }
            AppCompatDelegate.setApplicationLocales(if (l.isBlank()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(l))
        }
        "limit" -> SpeedLimitDialog(s.globalLimitBps, { dialog = null }) { v -> set { it.copy(globalLimitBps = v) }; dialog = null }
        "tile" -> androidx.compose.material3.AlertDialog(
            onDismissRequest = { dialog = null },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(android.R.string.ok)) } },
            title = { Text(stringResource(R.string.tile_add)) },
            text = { Text(stringResource(R.string.tile_add_manual)) },
        )
    }
}

private fun accentName(a: Accent) = when (a) {
    Accent.MINT -> R.string.accent_mint; Accent.BLUE -> R.string.accent_blue; Accent.VIOLET -> R.string.accent_violet
    Accent.AMBER -> R.string.accent_amber; Accent.ROSE -> R.string.accent_rose
}

/** الإعدادات المتقدمة: المحرك والشبكة والشروط والأتمتة والتورنت والمتصفح. */
@Composable
fun AdvancedSettingsScreen(vm: MainViewModel, padding: PaddingValues, onBack: () -> Unit) {
    val s by vm.settings.collectAsState()
    fun set(f: (Settings) -> Settings) = vm.updateSettings(f)
    var dialog by remember { mutableStateOf<String?>(null) }

    LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp)) {
        item { SubTitle(stringResource(R.string.advanced_settings), stringResource(R.string.advanced_settings_desc), onBack) }

        item { SectionHeader(stringResource(R.string.sec_engine)) }
        item {
            Card(padding = PaddingValues(vertical = 4.dp)) {
                ToggleRow(stringResource(R.string.smart_tuning), stringResource(R.string.smart_tuning_desc), s.smartTuning) { v -> set { it.copy(smartTuning = v) } }
                Hairline()
                ToggleRow(stringResource(R.string.dynamic_split), stringResource(R.string.dynamic_split_desc), s.dynamicSplit) { v -> set { it.copy(dynamicSplit = v) } }
                Hairline()
                var r by remember(s.maxRetries) { mutableFloatStateOf(s.maxRetries.toFloat()) }
                SliderRow(stringResource(R.string.max_retries), stringResource(R.string.max_retries_desc), r, 0f..30f, 29, Fmt.iso(r.toInt().toString()), { r = it }) { set { it.copy(maxRetries = r.toInt()) } }
                Hairline()
                var d by remember(s.retryDelaySec) { mutableFloatStateOf(s.retryDelaySec.toFloat()) }
                SliderRow(stringResource(R.string.retry_delay), stringResource(R.string.retry_delay_desc), d, 1f..30f, 28, Fmt.duration(d.toLong()), { d = it }) { set { it.copy(retryDelaySec = d.toInt()) } }
                Hairline()
                SettingRow(stringResource(R.string.user_agent), stringResource(R.string.user_agent_desc), value = s.userAgent.ifBlank { stringResource(R.string.default_value) }, onClick = { dialog = "ua" })
                Hairline()
                SettingRow(stringResource(R.string.proxy), stringResource(R.string.proxy_desc),
                    value = when (s.proxyMode) { ProxyMode.NONE -> stringResource(R.string.proxy_none); else -> "${s.proxyMode.name} · ${Fmt.iso(s.proxyHost + ":" + s.proxyPort)}" },
                    onClick = { dialog = "proxy" })
            }
        }

        item { SectionHeader(stringResource(R.string.sec_mobile)) }
        item {
            Card(padding = PaddingValues(vertical = 4.dp)) {
                ToggleRow(stringResource(R.string.separate_mobile), stringResource(R.string.separate_mobile_desc), s.separateMobile) { v -> set { it.copy(separateMobile = v) } }
                if (s.separateMobile) {
                    Hairline()
                    var mc by remember(s.mobileMaxConnections) { mutableFloatStateOf(s.mobileMaxConnections.toFloat()) }
                    SliderRow(stringResource(R.string.max_connections), stringResource(R.string.mobile_only), mc, 1f..32f, 30, Fmt.iso(mc.toInt().toString()), { mc = it }) { set { it.copy(mobileMaxConnections = mc.toInt()) } }
                    var mp by remember(s.mobileParallel) { mutableFloatStateOf(s.mobileParallel.toFloat()) }
                    SliderRow(stringResource(R.string.max_parallel), stringResource(R.string.mobile_only), mp, 1f..10f, 8, Fmt.iso(mp.toInt().toString()), { mp = it }) { set { it.copy(mobileParallel = mp.toInt()) } }
                    SettingRow(stringResource(R.string.global_limit), stringResource(R.string.mobile_only),
                        value = if (s.mobileLimitBps > 0) Fmt.speed(s.mobileLimitBps) else stringResource(R.string.unlimited), onClick = { dialog = "mlimit" })
                }
                Hairline()
                SettingRow(stringResource(R.string.data_limits), stringResource(R.string.data_limits_desc),
                    value = "${stringResource(R.string.daily)}: ${if (s.dailyLimitBytes > 0) Fmt.bytes(s.dailyLimitBytes) else stringResource(R.string.unlimited)} · ${stringResource(R.string.monthly)}: ${if (s.monthlyLimitBytes > 0) Fmt.bytes(s.monthlyLimitBytes) else stringResource(R.string.unlimited)}",
                    onClick = { dialog = "data" })
            }
        }

        item { SectionHeader(stringResource(R.string.sec_conditions)) }
        item {
            Card(padding = PaddingValues(vertical = 4.dp)) {
                ToggleRow(stringResource(R.string.only_charging), stringResource(R.string.only_charging_desc), s.onlyWhileCharging) { v -> set { it.copy(onlyWhileCharging = v) } }
                Hairline()
                var bat by remember(s.pauseOnLowBattery) { mutableFloatStateOf(s.pauseOnLowBattery.toFloat()) }
                SliderRow(stringResource(R.string.low_battery), stringResource(R.string.low_battery_desc), bat, 0f..50f, 9,
                    if (bat.toInt() == 0) stringResource(R.string.off) else Fmt.iso("${bat.toInt()}%"), { bat = it }) { set { it.copy(pauseOnLowBattery = bat.toInt()) } }
                Hairline()
                ToggleRow(stringResource(R.string.pause_hot), stringResource(R.string.pause_hot_desc), s.pauseWhenHot) { v -> set { it.copy(pauseWhenHot = v) } }
                Hairline()
                var free by remember(s.minFreeMb) { mutableFloatStateOf((s.minFreeMb / 100).toFloat()) }
                SliderRow(stringResource(R.string.min_free), stringResource(R.string.min_free_desc), free, 0f..50f, 49,
                    if (free.toInt() == 0) stringResource(R.string.off) else Fmt.bytes(free.toLong() * 100 * 1024 * 1024), { free = it }) { set { it.copy(minFreeMb = free.toLong() * 100) } }
            }
        }

        item { SectionHeader(stringResource(R.string.sec_automation)) }
        item {
            Card(padding = PaddingValues(vertical = 4.dp)) {
                ToggleRow(stringResource(R.string.window), sr(R.string.window_desc, s.windowStart, s.windowEnd), s.windowEnabled) { v -> set { it.copy(windowEnabled = v) } }
                if (s.windowEnabled) {
                    var ws by remember(s.windowStart) { mutableFloatStateOf(s.windowStart.toFloat()) }
                    var we by remember(s.windowEnd) { mutableFloatStateOf(s.windowEnd.toFloat()) }
                    SliderRow(stringResource(R.string.window_start), stringResource(R.string.window_hour_desc), ws, 0f..23f, 22, hours(ws.toInt()), { ws = it }) { set { it.copy(windowStart = ws.toInt()) } }
                    SliderRow(stringResource(R.string.window_end), stringResource(R.string.window_hour_desc), we, 0f..23f, 22, hours(we.toInt()), { we = it }) { set { it.copy(windowEnd = we.toInt()) } }
                }
                Hairline()
                ToggleRow(stringResource(R.string.auto_extract), stringResource(R.string.auto_extract_desc), s.autoExtract) { v -> set { it.copy(autoExtract = v) } }
            }
        }

        item { SectionHeader(stringResource(R.string.sec_torrent)) }
        item {
            Card(padding = PaddingValues(vertical = 4.dp)) {
                ToggleRow(stringResource(R.string.torrent_dht), stringResource(R.string.torrent_dht_desc), s.torrentDht) { v -> set { it.copy(torrentDht = v) } }
                Hairline()
                SettingRow(stringResource(R.string.torrent_up_limit), stringResource(R.string.torrent_up_limit_desc),
                    value = if (s.torrentUpLimit > 0) Fmt.speed(s.torrentUpLimit.toLong()) else stringResource(R.string.unlimited), onClick = { dialog = "tup" })
                Hairline()
                SettingRow(stringResource(R.string.torrent_down_limit), stringResource(R.string.torrent_down_limit_desc),
                    value = if (s.torrentDownLimit > 0) Fmt.speed(s.torrentDownLimit.toLong()) else stringResource(R.string.unlimited), onClick = { dialog = "tdown" })
                Hairline()
                ToggleRow(stringResource(R.string.torrent_seed_after), stringResource(R.string.torrent_seed_default_desc), s.torrentSeedDefault) { v -> set { it.copy(torrentSeedDefault = v) } }
                Hairline()
                var ratio by remember(s.torrentRatio) { mutableFloatStateOf(s.torrentRatio) }
                SliderRow(stringResource(R.string.torrent_ratio), stringResource(R.string.torrent_ratio_desc), ratio, 0.5f..5f, 8,
                    Fmt.iso(String.format(java.util.Locale.ROOT, "%.1f", ratio)), { ratio = it }) { set { it.copy(torrentRatio = ratio) } }
            }
        }

        item { SectionHeader(stringResource(R.string.sec_browser)) }
        item {
            Card(padding = PaddingValues(vertical = 4.dp)) {
                ToggleRow(stringResource(R.string.block_popups), stringResource(R.string.block_popups_desc), s.blockPopups) { v -> set { it.copy(blockPopups = v) } }
                Hairline()
                ToggleRow(stringResource(R.string.block_trackers), stringResource(R.string.block_trackers_desc), s.blockTrackers) { v -> set { it.copy(blockTrackers = v) } }
                Hairline()
                ToggleRow(stringResource(R.string.desktop_mode), stringResource(R.string.desktop_mode_desc), s.desktopMode) { v -> set { it.copy(desktopMode = v) } }
            }
        }

        item { SectionHeader(stringResource(R.string.sec_files)) }
        item {
            Card(padding = PaddingValues(vertical = 4.dp)) {
                ToggleRow(stringResource(R.string.warn_exe), stringResource(R.string.warn_exe_desc), s.warnExecutables) { v -> set { it.copy(warnExecutables = v) } }
                Hairline()
                ToggleRow(stringResource(R.string.clipboard), stringResource(R.string.clipboard_desc), s.suggestClipboard) { v -> set { it.copy(suggestClipboard = v) } }
            }
        }
    }

    when (dialog) {
        "ua" -> TextDialog(stringResource(R.string.user_agent), s.userAgent, { dialog = null }) { v -> set { it.copy(userAgent = v.trim()) }; dialog = null }
        "proxy" -> ProxyDialog(s, vm.secrets.has(com.tadaffuq.app.core.SecretStore.PROXY_PASSWORD), { dialog = null }) { mode, host, port, user, pass ->
            set { it.copy(proxyMode = mode, proxyHost = host, proxyPort = port, proxyUser = user) }
            if (pass != null) vm.secrets.put(com.tadaffuq.app.core.SecretStore.PROXY_PASSWORD, pass)
            dialog = null
        }
        "mlimit" -> SpeedLimitDialog(s.mobileLimitBps, { dialog = null }) { v -> set { it.copy(mobileLimitBps = v) }; dialog = null }
        "data" -> DataLimitDialog(s, { dialog = null }) { d, m -> set { it.copy(dailyLimitBytes = d, monthlyLimitBytes = m) }; dialog = null }
        "tup" -> SpeedLimitDialog(s.torrentUpLimit.toLong(), { dialog = null }) { v -> set { it.copy(torrentUpLimit = v.toInt()) }; dialog = null }
        "tdown" -> SpeedLimitDialog(s.torrentDownLimit.toLong(), { dialog = null }) { v -> set { it.copy(torrentDownLimit = v.toInt()) }; dialog = null }
    }
}

/** صفحة «حول» بنمط سَيْل: الأيقونة والاسم والإصدار والوصف، وبطاقة المطوّر، وجدول المعلومات. */
@Composable
fun AboutScreen(padding: PaddingValues, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val accent = T.extra.chartLine
    LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp)) {
        item { SubTitle(stringResource(R.string.about_title), onBack = onBack) }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(96.dp).clip(RoundedCornerShape(26.dp)).background(Brush.linearGradient(listOf(Navy, accent))),
                    contentAlignment = Alignment.Center,
                ) { Image(painterResource(R.drawable.ic_launcher_fg), null, Modifier.size(96.dp)) }
                Spacer(Modifier.height(14.dp))
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall)
                Text(sr(R.string.version_x, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyMedium, color = T.extra.secondaryText)
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.about_text), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            }
        }
        item {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp),
                shape = RoundedCornerShape(28.dp), color = Color.Transparent,
            ) {
                Row(
                    Modifier.background(Brush.linearGradient(listOf(Navy, accent.copy(alpha = 0.95f)))).padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(54.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Person, null, tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.developer_line), style = MaterialTheme.typography.titleMedium, color = Color.White)
                        Text(stringResource(R.string.developer_dua), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.92f))
                    }
                }
            }
        }
        item {
            Card(padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                InfoRow(stringResource(R.string.about_build), Fmt.iso("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.GIT_SHA}"))
                Hairline()
                InfoRow(stringResource(R.string.about_engine), stringResource(R.string.about_engine_value))
                Hairline()
                InfoRow(stringResource(R.string.privacy), stringResource(R.string.about_privacy_value))
                Hairline()
                InfoRow(stringResource(R.string.about_source), "GitHub", onClick = {
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Hussein199800/tadaffuq")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                })
            }
        }
        item { SectionHeader(stringResource(R.string.privacy)) }
        item { Card { Text(stringResource(R.string.privacy_text), style = MaterialTheme.typography.bodyMedium) } }
        item { SectionHeader(stringResource(R.string.licenses)) }
        item { Card { Text(stringResource(R.string.licenses_text), style = MaterialTheme.typography.bodySmall) } }
    }
}

@Composable
private fun InfoRow(label: String, value: String, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = T.extra.secondaryText, modifier = Modifier.width(110.dp))
        Text(value, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), color = if (onClick != null) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface)
    }
}
