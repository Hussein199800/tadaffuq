package com.tadaffuq.app.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tadaffuq.app.BuildConfig
import com.tadaffuq.app.R
import com.tadaffuq.app.core.Fmt
import com.tadaffuq.app.core.sr
import com.tadaffuq.app.ui.components.Card
import com.tadaffuq.app.ui.components.Hairline
import com.tadaffuq.app.ui.components.ProgressLine
import com.tadaffuq.app.ui.components.SettingRow
import com.tadaffuq.app.ui.components.Tint
import com.tadaffuq.app.ui.components.ToggleRow
import com.tadaffuq.app.ui.theme.T
import com.tadaffuq.data.Settings
import com.tadaffuq.engine.SelfUpdate
import kotlinx.coroutines.launch

/** رسالة كل حالة بلغة بسيطة، مع الحل عند الفشل. */
@Composable
fun updateStatusText(s: UpdateState): String = when (s) {
    UpdateState.Idle -> sr(R.string.upd_current, BuildConfig.VERSION_NAME)
    UpdateState.Checking -> stringResource(R.string.upd_checking)
    UpdateState.UpToDate -> stringResource(R.string.upd_latest)
    is UpdateState.Available -> sr(R.string.upd_available, s.c.versionName)
    is UpdateState.Downloading -> sr(R.string.upd_downloading, Fmt.percent(s.done, s.total))
    is UpdateState.Verifying -> stringResource(R.string.upd_verifying)
    is UpdateState.NeedsPermission -> stringResource(R.string.upd_need_permission)
    is UpdateState.Installing -> stringResource(R.string.upd_installing)
    is UpdateState.Failed -> stringResource(when (s.reason) {
        UpdateState.Reason.NETWORK -> R.string.upd_err_network
        UpdateState.Reason.RATE_LIMITED -> R.string.upd_err_rate
        UpdateState.Reason.CHECKSUM -> R.string.upd_err_checksum
        UpdateState.Reason.SIGNATURE -> R.string.upd_err_signature
        UpdateState.Reason.SPACE -> R.string.upd_err_space
        UpdateState.Reason.INSTALL -> R.string.upd_err_install
        UpdateState.Reason.UNSUPPORTED -> R.string.upd_unsupported
    })
}

/** بطاقة «التحديثات» في «حول»: الإصدار الحالي، فحص الآن، والفحص التلقائي. نسخة GitHub فقط. */
@Composable
fun UpdateCard(updater: AppUpdater, settings: Settings, onAutoChange: (Boolean) -> Unit) {
    if (!updater.enabled) return
    val state by updater.state.collectAsState()
    val scope = rememberCoroutineScope()
    Card(padding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp)) {
        SettingRow(stringResource(R.string.upd_title), updateStatusText(state), Icons.Rounded.SystemUpdate, tint = Tint.MINT,
            onClick = { scope.launch { updater.check(manual = true) } }) {
            TextButton(onClick = { scope.launch { updater.check(manual = true) } }, enabled = state !is UpdateState.Checking && state !is UpdateState.Downloading) {
                Text(stringResource(R.string.upd_check_now))
            }
        }
        Hairline()
        ToggleRow(stringResource(R.string.upd_auto), stringResource(R.string.upd_auto_desc), settings.autoUpdateCheck, onChange = onAutoChange)
    }
}

/**
 * نافذة التحديث: تظهر حين يوجد إصدار أحدث (تلقائياً مرة لكل إصدار، أو بعد الفحص اليدوي)
 * وتبقى خلال التنزيل والتحقق والتثبيت، بأرقام حقيقية وزر إلغاء.
 */
@Composable
fun UpdateDialog(updater: AppUpdater, onClose: () -> Unit) {
    val state by updater.state.collectAsState()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val c: SelfUpdate.Candidate = when (val s = state) {
        is UpdateState.Available -> s.c
        is UpdateState.Downloading -> s.c
        is UpdateState.Verifying -> s.c
        is UpdateState.NeedsPermission -> s.c
        is UpdateState.Installing -> s.c
        is UpdateState.Failed -> s.c ?: return
        else -> return
    }
    val busy = state is UpdateState.Downloading || state is UpdateState.Verifying || state is UpdateState.Installing
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        icon = { androidx.compose.material3.Icon(Icons.Rounded.SystemUpdate, null) },
        title = { Text(sr(R.string.upd_new_version, c.versionName)) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text(sr(R.string.upd_size, Fmt.bytes(c.asset.size)), style = MaterialTheme.typography.labelLarge, color = T.extra.secondaryText)
                val notes = SelfUpdate.plainNotes(c.notes)
                if (notes.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(notes, style = MaterialTheme.typography.bodyMedium) }
                if (busy || state is UpdateState.Failed || state is UpdateState.NeedsPermission) {
                    Spacer(Modifier.height(12.dp))
                    Text(updateStatusText(state), style = MaterialTheme.typography.bodySmall, color = if (state is UpdateState.Failed) T.extra.danger else T.extra.secondaryText)
                    (state as? UpdateState.Downloading)?.let { d ->
                        Spacer(Modifier.height(6.dp))
                        ProgressLine(if (d.total > 0) d.done.toFloat() / d.total else null, T.extra.chartLine)
                        Text("${Fmt.bytes(d.done)} / ${Fmt.bytes(d.total)}", style = MaterialTheme.typography.labelSmall, color = T.extra.secondaryText)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.upd_safety), style = MaterialTheme.typography.labelSmall, color = T.extra.secondaryText)
            }
        },
        confirmButton = {
            when (val s = state) {
                is UpdateState.NeedsPermission -> Button(onClick = { ctx.startActivity(updater.permissionIntent()) }) { Text(stringResource(R.string.upd_allow)) }
                is UpdateState.Available, is UpdateState.Failed -> Button(onClick = { scope.launch { updater.downloadAndInstall(c) } }) {
                    Text(stringResource(if (s is UpdateState.Failed) R.string.retry else R.string.upd_install))
                }
                else -> Unit
            }
        },
        dismissButton = {
            if (!busy) TextButton(onClick = { scope.launch { updater.dismiss(c) }; onClose() }) { Text(stringResource(R.string.upd_later)) }
        },
    )
}
