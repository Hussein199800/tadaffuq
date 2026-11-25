package com.tadaffuq.app.ui.downloads

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tadaffuq.app.R
import com.tadaffuq.app.core.Fmt
import com.tadaffuq.app.ui.components.Card
import com.tadaffuq.app.ui.components.ToggleRow
import com.tadaffuq.app.ui.theme.T
import com.tadaffuq.data.Settings
import com.tadaffuq.engine.SpeedMode

/**
 * لوحة التحكم السريعة، مطوية افتراضياً وبثلاثة خيارات فقط: حد السرعة، وطريقة التنزيل، وWi-Fi فقط.
 * الاتصالات والمتزامن في الإعدادات ← التحميل.
 * كل تغيير يُطبَّق فوراً على التنزيلات الجارية (حد السرعة والاتصالات حيّان في المحرك).
 */
@Composable
fun QuickPanel(s: Settings, speedNow: Long, initiallyOpen: Boolean = false, onChange: ((Settings) -> Settings) -> Unit) {
    var open by rememberSaveable { mutableStateOf(initiallyOpen) }
    var custom by remember { mutableStateOf(false) }
    Card(onClick = { open = !open }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Tune, null, tint = MaterialTheme.colorScheme.secondary)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(stringResource(R.string.quick_panel), style = MaterialTheme.typography.titleSmall)
                Text(
                    listOf(
                        if (s.globalLimitBps > 0) Fmt.speed(s.globalLimitBps) else stringResource(R.string.unlimited),
                        stringResource(com.tadaffuq.app.ui.more.modeLabel(s.mode)),
                        if (s.wifiOnly) stringResource(R.string.wifi_only) else null,
                    ).filterNotNull().joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = T.extra.secondaryText,
                )
            }
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, stringResource(R.string.quick_panel))
        }
        AnimatedVisibility(open) {
            Column {
                Text(stringResource(R.string.global_limit), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val presets = listOf(0L, 256L * 1024, 1024L * 1024, 5L * 1024 * 1024)
                    presets.forEach { v ->
                        FilterChip(s.globalLimitBps == v, { onChange { it.copy(globalLimitBps = v) } },
                            label = { Text(if (v == 0L) stringResource(R.string.unlimited) else Fmt.speed(v)) })
                    }
                    FilterChip(s.globalLimitBps !in presets, { custom = true }, label = { Text(stringResource(R.string.schedule_custom)) })
                }
                Text(stringResource(R.string.speed_mode), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(SpeedMode.NORMAL to R.string.mode_normal, SpeedMode.MAX to R.string.mode_max, SpeedMode.WEAK_NETWORK to R.string.mode_weak, SpeedMode.SAVER to R.string.mode_saver).forEach { (m, l) ->
                        FilterChip(s.mode == m, { onChange { it.copy(mode = m) } }, label = { Text(stringResource(l)) })
                    }
                }
                ToggleRow(stringResource(R.string.wifi_only), stringResource(R.string.wifi_only_desc), s.wifiOnly) { v -> onChange { it.copy(wifiOnly = v) } }
                Text(stringResource(R.string.qp_now, Fmt.speed(speedNow)), style = MaterialTheme.typography.bodySmall, color = T.extra.secondaryText)
            }
        }
    }
    if (custom) SpeedLimitDialog(s.globalLimitBps, { custom = false }) { v -> onChange { it.copy(globalLimitBps = v) }; custom = false }
}
