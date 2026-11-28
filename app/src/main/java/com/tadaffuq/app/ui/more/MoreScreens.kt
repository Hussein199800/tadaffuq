package com.tadaffuq.app.ui.more

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import com.tadaffuq.app.core.sr
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Rule
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.tadaffuq.app.BuildConfig
import com.tadaffuq.app.R
import com.tadaffuq.app.core.Fmt
import com.tadaffuq.app.core.sr
import com.tadaffuq.app.download.StorageDirs
import com.tadaffuq.app.ui.MainViewModel
import com.tadaffuq.app.ui.add.AddRequest
import com.tadaffuq.app.ui.components.Card
import com.tadaffuq.app.ui.components.EmptyState
import com.tadaffuq.app.ui.components.Hairline
import com.tadaffuq.app.ui.components.LargeTitle
import com.tadaffuq.app.ui.components.SectionHeader
import com.tadaffuq.app.ui.components.SettingRow
import com.tadaffuq.app.ui.components.SliderRow
import com.tadaffuq.app.ui.components.StatTile
import com.tadaffuq.app.ui.components.ToggleRow
import com.tadaffuq.app.ui.files.categoryLabel
import com.tadaffuq.app.ui.theme.T
import com.tadaffuq.data.Settings
import com.tadaffuq.data.ProxyMode
import com.tadaffuq.data.TaskStatus
import com.tadaffuq.data.ThemeMode
import com.tadaffuq.engine.FileNames
import com.tadaffuq.engine.LinkTools
import com.tadaffuq.engine.SpeedMode
import kotlinx.coroutines.launch
import java.util.Calendar

@Composable
fun MoreScreen(padding: PaddingValues, about: @Composable () -> Unit = {}, initialTab: Int = 0, open: (String) -> Unit) {
    // ثلاثة أقسام بدل قائمة طويلة (مثل «سَيْل»): ما يُضبط مرة، وما يُستعمل، ومعلومات التطبيق
    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(initialTab) }
    Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
        LargeTitle(stringResource(R.string.tab_more))
        MoreTabs(tab) { tab = it }
        androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
            when (tab) {
                2 -> about()
                else -> LazyColumn(
                    contentPadding = PaddingValues(top = 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val rows = if (tab == 0) listOf(
                        MoreEntry(R.string.settings, R.string.settings_desc, Icons.Rounded.Settings, com.tadaffuq.app.ui.components.Tint.BLUE, "settings"),
                        MoreEntry(R.string.rules, R.string.rules_desc, Icons.Rounded.Rule, com.tadaffuq.app.ui.components.Tint.AMBER, "rules"),
                        MoreEntry(R.string.backup, R.string.backup_desc, Icons.Rounded.Backup, com.tadaffuq.app.ui.components.Tint.MINT, "backup"),
                        MoreEntry(R.string.rpc, R.string.rpc_desc, Icons.Rounded.Lan, com.tadaffuq.app.ui.components.Tint.VIOLET, "rpc"),
                    ) else listOf(
                        MoreEntry(R.string.reports, R.string.reports_desc, Icons.Rounded.BarChart, com.tadaffuq.app.ui.components.Tint.VIOLET, "reports"),
                        MoreEntry(R.string.link_hub, R.string.link_hub_desc, Icons.Rounded.Link, com.tadaffuq.app.ui.components.Tint.MINT, "links"),
                        MoreEntry(R.string.vault, R.string.vault_desc, Icons.Rounded.Lock, com.tadaffuq.app.ui.components.Tint.CORAL, "vault"),
                        MoreEntry(R.string.trash, R.string.trash_desc, Icons.Rounded.DeleteOutline, com.tadaffuq.app.ui.components.Tint.AMBER, "trash"),
                        MoreEntry(R.string.dev, R.string.dev_desc, Icons.Rounded.Code, com.tadaffuq.app.ui.components.Tint.SLATE, "dev"),
                    )
                    items(rows.size) { k ->
                        val e = rows[k]
                        Card(padding = PaddingValues(vertical = 2.dp)) {
                            SettingRow(stringResource(e.title), stringResource(e.desc), e.icon, tint = e.tint, onClick = { open(e.route) }, trailing = {
                                // سهم يتبع اتجاه الكتابة (يشير يساراً في العربية)
                                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            })
                        }
                    }
                }
            }
        }
    }
}

