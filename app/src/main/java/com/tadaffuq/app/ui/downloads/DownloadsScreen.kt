package com.tadaffuq.app.ui.downloads

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.automirrored.rounded.Sort
import com.tadaffuq.app.core.sr
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.tadaffuq.app.ui.MainViewModel
import com.tadaffuq.app.ui.components.Chip
import com.tadaffuq.app.ui.components.EmptyState
import com.tadaffuq.app.ui.components.LargeTitle
import com.tadaffuq.app.ui.holdText
import com.tadaffuq.app.ui.theme.T
import com.tadaffuq.data.TaskEntity
import com.tadaffuq.data.TaskStatus

enum class Filter(val label: Int, val match: (TaskStatus) -> Boolean) {
    ALL(R.string.f_all, { true }),
    ACTIVE(R.string.f_active, { it.isActive }),
    WAITING(R.string.f_waiting, { it.isWaiting }),
    COMPLETED(R.string.f_completed, { it == TaskStatus.COMPLETED }),
    PAUSED(R.string.f_paused, { it == TaskStatus.PAUSED }),
    FAILED(R.string.f_failed, { it == TaskStatus.FAILED }),
    TORRENT(R.string.f_torrent, { false }),
}

@Composable
fun DownloadsScreen(vm: MainViewModel, onAdd: () -> Unit, padding: PaddingValues, tvm: com.tadaffuq.app.ui.torrent.TorrentViewModel = androidx.hilt.navigation.compose.hiltViewModel()) {
    val torrents by tvm.torrents.collectAsState()
    val tlive by tvm.live.collectAsState()
    var torrentDetail by rememberSaveable { mutableStateOf<String?>(null) }
    val tasks by vm.tasks.collectAsState()
    val live by vm.live.collectAsState()
    val hold by vm.hold.collectAsState()
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    var selection by remember { mutableStateOf(setOf<Long>()) }
    var detail by rememberSaveable { mutableStateOf<Long?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val st0 by vm.settings.collectAsState()
    val sort = DownloadSort.of(st0.downloadsSort)
    val shown = sort.apply(tasks.filter { filter.match(it.status) })
    // التورنت العامل يُحسب في «يعمل» و«ينتظر» مع التنزيلات العادية
    val torrentActive = tlive.values.count { it.state == com.tadaffuq.torrent.TorrentState.DOWNLOADING || it.state == com.tadaffuq.torrent.TorrentState.METADATA || it.state == com.tadaffuq.torrent.TorrentState.CHECKING || it.state == com.tadaffuq.torrent.TorrentState.SEEDING }
    val failedCount = tasks.count { it.status == TaskStatus.FAILED }
    var sortMenu by remember { mutableStateOf(false) }

    LazyColumn(contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 88.dp, top = padding.calculateTopPadding())) {
        item {
            if (selection.isEmpty()) {
                LargeTitle(stringResource(R.string.tab_downloads), sr(R.string.downloads_sub, tasks.count { it.status.isActive } + torrentActive, tasks.count { it.status.isWaiting })) {
                    if (tasks.any { it.status.isActive || it.status.isWaiting }) IconButton(onClick = vm::pauseAll) { Icon(Icons.Rounded.Pause, stringResource(R.string.action_pause_all), tint = com.tadaffuq.app.ui.components.roleColor(com.tadaffuq.app.ui.components.Role.PAUSE)) }
                    if (tasks.any { it.status == TaskStatus.PAUSED }) IconButton(onClick = vm::resumeAll) { Icon(Icons.Rounded.PlayArrow, stringResource(R.string.action_resume_all), tint = com.tadaffuq.app.ui.components.roleColor(com.tadaffuq.app.ui.components.Role.START)) }
                }
            } else {
                SelectionBar(
                    count = selection.size,
                    onClose = { selection = emptySet() },
                    onAll = { selection = shown.map { it.id }.toSet() },
                    onPause = { vm.pause(selection); selection = emptySet() },
                    onResume = { vm.resume(selection); selection = emptySet() },
                    onDelete = { confirmDelete = true },
                )
            }
            val settings by vm.settings.collectAsState()
            QuickPanel(settings, live.values.sumOf { it.speedBps }) { f -> vm.updateSettings(f) }
            hold?.let {
                // رسالة تخص الطابور كله: تنبيه كهرماني تحت العنوان
                com.tadaffuq.app.ui.components.RoleBanner(com.tadaffuq.app.ui.components.Role.PAUSE, holdText(it), Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            // الترتيب ظاهر دائماً باسمه الحالي، واختصار للإخفاقات يفتح فلترها بلمسة
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.layout.Box {
                    androidx.compose.material3.OutlinedButton(onClick = { sortMenu = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.AutoMirrored.Rounded.Sort, null, modifier = Modifier.size(18.dp))
                        androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
                        Text(sr(R.string.sort_by, stringResource(sort.label)))
                    }
                    androidx.compose.material3.DropdownMenu(sortMenu, { sortMenu = false }) {
                        DownloadSort.entries.forEach { o ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(stringResource(o.label)) },
                                leadingIcon = { if (o == sort) Icon(Icons.Rounded.Check, null) },
                                onClick = { vm.updateSettings { it.copy(downloadsSort = o.name) }; sortMenu = false },
                            )
                        }
                    }
                }
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                if (failedCount > 0 && filter != Filter.FAILED) androidx.compose.material3.TextButton(onClick = { filter = Filter.FAILED }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Rounded.ErrorOutline, null, tint = T.extra.danger, modifier = Modifier.size(18.dp))
                    androidx.compose.foundation.layout.Spacer(Modifier.width(4.dp))
                    Text(com.tadaffuq.app.core.pr(R.plurals.failed_shortcut, failedCount, failedCount), color = T.extra.danger)
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Filter.entries.forEach { f ->
                    if (f == Filter.TORRENT && torrents.isEmpty()) return@forEach
                    Chip(stringResource(f.label), filter == f, { filter = f }, count = (if (f == Filter.TORRENT) torrents.size else tasks.count { f.match(it.status) }).takeIf { f != Filter.ALL },
                        tint = when (f) {
                            Filter.ALL -> null
                            Filter.ACTIVE -> statusColor(TaskStatus.RUNNING)
                            Filter.WAITING -> statusColor(TaskStatus.WAITING_NETWORK)
                            Filter.COMPLETED -> statusColor(TaskStatus.COMPLETED)
                            Filter.PAUSED -> statusColor(TaskStatus.PAUSED)
                            Filter.FAILED -> statusColor(TaskStatus.FAILED)
                            Filter.TORRENT -> if (com.tadaffuq.app.ui.components.isDark()) com.tadaffuq.app.ui.components.Tint.VIOLET.dark else com.tadaffuq.app.ui.components.Tint.VIOLET.light
                        })
                }
            }
        }
        if (filter == Filter.ALL || filter == Filter.TORRENT) items(torrents, key = { "t" + it.infoHash }) { t ->
            com.tadaffuq.app.ui.torrent.TorrentCard(t, tlive[t.infoHash]) { torrentDetail = t.infoHash }
        }
        if (shown.isEmpty() && !(filter == Filter.ALL && torrents.isNotEmpty()) && filter != Filter.TORRENT) item {
            EmptyState(
                Icons.Rounded.Download,
                stringResource(if (tasks.isEmpty()) R.string.empty_downloads else R.string.empty_filter),
                stringResource(if (tasks.isEmpty()) R.string.empty_downloads_text else R.string.empty_filter_text),
                if (tasks.isEmpty()) stringResource(R.string.add_title) else null, onAdd,
            )
        }
        items(shown, key = { it.id }) { t ->
            val st by vm.settings.collectAsState()
            TaskCard(
                compact = st.density == com.tadaffuq.data.ListDensity.COMPACT, showSegments = st.showSegmentsInList,
                task = t, live = live[t.id], selected = t.id in selection, selectionMode = selection.isNotEmpty(),
                onClick = { if (selection.isNotEmpty()) selection = selection.toggle(t.id) else detail = t.id },
                onLongClick = { selection = selection.toggle(t.id) },
                onPrimary = { primary(vm, t) },
            )
        }
    }

    torrentDetail?.let { h -> com.tadaffuq.app.ui.torrent.TorrentDetailSheet(h, { torrentDetail = null }, tvm) }
    detail?.let { id -> tasks.firstOrNull { it.id == id }?.let { TaskDetailSheet(it, live[id], vm) { detail = null } } ?: run { detail = null } }

    if (confirmDelete) DeleteDialog(
        count = selection.size,
        hasFiles = tasks.any { it.id in selection && it.status == TaskStatus.COMPLETED },
        onDismiss = { confirmDelete = false },
        onConfirm = { withFiles -> vm.delete(selection, withFiles); selection = emptySet(); confirmDelete = false },
    )
}

