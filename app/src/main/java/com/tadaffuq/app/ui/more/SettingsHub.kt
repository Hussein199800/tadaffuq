package com.tadaffuq.app.ui.more

import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.tadaffuq.app.R
import com.tadaffuq.app.core.Fmt
import com.tadaffuq.app.core.sr
import com.tadaffuq.app.download.StorageDirs
import com.tadaffuq.app.download.TaskFiles
import com.tadaffuq.app.ui.MainViewModel
import com.tadaffuq.app.ui.components.Card
import com.tadaffuq.app.ui.components.ChoiceRow
import com.tadaffuq.app.ui.components.Hairline
import com.tadaffuq.app.ui.components.HowItWorks
import com.tadaffuq.app.ui.components.HubRow
import com.tadaffuq.app.ui.components.LargeTitle
import com.tadaffuq.app.ui.components.SectionHeader
import com.tadaffuq.app.ui.components.SettingRow
import com.tadaffuq.app.ui.components.Tint
import com.tadaffuq.app.ui.components.ToggleRow
import com.tadaffuq.app.ui.downloads.SpeedLimitDialog
import com.tadaffuq.app.ui.theme.T
import com.tadaffuq.app.ui.theme.accentPair
import com.tadaffuq.app.ui.theme.pairedAccent
import com.tadaffuq.app.ui.theme.paletteTones
import com.tadaffuq.data.Accent
import com.tadaffuq.data.ListDensity
import com.tadaffuq.data.Palette
import com.tadaffuq.data.Settings
import com.tadaffuq.data.TextSize
import com.tadaffuq.data.ThemeMode
import com.tadaffuq.engine.SpeedMode
import kotlinx.coroutines.launch

private enum class Page(val title: Int) {
    DOWNLOAD(R.string.hub_download), NETWORK(R.string.hub_network), STORAGE(R.string.hub_storage),
    NOTIFY(R.string.hub_notify), LOOK(R.string.hub_look),
}

/**
 * مركز الإعدادات: ستة مداخل بأيقونات ملونة وقيمة مختصرة، والتفاصيل عند الضغط.
 * الافتراضي ذكي؛ الشرح سطر واحد، والطويل خلف «كيف يعمل؟». «متقدمة» صفحة مستقلة.
 * @param navigate مسار شاشة أخرى («advanced»، «rules»، «backup»، «trash»).
 */
@Composable
fun SettingsScreen(vm: MainViewModel, padding: PaddingValues, onBack: () -> Unit, startPage: String? = null, navigate: (String) -> Unit) {
    val s by vm.settings.collectAsState()
    var page by rememberSaveable { mutableStateOf(startPage?.let { Page.valueOf(it) }) }
    BackHandler(enabled = page != null) { page = null }
    val p = page
    if (p == null) Hub(s, padding, onBack, navigate) { page = it }
    else SettingsPage(vm, s, p, padding, { page = null }, navigate)
}