private data class MoreEntry(val title: Int, val desc: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector, val tint: com.tadaffuq.app.ui.components.Tint, val route: String)

/** شريط أقسام بحبة للمحدد (يلتف النص ولا يُقص، ومنطقة لمس 48dp). */
@Composable
private fun MoreTabs(selected: Int, onSelect: (Int) -> Unit) {
    val labels = listOf(R.string.more_tab_settings to Icons.Rounded.Settings, R.string.more_tab_tools to Icons.Rounded.Build, R.string.more_tab_about to Icons.Rounded.Info)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        labels.forEachIndexed { i, (label, icon) ->
            val on = i == selected
            val bg by androidx.compose.animation.animateColorAsState(if (on) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent, label = "tab")
            val fg = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            Row(
                Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).background(bg)
                    .selectable(selected = on, role = androidx.compose.ui.semantics.Role.Tab, onClick = { onSelect(i) }).padding(horizontal = 6.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(label), color = fg, style = MaterialTheme.typography.labelLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

@Composable
fun SubTitle(title: String, subtitle: String? = null, onBack: () -> Unit) {
    Row(Modifier.padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) }
    }
    LargeTitle(title, subtitle)
}

internal fun modeLabel(m: SpeedMode) = when (m) { SpeedMode.NORMAL -> R.string.mode_normal; SpeedMode.MAX -> R.string.mode_max; SpeedMode.WEAK_NETWORK -> R.string.mode_weak; SpeedMode.SAVER -> R.string.mode_saver }
internal fun modeDesc(m: SpeedMode) = when (m) { SpeedMode.NORMAL -> R.string.mode_normal_desc; SpeedMode.MAX -> R.string.mode_max_desc; SpeedMode.WEAK_NETWORK -> R.string.mode_weak_desc; SpeedMode.SAVER -> R.string.mode_saver_desc }
internal fun themeLabel(t: ThemeMode) = when (t) { ThemeMode.SYSTEM -> R.string.theme_system; ThemeMode.LIGHT -> R.string.theme_light; ThemeMode.DARK -> R.string.theme_dark }
internal fun langLabel(l: String) = when (l) { "ar" -> R.string.lang_ar; "en" -> R.string.lang_en; else -> R.string.lang_system }

@Composable
internal fun <T> ChoiceDialog(title: String, options: List<Pair<T, String>>, current: T, onDismiss: () -> Unit, onPick: (T) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (v, label) ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(v); onDismiss() }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.RadioButton(v == current, { onPick(v); onDismiss() })
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
internal fun TextDialog(title: String, value: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(value) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, maxLines = 4, supportingText = { Text(stringResource(R.string.empty_default)) }) },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
internal fun DataLimitDialog(s: Settings, onDismiss: () -> Unit, onSave: (Long, Long) -> Unit) {
    val mb = 1024L * 1024
    var daily by remember { mutableStateOf(if (s.dailyLimitBytes > 0) (s.dailyLimitBytes / mb).toString() else "") }
    var monthly by remember { mutableStateOf(if (s.monthlyLimitBytes > 0) (s.monthlyLimitBytes / mb).toString() else "") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.data_limits)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.data_limits_dialog), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(daily, { daily = it }, label = { Text(stringResource(R.string.daily_mb)) }, singleLine = true)
                OutlinedTextField(monthly, { monthly = it }, label = { Text(stringResource(R.string.monthly_mb)) }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(((Fmt.parseNumber(daily) ?: 0.0) * mb).toLong(), ((Fmt.parseNumber(monthly) ?: 0.0) * mb).toLong()) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
internal fun ProxyDialog(s: Settings, hasPassword: Boolean, onDismiss: () -> Unit, onSave: (ProxyMode, String, Int, String, String?) -> Unit) {
    var mode by remember { mutableStateOf(s.proxyMode) }
    var host by remember { mutableStateOf(s.proxyHost) }
    var port by remember { mutableStateOf(if (s.proxyPort > 0) s.proxyPort.toString() else "") }
    var user by remember { mutableStateOf(s.proxyUser) }
    var pass by remember { mutableStateOf<String?>(null) }
    val portNum = Fmt.parseNumber(port)?.toInt() ?: 0
    val valid = mode == ProxyMode.NONE || (host.isNotBlank() && portNum in 1..65535)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.proxy)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ProxyMode.entries.forEach { m -> FilterChip(mode == m, { mode = m }, label = { Text(if (m == ProxyMode.NONE) stringResource(R.string.proxy_none) else m.name) }) }
                }
                if (mode != ProxyMode.NONE) {
                    OutlinedTextField(host, { host = it.trim() }, label = { Text(stringResource(R.string.proxy_host)) }, singleLine = true)
                    OutlinedTextField(port, { port = it.trim() }, label = { Text(stringResource(R.string.proxy_port)) }, singleLine = true)
                    OutlinedTextField(user, { user = it.trim() }, label = { Text(stringResource(R.string.proxy_user)) }, singleLine = true)
                    OutlinedTextField(pass ?: "", { pass = it }, label = { Text(stringResource(R.string.proxy_password)) }, singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        supportingText = { Text(stringResource(if (hasPassword && pass == null) R.string.secret_saved else R.string.secret_desc)) })
                    if (mode == ProxyMode.SOCKS5) Text(stringResource(R.string.socks_note), style = MaterialTheme.typography.bodySmall, color = T.extra.secondaryText)
                }
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onSave(mode, host, portNum, user, pass) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

