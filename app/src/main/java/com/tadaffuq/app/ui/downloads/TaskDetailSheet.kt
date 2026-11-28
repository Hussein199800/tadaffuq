package com.tadaffuq.app.ui.downloads

import com.tadaffuq.app.core.sr
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.tadaffuq.app.R
import com.tadaffuq.app.core.Fmt
import com.tadaffuq.app.download.ErrorCodes
import com.tadaffuq.app.download.StorageDirs
import com.tadaffuq.app.ui.MainViewModel
import com.tadaffuq.app.ui.components.SegmentMap
import com.tadaffuq.app.ui.components.StatTile
import com.tadaffuq.app.ui.doctorText
import com.tadaffuq.app.ui.statusText
import com.tadaffuq.app.ui.theme.T
import com.tadaffuq.data.TaskEntity
import com.tadaffuq.data.TaskStatus
import com.tadaffuq.engine.Doctor
import com.tadaffuq.engine.ProbeResult
import com.tadaffuq.engine.Snapshot
import java.io.File
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TaskDetailSheet(t: TaskEntity, live: Snapshot?, vm: MainViewModel, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var dialog by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        TaskDetailContent(t, live, vm) { dialog = it }
    }

    when (dialog) {
        "limit" -> SpeedLimitDialog(t.speedLimitBps, { dialog = null }) { vm.setSpeedLimit(t.id, it); dialog = null }
        "url" -> UrlDialog(t.url, { dialog = null }) { vm.updateUrl(t.id, it); dialog = null }
        "mirrors" -> MirrorsDialog(t, vm) { dialog = null }
        "delete" -> DeleteDialog(1, t.status == TaskStatus.COMPLETED, { dialog = null }) { withFiles -> vm.delete(listOf(t.id), withFiles); dialog = null; onDismiss() }
    }
}