@Composable
private fun Hub(s: Settings, padding: PaddingValues, onBack: () -> Unit, navigate: (String) -> Unit, open: (Page) -> Unit) {
    val ctx = LocalContext.current
    LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp)) {
        item { SubTitle(stringResource(R.string.settings), stringResource(R.string.set_hub_sub), onBack) }
        item {
            Card(padding = PaddingValues(vertical = 4.dp)) {
                HubRow(Icons.Rounded.Download, Tint.BLUE, stringResource(R.string.hub_download),
                    listOf(stringResource(modeLabel(s.mode)), sr(R.string.files_at_once_n, s.maxParallel),
                        if (s.globalLimitBps > 0) Fmt.speed(s.globalLimitBps) else stringResource(R.string.unlimited)).joinToString(" · ")) { open(Page.DOWNLOAD) }
                Hairline()
                HubRow(Icons.Rounded.Wifi, Tint.MINT, stringResource(R.string.hub_network),
                    listOfNotNull(stringResource(if (s.wifiOnly) R.string.wifi_only else R.string.all_networks),
                        stringResource(R.string.auto_resume).takeIf { s.autoResume }).joinToString(" · ")) { open(Page.NETWORK) }
                Hairline()
                HubRow(Icons.Rounded.Folder, Tint.AMBER, stringResource(R.string.hub_storage),
                    if (s.moveToTree.isBlank()) sr(R.string.default_folder, StorageDirs.ROOT) else TaskFiles.treeName(ctx, s.moveToTree)) { open(Page.STORAGE) }
                Hairline()
                HubRow(Icons.Rounded.NotificationsActive, Tint.CORAL, stringResource(R.string.hub_notify),
                    listOfNotNull(stringResource(R.string.notify_per_task).takeIf { s.notifyPerTask }, stringResource(R.string.tile_label)).joinToString(" · ")) { open(Page.NOTIFY) }
                Hairline()
                HubRow(Icons.Rounded.Palette, Tint.VIOLET, stringResource(R.string.hub_look),
                    listOf(stringResource(paletteName(s.palette)), stringResource(themeLabel(s.theme)), stringResource(textSizeName(s.textSize))).joinToString(" · ")) { open(Page.LOOK) }
                Hairline()
                HubRow(Icons.Rounded.Tune, Tint.SLATE, stringResource(R.string.hub_advanced), stringResource(R.string.hub_advanced_v)) { navigate("advanced") }
            }
        }
    }
}

@Composable
private fun SettingsPage(vm: MainViewModel, s: Settings, page: Page, padding: PaddingValues, back: () -> Unit, navigate: (String) -> Unit) {
    fun set(f: (Settings) -> Settings) = vm.updateSettings(f)
    var dialog by remember { mutableStateOf<String?>(null) }
    LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp)) {
        item { SubTitle(stringResource(page.title), onBack = back) }
        when (page) {
            Page.DOWNLOAD -> downloadPage(s, ::set) { dialog = it }
            Page.NETWORK -> networkPage(s, ::set) { dialog = it }
            Page.STORAGE -> storagePage(vm, s, ::set, navigate)
            Page.NOTIFY -> notifyPage(vm, s, ::set) { dialog = it }
            Page.LOOK -> lookPage(s, ::set) { dialog = it }
        }
    }
    when (dialog) {
        "limit" -> SpeedLimitDialog(s.globalLimitBps, { dialog = null }) { v -> set { it.copy(globalLimitBps = v) }; dialog = null }
        "mlimit" -> SpeedLimitDialog(s.mobileLimitBps, { dialog = null }) { v -> set { it.copy(mobileLimitBps = v) }; dialog = null }
        "data" -> DataLimitDialog(s, { dialog = null }) { d, m -> set { it.copy(dailyLimitBytes = d, monthlyLimitBytes = m) }; dialog = null }
        "lang" -> ChoiceDialog(stringResource(R.string.language), listOf("", "ar", "en").map { it to stringResource(langLabel(it)) }, s.language, { dialog = null }) { l ->
            set { it.copy(language = l) }
            AppCompatDelegate.setApplicationLocales(if (l.isBlank()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(l))
        }
        "tile" -> AlertDialog(
            onDismissRequest = { dialog = null },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(android.R.string.ok)) } },
            title = { Text(stringResource(R.string.tile_add)) },
            text = { Text(stringResource(R.string.tile_add_manual)) },
        )
    }
}

private fun <T> withCurrent(base: List<T>, cur: T) = if (cur in base) base else (base + cur)

private fun LazyListScope.downloadPage(s: Settings, set: ((Settings) -> Settings) -> Unit, dialog: (String) -> Unit) {
    item {
        Card(padding = PaddingValues(vertical = 4.dp)) {
            ChoiceRow(stringResource(R.string.mode_simple), stringResource(modeDesc(s.mode)),
                SpeedMode.entries.map { it to stringResource(modeLabel(it)) }, s.mode) { m -> set { it.copy(mode = m) } }
            Hairline()
            ChoiceRow(stringResource(R.string.parallel_simple), stringResource(R.string.parallel_simple_desc),
                withCurrent(listOf(1, 2, 3, 5), s.maxParallel).sorted().map { it to Fmt.iso(it.toString()) }, s.maxParallel) { n -> set { it.copy(maxParallel = n) } }
            Hairline()
            SettingRow(stringResource(R.string.limit_simple), stringResource(R.string.limit_simple_desc),
                value = if (s.globalLimitBps > 0) Fmt.speed(s.globalLimitBps) else stringResource(R.string.unlimited), onClick = { dialog("limit") })
            Hairline()
            ChoiceRow(stringResource(R.string.conn_simple), stringResource(R.string.conn_simple_desc),
                withCurrent(listOf(2, 4, 8, 16, 32), s.maxConnections).sorted().map { it to Fmt.iso(it.toString()) }, s.maxConnections) { n -> set { it.copy(maxConnections = n) } }
            HowItWorks(stringResource(R.string.conn_how))
        }
    }
}