// ===== التقارير =====

@Composable
fun ReportsScreen(vm: MainViewModel, padding: PaddingValues, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val usage by vm.usage.collectAsState()
    val tasks by vm.tasks.collectAsState()
    val scope = rememberCoroutineScope()
    var period by rememberSaveable { mutableStateOf(1) }
    val cal = Calendar.getInstance()
    fun daysAgo(n: Int) = Fmt.day(cal.timeInMillis - n * 86_400_000L)
    val from = when (period) { 0 -> daysAgo(0); 1 -> daysAgo(6); 2 -> daysAgo(29); else -> daysAgo(364) }
    val p = vm.period(usage, from)
    val done = tasks.filter { it.createdAt >= java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).parse(from)!!.time }
    val completed = done.count { it.status == TaskStatus.COMPLETED }
    val failed = done.count { it.status == TaskStatus.FAILED }
    val byCat = tasks.filter { it.status == TaskStatus.COMPLETED }.groupBy { FileNames.category(it.fileName) }.mapValues { (_, l) -> l.sumOf { it.totalBytes } }

    LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp)) {
        item { SubTitle(stringResource(R.string.reports), stringResource(R.string.reports_note), onBack) }
        item {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(R.string.p_day, R.string.p_week, R.string.p_month, R.string.p_year).forEachIndexed { i, l -> FilterChip(period == i, { period = i }, label = { Text(stringResource(l)) }) }
            }
        }
        item {
            Card {
                Row(Modifier.fillMaxWidth()) {
                    StatTile(stringResource(R.string.total), Fmt.bytes(p.bytes), Modifier.weight(1f), MaterialTheme.colorScheme.secondary)
                    StatTile("Wi-Fi", Fmt.bytes(p.wifi), Modifier.weight(1f))
                    StatTile(stringResource(R.string.mobile_data), Fmt.bytes(p.cellular), Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                UsageBars(usage.filter { it.day >= from })
            }
        }
        item {
            Card {
                Text(stringResource(R.string.success_rate), style = MaterialTheme.typography.titleSmall)
                Row(Modifier.fillMaxWidth()) {
                    StatTile(stringResource(R.string.st_completed), Fmt.iso(completed.toString()), Modifier.weight(1f), T.extra.success)
                    StatTile(stringResource(R.string.st_failed), Fmt.iso(failed.toString()), Modifier.weight(1f), T.extra.danger)
                    StatTile(stringResource(R.string.rate), if (completed + failed > 0) Fmt.iso("${completed * 100 / (completed + failed)}%") else "—", Modifier.weight(1f))
                }
            }
        }
        item {
            Card {
                Text(stringResource(R.string.by_type), style = MaterialTheme.typography.titleSmall)
                if (byCat.isEmpty()) Text(stringResource(R.string.no_data), color = T.extra.secondaryText)
                val max = (byCat.values.maxOrNull() ?: 1L).coerceAtLeast(1)
                byCat.entries.sortedByDescending { it.value }.forEach { (c, b) ->
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Row { Text(stringResource(categoryLabel(c)), modifier = Modifier.weight(1f)); Text(Fmt.bytes(b), color = T.extra.secondaryText) }
                        com.tadaffuq.app.ui.components.ProgressLine(b.toFloat() / max, T.extra.chartLine)
                    }
                }
            }
        }
        item {
            Row(Modifier.padding(16.dp)) {
                OutlinedButton(onClick = {
                    scope.launch {
                        val uri = vm.exportCsv(usage, tasks)
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
                    }
                }) { Text(stringResource(R.string.export_csv)) }
            }
        }
    }
}