private fun Set<Long>.toggle(id: Long) = if (id in this) this - id else this + id

fun primary(vm: MainViewModel, t: TaskEntity) {
    when (t.status) {
        TaskStatus.RUNNING, TaskStatus.QUEUED, TaskStatus.WAITING_NETWORK -> vm.pause(listOf(t.id))
        TaskStatus.PAUSED, TaskStatus.FAILED -> vm.resume(listOf(t.id))
        else -> Unit
    }
}

@Composable
private fun SelectionBar(count: Int, onClose: () -> Unit, onAll: () -> Unit, onPause: () -> Unit, onResume: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.padding(horizontal = 8.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, stringResource(R.string.cancel)) }
        Text(sr(R.string.selected_n, count), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        IconButton(onClick = onAll) { Icon(Icons.Rounded.SelectAll, stringResource(R.string.select_all)) }
        IconButton(onClick = onPause) { Icon(Icons.Rounded.Pause, stringResource(R.string.action_pause), tint = com.tadaffuq.app.ui.components.roleColor(com.tadaffuq.app.ui.components.Role.PAUSE)) }
        IconButton(onClick = onResume) { Icon(Icons.Rounded.PlayArrow, stringResource(R.string.action_resume), tint = com.tadaffuq.app.ui.components.roleColor(com.tadaffuq.app.ui.components.Role.START)) }
        IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, stringResource(R.string.action_delete), tint = com.tadaffuq.app.ui.components.roleColor(com.tadaffuq.app.ui.components.Role.DELETE)) }
    }
}

@Composable
fun DeleteDialog(count: Int, hasFiles: Boolean, onDismiss: () -> Unit, savedBytes: Long = 0, onConfirm: (Boolean) -> Unit) {
    var withFiles by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(com.tadaffuq.app.core.pr(R.plurals.delete_title, count, count)) },
        text = {
            androidx.compose.foundation.layout.Column {
                Text(stringResource(R.string.delete_desc))
                // تنزيل لم يكتمل: يُسمّى ما سيُفقد
                if (savedBytes > 0) Text(com.tadaffuq.app.core.sr(R.string.delete_progress_lost, Fmt.bytes(savedBytes)), color = T.extra.warning)
                if (hasFiles) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(withFiles, { withFiles = it })
                    Text(stringResource(R.string.delete_files_too))
                }
            }
        },
        // الزر يقول ما سيحدث فعلاً: إزالة السجل فقط، أو حذف الملف من الجهاز
        confirmButton = { TextButton(onClick = { onConfirm(withFiles) }) { Text(stringResource(if (hasFiles && withFiles) R.string.delete_file_device else R.string.remove_from_list), color = T.extra.danger) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = T.extra.neutral) } },
    )
}