private fun LazyListScope.networkPage(s: Settings, set: ((Settings) -> Settings) -> Unit, dialog: (String) -> Unit) {
    item {
        Card(padding = PaddingValues(vertical = 4.dp)) {
            ToggleRow(stringResource(R.string.wifi_only), stringResource(R.string.wifi_only_desc), s.wifiOnly) { v -> set { it.copy(wifiOnly = v) } }
            Hairline()
            ToggleRow(stringResource(R.string.auto_resume), stringResource(R.string.auto_resume_desc), s.autoResume) { v -> set { it.copy(autoResume = v) } }
            Hairline()
            SettingRow(stringResource(R.string.data_limits), stringResource(R.string.data_limits_desc),
                value = "${stringResource(R.string.daily)}: ${if (s.dailyLimitBytes > 0) Fmt.bytes(s.dailyLimitBytes) else stringResource(R.string.unlimited)} · ${stringResource(R.string.monthly)}: ${if (s.monthlyLimitBytes > 0) Fmt.bytes(s.monthlyLimitBytes) else stringResource(R.string.unlimited)}",
                onClick = { dialog("data") })
            Hairline()
            ToggleRow(stringResource(R.string.separate_mobile), stringResource(R.string.separate_mobile_desc), s.separateMobile) { v -> set { it.copy(separateMobile = v) } }
            if (s.separateMobile) {
                ChoiceRow(stringResource(R.string.conn_simple), stringResource(R.string.mobile_only),
                    withCurrent(listOf(1, 2, 4, 8), s.mobileMaxConnections).sorted().map { it to Fmt.iso(it.toString()) }, s.mobileMaxConnections) { n -> set { it.copy(mobileMaxConnections = n) } }
                ChoiceRow(stringResource(R.string.parallel_simple), stringResource(R.string.mobile_only),
                    withCurrent(listOf(1, 2, 3), s.mobileParallel).sorted().map { it to Fmt.iso(it.toString()) }, s.mobileParallel) { n -> set { it.copy(mobileParallel = n) } }
                SettingRow(stringResource(R.string.limit_simple), stringResource(R.string.mobile_only),
                    value = if (s.mobileLimitBps > 0) Fmt.speed(s.mobileLimitBps) else stringResource(R.string.unlimited), onClick = { dialog("mlimit") })
            }
        }
    }
    item { SectionHeader(stringResource(R.string.sec_schedule)) }
    item {
        Card(padding = PaddingValues(vertical = 4.dp)) {
            ToggleRow(stringResource(R.string.window), sr(R.string.window_desc, s.windowStart, s.windowEnd), s.windowEnabled) { v -> set { it.copy(windowEnabled = v) } }
            if (s.windowEnabled) {
                ChoiceRow(stringResource(R.string.window_start), null, withCurrent(listOf(0, 1, 2, 22, 23), s.windowStart).sorted().map { it to hourLabel(it) }, s.windowStart) { h -> set { it.copy(windowStart = h) } }
                ChoiceRow(stringResource(R.string.window_end), null, withCurrent(listOf(6, 7, 8, 9), s.windowEnd).sorted().map { it to hourLabel(it) }, s.windowEnd) { h -> set { it.copy(windowEnd = h) } }
            }
            Hairline()
            ToggleRow(stringResource(R.string.only_charging), stringResource(R.string.only_charging_desc), s.onlyWhileCharging) { v -> set { it.copy(onlyWhileCharging = v) } }
            Hairline()
            ChoiceRow(stringResource(R.string.low_battery_chips), null,
                withCurrent(listOf(0, 15, 25), s.pauseOnLowBattery).sorted().map { it to (if (it == 0) stringResource(R.string.off) else Fmt.iso("$it%")) }, s.pauseOnLowBattery) { b -> set { it.copy(pauseOnLowBattery = b) } }
            Hairline()
            ToggleRow(stringResource(R.string.pause_hot), stringResource(R.string.pause_hot_desc), s.pauseWhenHot) { v -> set { it.copy(pauseWhenHot = v) } }
            Hairline()
            ChoiceRow(stringResource(R.string.min_free), stringResource(R.string.min_free_desc),
                withCurrent(listOf(0L, 500L, 1000L, 2000L), s.minFreeMb).sorted().map { it to (if (it == 0L) stringResource(R.string.off) else Fmt.bytes(it * 1024 * 1024)) }, s.minFreeMb) { m -> set { it.copy(minFreeMb = m) } }
        }
    }
}