@Composable
private fun UsageBars(rows: List<com.tadaffuq.data.UsageEntity>) {
    val byDay = rows.groupBy { it.day }.mapValues { (_, l) -> l.sumOf { it.bytes } }.toSortedMap()
    if (byDay.isEmpty()) { Text(stringResource(R.string.no_data), color = T.extra.secondaryText); return }
    val color = T.extra.chartLine
    val track = MaterialTheme.colorScheme.surfaceVariant
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    val values = byDay.values.toList().takeLast(60)
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(90.dp)) {
        val max = (values.maxOrNull() ?: 1L).coerceAtLeast(1).toFloat()
        val w = size.width / values.size
        values.forEachIndexed { i, v ->
            val h = (v / max) * size.height
            val x = if (rtl) size.width - (i + 1) * w else i * w
            drawRoundRect(track, topLeft = androidx.compose.ui.geometry.Offset(x + w * 0.15f, 0f), size = androidx.compose.ui.geometry.Size(w * 0.7f, size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f))
            drawRoundRect(color, topLeft = androidx.compose.ui.geometry.Offset(x + w * 0.15f, size.height - h), size = androidx.compose.ui.geometry.Size(w * 0.7f, h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f))
        }
    }
}

// ===== مركز الروابط =====

@Composable
fun LinksScreen(vm: MainViewModel, padding: PaddingValues, onBack: () -> Unit, onAdd: (AddRequest) -> Unit) {
    val ctx = LocalContext.current
    val links by vm.links.collectAsState()
    var generator by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().take(5_000_000).toByteArray().toString(Charsets.UTF_8) }.orEmpty()
            }
            val name = uri.lastPathSegment.orEmpty().lowercase()
            val urls = if (name.endsWith(".csv")) LinkTools.fromCsv(text) else LinkTools.extract(text)
            vm.saveLinks(urls.filter(LinkTools::isSupported))
        }
    }
    LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp)) {
        item { SubTitle(stringResource(R.string.link_hub), stringResource(R.string.link_hub_desc), onBack) }
        item {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { import.launch(arrayOf("text/plain", "text/csv", "text/comma-separated-values", "application/csv")) }) { Text(stringResource(R.string.import_file)) }
                OutlinedButton(onClick = { generator = true }) { Text(stringResource(R.string.sequence)) }
            }
            if (links.isNotEmpty()) Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onAdd(AddRequest(links.joinToString("\n") { it.url })) }) { Text(sr(R.string.download_all, links.size)) }
                TextButton(onClick = { vm.deleteLinks(links.map { it.id }) }) { Text(stringResource(R.string.clear_all), color = T.extra.danger) }
            }
        }
        if (links.isEmpty()) item { EmptyState(Icons.Rounded.Link, stringResource(R.string.no_links), stringResource(R.string.no_links_text)) }
        items(links, key = { it.id }) { l ->
            Card(onClick = { onAdd(AddRequest(l.url)) }) {
                Text(l.title ?: l.url.substringAfterLast('/').ifBlank { l.url }, style = MaterialTheme.typography.bodyLarge)
                Text(l.url, style = MaterialTheme.typography.bodySmall, color = T.extra.secondaryText)
                Row {
                    Text(Fmt.date(l.createdAt), style = MaterialTheme.typography.labelSmall, color = T.extra.secondaryText, modifier = Modifier.weight(1f).align(Alignment.CenterVertically))
                    TextButton(onClick = { vm.deleteLinks(listOf(l.id)) }) { Text(stringResource(R.string.action_delete)) }
                }
            }
        }
    }
    if (generator) SequenceDialog({ generator = false }) { generator = false; onAdd(AddRequest(it)) }
}