/** محتوى التفاصيل منفصلاً عن النافذة ليُعاد استعماله ويُختبر. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskDetailContent(t: TaskEntity, live: Snapshot?, vm: MainViewModel, openDialog: (String) -> Unit) {
    val ctx = LocalContext.current
    run {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp)) {
            Text(t.fileName, style = MaterialTheme.typography.titleLarge)
            Text(statusText(t.status), style = MaterialTheme.typography.labelLarge, color = statusColor(t.status))
            Spacer(Modifier.height(12.dp))

            val total = live?.total ?: t.totalBytes
            val saved by androidx.compose.runtime.produceState(emptyList<com.tadaffuq.engine.SegmentState>(), t.id, t.status) {
                value = if (live == null && t.status != com.tadaffuq.data.TaskStatus.COMPLETED) vm.savedSegments(t.id) else emptyList()
            }
            val segments = live?.segments ?: saved.takeIf { it.isNotEmpty() }
            if (segments != null && total > 0) {
                Text(sr(R.string.segments_title, segments.size), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                SegmentMap(segments, total, T.extra.chartLine, com.tadaffuq.app.core.pr(R.plurals.segments_a11y, segments.size, segments.size), height = 28.dp)
                Spacer(Modifier.height(10.dp))
                com.tadaffuq.app.ui.components.SegmentList(segments, T.extra.chartLine)
                Spacer(Modifier.height(12.dp))
            }

            Row(Modifier.fillMaxWidth()) {
                StatTile(stringResource(R.string.downloaded), "${Fmt.bytes(live?.downloaded ?: t.downloadedBytes)} / ${Fmt.bytes(total)}", Modifier.weight(1f))
                StatTile(stringResource(R.string.speed_now), live?.let { Fmt.speed(it.speedBps) } ?: "—", Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth()) {
                StatTile(stringResource(R.string.speed_avg), Fmt.speed(live?.averageBps ?: t.averageBps), Modifier.weight(1f))
                StatTile(stringResource(R.string.speed_max), Fmt.speed(maxOf(live?.maxBps ?: 0, t.maxBps)), Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth()) {
                StatTile(stringResource(R.string.time_left), live?.etaSeconds?.let { Fmt.duration(it) } ?: "—", Modifier.weight(1f))
                StatTile(stringResource(R.string.connections), live?.connections?.let { Fmt.iso(it.toString()) } ?: if (t.connections == 0) stringResource(R.string.auto) else Fmt.iso(t.connections.toString()), Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth()) {
                StatTile(stringResource(R.string.retries), Fmt.iso(t.retries.toString()), Modifier.weight(1f))
                StatTile(stringResource(R.string.priority), Fmt.iso(t.priority.toString()), Modifier.weight(1f))
            }
            if (t.speedLimitBps > 0) StatTile(stringResource(R.string.speed_limit), Fmt.speed(t.speedLimitBps))

            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.link), style = MaterialTheme.typography.titleSmall)
            Text(t.url, style = MaterialTheme.typography.bodySmall, color = T.extra.secondaryText)
            if (t.finalUrl != null && t.finalUrl != t.url) {
                Text(stringResource(R.string.final_link), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
                Text(t.finalUrl.orEmpty(), style = MaterialTheme.typography.bodySmall, color = T.extra.secondaryText)
            }
            Text(sr(R.string.saved_in, Fmt.iso(t.filePath ?: t.dirPath)), style = MaterialTheme.typography.bodySmall, color = T.extra.secondaryText, modifier = Modifier.padding(top = 4.dp))
            // وجهة هذه المهمة مثبّتة عند إضافتها؛ تُغيَّر هنا صراحة قبل الاكتمال
            val ctx2 = androidx.compose.ui.platform.LocalContext.current
            val pickTree = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { uri ->
                if (uri != null) {
                    runCatching { ctx2.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                    vm.setTree(t.id, uri.toString())
                }
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Text(sr(R.string.task_dest, t.treeUri?.let { Fmt.iso(com.tadaffuq.app.download.TaskFiles.treeName(ctx2, it)) } ?: stringResource(R.string.task_dest_default)),
                    style = MaterialTheme.typography.bodySmall, color = T.extra.secondaryText, modifier = Modifier.weight(1f))
                if (t.status != TaskStatus.COMPLETED) {
                    TextButton(onClick = { pickTree.launch(null) }) { Text(stringResource(R.string.task_dest_change)) }
                    if (t.treeUri != null) TextButton(onClick = { vm.setTree(t.id, null) }) { Text(stringResource(R.string.reset_default)) }
                }
            }
            if (t.errorCode?.startsWith("HOSTKEY") == true && t.errorDetail != null)
                Text(sr(R.string.hostkey_new_text, t.errorDetail), style = MaterialTheme.typography.bodySmall, color = T.extra.warning, modifier = Modifier.padding(top = 4.dp))
            t.actualHash?.let { Text("SHA/MD5: ${Fmt.iso(it)}", style = MaterialTheme.typography.bodySmall, color = if (t.hashOk == false) T.extra.danger else T.extra.secondaryText) }

            // طبيب التحميل: قواعد ثابتة على قراءات المهمة الحقيقية
            val probe = if (t.totalBytes != 0L || t.finalUrl != null) ProbeResult(t.finalUrl ?: t.url, t.totalBytes, t.acceptRanges, t.etag, t.lastModified, t.fileName, t.mimeType) else null
            val findings = Doctor.diagnose(
                Doctor.Input(
                    probe = probe.takeIf { t.finalUrl != null },
                    error = ErrorCodes.toError(t.errorCode, t.errorDetail),
                    backgroundRestricted = ctx.getSystemService(android.app.ActivityManager::class.java).isBackgroundRestricted,
                    retries = t.retries, networkRetries = t.networkRetries, serverRetries = t.serverRetries,
                    averageBps = live?.averageBps ?: t.averageBps, connections = live?.connections ?: 0,
                    freeBytes = runCatching { File(t.dirPath).let { if (it.exists()) it else it.parentFile }?.usableSpace ?: -1 }.getOrDefault(-1),
                    remainingBytes = if (total > 0) total - (live?.downloaded ?: t.downloadedBytes) else -1,
                ),
            ).filter { t.status != TaskStatus.COMPLETED || it == Doctor.Finding.CHECKSUM_MISMATCH }
            if (findings.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text(stringResource(R.string.doctor_title), style = MaterialTheme.typography.titleSmall)
                findings.forEach { f ->
                    val (what, fix) = doctorText(f)
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text(what, style = MaterialTheme.typography.bodyMedium)
                        Text(fix, style = MaterialTheme.typography.bodySmall, color = T.extra.secondaryText)
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when (t.status) {
                    TaskStatus.RUNNING, TaskStatus.QUEUED, TaskStatus.WAITING_NETWORK ->
                        Action(Icons.Rounded.Pause, stringResource(R.string.action_pause)) { vm.pause(listOf(t.id)) }
                    TaskStatus.FAILED.takeIf { t.errorCode == "HOSTKEY_NEW" && t.errorDetail != null } -> {
                        Action(Icons.Rounded.PlayArrow, stringResource(R.string.hostkey_trust)) {
                            com.tadaffuq.engine.RemoteTarget.parse(t.url)?.let { r -> vm.manager.credentials.trustHostKey(r.host, r.port, t.errorDetail!!) }
                            vm.resume(listOf(t.id))
                        }
                    }
                    TaskStatus.PAUSED, TaskStatus.FAILED ->
                        Action(Icons.Rounded.PlayArrow, stringResource(if (t.status == TaskStatus.FAILED) R.string.action_retry else R.string.action_resume)) { vm.resume(listOf(t.id)) }
                    else -> Unit
                }
                if (t.status == TaskStatus.COMPLETED) {
                    val file = t.filePath?.let(::File)
                    if (file?.exists() == true) {
                        Action(Icons.Rounded.OpenInNew, stringResource(R.string.action_open)) {
                            try { ctx.startActivity(StorageDirs.openIntent(ctx, file)) } catch (e: ActivityNotFoundException) {
                                Toast.makeText(ctx, R.string.no_app_to_open, Toast.LENGTH_SHORT).show()
                            }
                        }
                        Action(Icons.Rounded.Share, stringResource(R.string.action_share)) { ctx.startActivity(StorageDirs.shareIntent(ctx, listOf(file))) }
                    }
                } else {
                    Action(Icons.Rounded.ArrowUpward, stringResource(R.string.priority_up)) { vm.setPriority(t.id, t.priority + 1) }
                    Action(Icons.Rounded.ArrowDownward, stringResource(R.string.priority_down)) { vm.setPriority(t.id, t.priority - 1) }
                    Action(Icons.Rounded.Speed, stringResource(R.string.speed_limit)) { openDialog("limit") }
                    Action(Icons.Rounded.Link, stringResource(R.string.refresh_link)) { openDialog("url") }
                    Action(Icons.Rounded.Link, stringResource(R.string.mirrors)) { openDialog("mirrors") }
                    Action(Icons.Rounded.RestartAlt, stringResource(R.string.action_restart)) { vm.restart(t.id) }
                }
                Action(Icons.Rounded.ContentCopy, stringResource(R.string.copy_link)) {
                    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("url", t.url))
                }
                Action(Icons.Rounded.Delete, stringResource(R.string.action_delete)) { openDialog("delete") }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun Action(icon: ImageVector, label: String, onClick: () -> Unit) {
    AssistChip(onClick = onClick, label = { Text(label) }, leadingIcon = { Icon(icon, null) })
}

@Composable
fun SpeedLimitDialog(currentBps: Long, onDismiss: () -> Unit, onSet: (Long) -> Unit) {
    var text by remember { mutableStateOf(if (currentBps > 0) (currentBps / 1024).toString() else "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.speed_limit)) },
        text = {
            Column {
                Text(stringResource(R.string.speed_limit_desc), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(text, { text = it }, label = { Text(stringResource(R.string.kbps)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onSet(((Fmt.parseNumber(text) ?: 0.0) * 1024).toLong().coerceAtLeast(0)) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun UrlDialog(current: String, onDismiss: () -> Unit, onSet: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.refresh_link)) },
        text = {
            Column {
                Text(stringResource(R.string.refresh_link_desc), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(text, { text = it.trim() }, maxLines = 5)
            }
        },
        confirmButton = { TextButton(onClick = { onSet(text) }, enabled = com.tadaffuq.engine.Protocols.isSupported(text)) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun MirrorsDialog(t: TaskEntity, vm: MainViewModel, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(t.mirrors.orEmpty()) }
    var results by remember { mutableStateOf<List<com.tadaffuq.engine.MirrorResult>?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mirrors)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.mirrors_desc), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(text, { text = it; results = null }, minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth())
                TextButton(enabled = !testing && text.isNotBlank(), onClick = {
                    testing = true
                    scope.launch {
                        vm.manager.setMirrors(t.id, text)
                        results = runCatching { vm.manager.testMirrors(t.copy(mirrors = text)) }.getOrDefault(emptyList())
                        testing = false
                    }
                }) { Text(stringResource(if (testing) R.string.add_probing else R.string.mirrors_test)) }
                results?.forEach { r ->
                    Text(r.url, style = MaterialTheme.typography.bodySmall)
                    Text(
                        if (r.matches) Fmt.speed(r.bps) + " · " + stringResource(R.string.mirror_ok) else stringResource(R.string.mirror_bad),
                        style = MaterialTheme.typography.labelMedium, color = if (r.matches) T.extra.success else T.extra.danger,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { scope.launch { vm.manager.setMirrors(t.id, text); onDismiss() } }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