private fun hourLabel(h: Int) = Fmt.iso("%02d:00".format(java.util.Locale.ROOT, h))

private fun LazyListScope.storagePage(vm: MainViewModel, s: Settings, set: ((Settings) -> Settings) -> Unit, navigate: (String) -> Unit) {
    item {
        val ctx = LocalContext.current
        val tree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                set { it.copy(moveToTree = uri.toString()) }
            }
        }
        Card(padding = PaddingValues(vertical = 4.dp)) {
            SettingRow(stringResource(R.string.save_folder), stringResource(R.string.save_folder_desc),
                value = if (s.moveToTree.isBlank()) sr(R.string.default_folder, StorageDirs.ROOT) else TaskFiles.treeName(ctx, s.moveToTree),
                onClick = { tree.launch(null) }) {
                if (s.moveToTree.isNotBlank()) TextButton(onClick = { set { it.copy(moveToTree = "") } }) { Text(stringResource(R.string.reset_default)) }
            }
            if (StorageDirs.usingFallback) Text(stringResource(R.string.storage_fallback), style = MaterialTheme.typography.bodySmall, color = T.extra.warning, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            Hairline()
            ToggleRow(stringResource(R.string.by_category), sr(R.string.by_category_desc, StorageDirs.ROOT), s.sortByCategory) { v -> set { it.copy(sortByCategory = v) } }
            Hairline()
            ToggleRow(stringResource(R.string.auto_extract), stringResource(R.string.auto_extract_desc), s.autoExtract) { v -> set { it.copy(autoExtract = v) } }
            Hairline()
            ToggleRow(stringResource(R.string.warn_exe), stringResource(R.string.warn_exe_desc), s.warnExecutables) { v -> set { it.copy(warnExecutables = v) } }
        }
    }
    item { SectionHeader(stringResource(R.string.sec_files)) }
    item {
        Card(padding = PaddingValues(vertical = 4.dp)) {
            SettingRow(stringResource(R.string.rules), stringResource(R.string.rules_desc), onClick = { navigate("rules") })
            Hairline()
            OrphansRow(vm)
            Hairline()
            SettingRow(stringResource(R.string.trash), stringResource(R.string.trash_desc), onClick = { navigate("trash") })
            Hairline()
            SettingRow(stringResource(R.string.backup), stringResource(R.string.backup_desc), onClick = { navigate("backup") })
        }
    }
}