@Composable
private fun SequenceDialog(onDismiss: () -> Unit, onUse: (String) -> Unit) {
    var pattern by remember { mutableStateOf("https://") }
    val preview = if (LinkTools.hasSequence(pattern)) LinkTools.expandSequence(pattern) else emptyList()
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.sequence)) },
        text = {
            Column {
                Text(stringResource(R.string.sequence_desc), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(pattern, { pattern = it.trim() }, maxLines = 4, modifier = Modifier.fillMaxWidth())
                if (preview.isNotEmpty()) {
                    Text(com.tadaffuq.app.core.pr(R.plurals.sequence_count, preview.size, preview.size), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
                    preview.take(3).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = T.extra.secondaryText) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onUse(preview.joinToString("\n")) }, enabled = preview.isNotEmpty()) { Text(stringResource(R.string.use)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

// ===== السلة =====

@Composable
fun TrashScreen(vm: MainViewModel, padding: PaddingValues, onBack: () -> Unit) {
    val trash by vm.trash.collectAsState()
    LaunchedEffect(Unit) { vm.purgeOldTrash() }
    LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp)) {
        item { SubTitle(stringResource(R.string.trash), stringResource(R.string.trash_note), onBack) }
        if (trash.isNotEmpty()) item {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { vm.restore(trash) }) { Text(stringResource(R.string.restore_all)) }
                TextButton(onClick = { vm.purge(trash) }) { Text(stringResource(R.string.empty_trash), color = T.extra.danger) }
            }
        }
        if (trash.isEmpty()) item { EmptyState(Icons.Rounded.DeleteOutline, stringResource(R.string.trash_empty), stringResource(R.string.trash_empty_text)) }
        items(trash, key = { it.id }) { t ->
            Card {
                Text(t.fileName, style = MaterialTheme.typography.bodyLarge)
                Text(Fmt.bytes(t.totalBytes) + " · " + Fmt.date(t.trashedAt ?: 0), style = MaterialTheme.typography.bodySmall, color = T.extra.secondaryText)
                Row {
                    TextButton(onClick = { vm.restore(listOf(t)) }) { Text(stringResource(R.string.restore)) }
                    TextButton(onClick = { vm.purge(listOf(t)) }) { Text(stringResource(R.string.delete_forever), color = T.extra.danger) }
                }
            }
        }
    }
}

