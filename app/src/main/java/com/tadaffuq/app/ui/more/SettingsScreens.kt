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
internal fun accentName(a: Accent) = when (a) {
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

    }

    when (dialog) {
        "ua" -> TextDialog(stringResource(R.string.user_agent), s.userAgent, { dialog = null }) { v -> set { it.copy(userAgent = v.trim()) }; dialog = null }
        "proxy" -> ProxyDialog(s, vm.secrets.has(com.tadaffuq.app.core.SecretStore.PROXY_PASSWORD), { dialog = null }) { mode, host, port, user, pass ->
            set { it.copy(proxyMode = mode, proxyHost = host, proxyPort = port, proxyUser = user) }
            if (pass != null) vm.secrets.put(com.tadaffuq.app.core.SecretStore.PROXY_PASSWORD, pass)
            dialog = null
        }
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