/** الملفات المؤقتة اليتيمة: يُعرض الحجم قبل الحذف، ويُطلب تأكيد، ويُعاد الفحص لحظة الحذف. */
@Composable
private fun OrphansRow(vm: MainViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var found by remember { mutableStateOf<List<java.io.File>?>(null) }
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { found = vm.manager.orphanParts() }
    val list = found
    val size = list?.sumOf { it.length() } ?: 0
    SettingRow(stringResource(R.string.orphans), stringResource(R.string.orphans_desc),
        value = when {
            list == null -> "…"
            list.isEmpty() -> stringResource(R.string.orphans_none)
            else -> sr(R.string.orphans_found, Fmt.bytes(size), com.tadaffuq.app.core.formatLatin(ctx.resources.getQuantityString(R.plurals.files_n, list.size), arrayOf(list.size)))
        }) {
        if (!list.isNullOrEmpty()) TextButton(onClick = { confirm = true }) { Text(stringResource(R.string.orphans_clean)) }
    }
    if (confirm && list != null) AlertDialog(
        onDismissRequest = { confirm = false },
        confirmButton = {
            TextButton(onClick = {
                confirm = false
                scope.launch {
                    vm.manager.cleanOrphans(list)
                    android.widget.Toast.makeText(ctx, ctx.getString(R.string.orphans_done, Fmt.bytes(size)), android.widget.Toast.LENGTH_SHORT).show()
                    found = vm.manager.orphanParts()
                }
            }) { Text(stringResource(R.string.orphans_clean), color = T.extra.danger) }
        },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(android.R.string.cancel)) } },
        text = { Text(sr(R.string.orphans_confirm, Fmt.bytes(size))) },
    )
}

private fun LazyListScope.notifyPage(vm: MainViewModel, s: Settings, set: ((Settings) -> Settings) -> Unit, dialog: (String) -> Unit) {
    item {
        val ctx = LocalContext.current
        Card(padding = PaddingValues(vertical = 4.dp)) {
            SettingRow(stringResource(R.string.tile_add), stringResource(R.string.tile_simple_desc), onClick = {
                if (!com.tadaffuq.app.download.TileRequest.request(ctx) { ok -> if (ok) android.widget.Toast.makeText(ctx, R.string.tile_add_done, android.widget.Toast.LENGTH_SHORT).show() }) dialog("tile")
            })
            Hairline()
            ToggleRow(stringResource(R.string.clipboard), stringResource(R.string.clipboard_desc), s.suggestClipboard) { v -> set { it.copy(suggestClipboard = v) } }
        }
    }
    item { SectionHeader(stringResource(R.string.sec_notifications)) }
    item {
        val ctx = LocalContext.current
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
}

private fun LazyListScope.lookPage(s: Settings, set: ((Settings) -> Settings) -> Unit, dialog: (String) -> Unit) {
    item {
        Card(padding = PaddingValues(16.dp)) {
            Text(stringResource(R.string.palette_title), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(10.dp))
            // معاينة مباشرة لكل ثيم: بطاقة تنزيل مصغرة بألوانه الحقيقية
            Palette.entries.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { p -> PaletteCard(p, s.palette == p, Modifier.weight(1f)) { set { it.copy(palette = p, accent = pairedAccent(p), dynamicColor = false) } } }
                }
            }
        }
    }
    item {
        Card(padding = PaddingValues(vertical = 4.dp)) {
            ChoiceRow(stringResource(R.string.theme_mode), null, ThemeMode.entries.map { it to stringResource(themeLabel(it)) }, s.theme) { m -> set { it.copy(theme = m) } }
            Hairline()
            ChoiceRow(stringResource(R.string.text_size), stringResource(R.string.text_size_desc), TextSize.entries.map { it to stringResource(textSizeName(it)) }, s.textSize) { t -> set { it.copy(textSize = t) } }
            Hairline()
            ChoiceRow(stringResource(R.string.density), null, listOf(ListDensity.COMFORTABLE to stringResource(R.string.density_comfy), ListDensity.COMPACT to stringResource(R.string.density_compact)), s.density) { d -> set { it.copy(density = d) } }
            Hairline()
            if (Build.VERSION.SDK_INT >= 31) {
                ToggleRow(stringResource(R.string.dynamic_color), stringResource(R.string.dynamic_color_desc), s.dynamicColor) { v -> set { it.copy(dynamicColor = v) } }
                Hairline()
            }
            if (!s.dynamicColor || Build.VERSION.SDK_INT < 31) {
                AccentRow(s.accent) { a -> set { it.copy(accent = a) } }
                Hairline()
            }
            ToggleRow(stringResource(R.string.live_scene), stringResource(R.string.live_scene_desc), s.showLiveScene) { v -> set { it.copy(showLiveScene = v) } }
            Hairline()
            ToggleRow(stringResource(R.string.segments_in_list), stringResource(R.string.segments_in_list_desc), s.showSegmentsInList) { v -> set { it.copy(showSegmentsInList = v) } }
            Hairline()
            ToggleRow(stringResource(R.string.reduce_motion), stringResource(R.string.reduce_motion_desc), s.reduceMotion) { v -> set { it.copy(reduceMotion = v) } }
            Hairline()
            SettingRow(stringResource(R.string.language), stringResource(R.string.language_desc), value = stringResource(langLabel(s.language)), onClick = { dialog("lang") })
        }
    }
    item {
        val d = Settings()
        TextButton(onClick = {
            set { it.copy(palette = d.palette, accent = d.accent, theme = d.theme, textSize = d.textSize, density = d.density, dynamicColor = d.dynamicColor,
                showLiveScene = d.showLiveScene, showSegmentsInList = d.showSegmentsInList, reduceMotion = d.reduceMotion) }
        }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { Text(stringResource(R.string.reset_look)) }
    }
}

/** بطاقة ثيم بمعاينة: خلفيته وسطحه ونصه وشريط تقدم بلون تمييزه المقترن. */
@Composable
private fun PaletteCard(p: Palette, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val dark = MaterialTheme.colorScheme.background.let { (it.red + it.green + it.blue) / 3f < 0.5f }
    val t = paletteTones(p, dark)
    val (bright, deep) = accentPair(pairedAccent(p))
    val accent = if (dark) bright else deep
    val name = stringResource(paletteName(p))
    Column(
        modifier.clip(RoundedCornerShape(18.dp))
            .border(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else T.extra.hairline, RoundedCornerShape(18.dp))
            .clickable(role = Role.RadioButton, onClickLabel = name, onClick = onClick)
            .semantics { this.selected = selected }
            .background(t.background).padding(10.dp),
    ) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(t.card).padding(8.dp)) {
            Text(Fmt.iso(stringResource(R.string.preview_file)), style = MaterialTheme.typography.labelMedium, color = t.onSurface, maxLines = 1)
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(t.hairline)) {
                Box(Modifier.fillMaxWidth(0.62f).height(6.dp).clip(CircleShape).background(accent))
            }
            Spacer(Modifier.height(4.dp))
            Text(Fmt.iso("62% · 8.4 MB/s"), style = MaterialTheme.typography.labelSmall, color = t.secondaryText)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(14.dp).clip(CircleShape).background(Brush.linearGradient(listOf(t.primary, accent))))
            Spacer(Modifier.width(6.dp))
            Text(name, style = MaterialTheme.typography.labelLarge, color = t.onSurface, textAlign = TextAlign.Start)
        }
    }
}

@Composable
private fun AccentRow(current: Accent, onPick: (Accent) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(stringResource(R.string.accent), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.accent_desc), style = MaterialTheme.typography.bodySmall, color = T.extra.secondaryText)
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Accent.entries.forEach { a ->
                val (bright, deep) = accentPair(a)
                val selected = current == a
                Box(
                    Modifier.size(48.dp).clip(CircleShape)
                        .clickable(role = Role.RadioButton, onClickLabel = stringResource(accentName(a))) { onPick(a) }
                        .semantics { this.selected = selected },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(if (selected) 40.dp else 32.dp).clip(CircleShape).background(Brush.linearGradient(listOf(bright, deep))), contentAlignment = Alignment.Center) {
                        if (selected) Box(Modifier.size(12.dp).clip(CircleShape).background(Color.White))
                    }
                }
            }
        }
    }
}

internal fun paletteName(p: Palette) = when (p) {
    Palette.NAVY_MINT -> R.string.palette_navy; Palette.GREEN_GOLD -> R.string.palette_green
    Palette.ORANGE_VIOLET -> R.string.palette_orange; Palette.IVORY_BLACK -> R.string.palette_ivory
}

internal fun textSizeName(t: TextSize) = when (t) { TextSize.SMALL -> R.string.text_small; TextSize.DEFAULT -> R.string.text_default; TextSize.LARGE -> R.string.text_large }
