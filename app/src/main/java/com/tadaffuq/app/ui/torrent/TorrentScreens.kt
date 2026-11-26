package com.tadaffuq.app.ui.torrent

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tadaffuq.app.R
import com.tadaffuq.app.core.Fmt
import com.tadaffuq.app.core.sr
import com.tadaffuq.app.download.TorrentManager
import com.tadaffuq.app.ui.components.ProgressLine
import com.tadaffuq.app.ui.components.SliderRow
import com.tadaffuq.app.ui.components.StatTile
import com.tadaffuq.app.ui.theme.T
import com.tadaffuq.data.SettingsRepository
import com.tadaffuq.data.TorrentEntity
import com.tadaffuq.data.TorrentRunState
import com.tadaffuq.torrent.TorrentInfoSnapshot
import com.tadaffuq.torrent.TorrentMeta
import com.tadaffuq.torrent.TorrentState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** طلب إضافة تورنت: Magnet أو محتوى ملف .torrent. */
data class TorrentRequest(val magnet: String? = null, val raw: ByteArray? = null)

@HiltViewModel
class TorrentViewModel @Inject constructor(val manager: TorrentManager, private val settings: SettingsRepository) : ViewModel() {
    val torrents = manager.torrents.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val live = manager.live
    val meta = MutableStateFlow<TorrentMeta?>(null)
    val loading = MutableStateFlow(false)
    val error = MutableStateFlow<Int?>(null)
    val wanted = MutableStateFlow<Set<Int>>(emptySet())
    val seedAfter = MutableStateFlow(false)
    val ratio = MutableStateFlow(1f)
    val upLimit = MutableStateFlow(0)
    private var request: TorrentRequest? = null

    fun open(r: TorrentRequest) {
        request = r
        meta.value = null; error.value = null; loading.value = true
        viewModelScope.launch {
            val s = settings.current()
            seedAfter.value = s.torrentSeedDefault; ratio.value = s.torrentRatio; upLimit.value = s.torrentUpLimit
            val m = runCatching { r.raw?.let(manager::parse) ?: r.magnet?.let { manager.fetchMagnet(it) } }.getOrNull()
            loading.value = false
            if (m == null) { error.value = if (r.raw != null) R.string.torrent_bad_file else R.string.torrent_no_metadata; return@launch }
            if (torrents.value.any { it.infoHash == m.infoHash }) error.value = R.string.torrent_exists
            meta.value = m
            wanted.value = m.files.map { it.index }.toSet()
        }
    }

    fun toggle(i: Int) { wanted.value = if (i in wanted.value) wanted.value - i else wanted.value + i }

    fun start(done: () -> Unit) {
        val m = meta.value ?: return
        viewModelScope.launch { manager.add(m, wanted.value, seedAfter.value, ratio.value, request?.magnet); done() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TorrentAddSheet(req: TorrentRequest, onDismiss: () -> Unit, vm: TorrentViewModel = hiltViewModel()) {
    LaunchedEffect(req) { vm.open(req) }
    val meta by vm.meta.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    val wanted by vm.wanted.collectAsState()
    val seed by vm.seedAfter.collectAsState()
    val ratio by vm.ratio.collectAsState()
    val up by vm.upLimit.collectAsState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        LazyColumn(Modifier.navigationBarsPadding()) {
            item {
                Text(stringResource(R.string.torrent_add), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 20.dp))
                if (loading) Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.heightIn(max = 20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.padding(6.dp))
                    Text(stringResource(R.string.torrent_fetching), color = T.extra.secondaryText)
                }
                error?.let { Text(stringResource(it), color = T.extra.danger, modifier = Modifier.padding(20.dp)) }
            }
            meta?.let { m ->
                item {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                        Text(m.name, style = MaterialTheme.typography.titleMedium)
                        val wantedSize = m.files.filter { it.index in wanted }.sumOf { it.size }
                        Text(com.tadaffuq.app.core.pr(R.plurals.torrent_summary, m.files.size, wanted.size, m.files.size, Fmt.bytes(wantedSize)), color = T.extra.secondaryText, style = MaterialTheme.typography.bodySmall)
                        // توضيح استهلاك البيانات والرفع قبل البدء
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.padding(top = 8.dp)) {
                            Text(sr(R.string.torrent_upload_note, Fmt.bytes(wantedSize), if (up > 0) Fmt.speed(up.toLong()) else stringResource(R.string.unlimited)),
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
                        }
                        Row(Modifier.padding(top = 6.dp)) {
                            TextButton(onClick = { vm.wanted.value = m.files.map { it.index }.toSet() }) { Text(stringResource(R.string.select_all)) }
                            TextButton(onClick = { vm.wanted.value = emptySet() }) { Text(stringResource(R.string.select_none)) }
                        }
                    }
                }
                items(m.files, key = { it.index }) { f ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(f.index in wanted, { vm.toggle(f.index) })
                        Column(Modifier.weight(1f)) {
                            Text(f.path.substringAfter('/'), style = MaterialTheme.typography.bodyMedium)
                            Text(Fmt.bytes(f.size), style = MaterialTheme.typography.bodySmall, color = T.extra.secondaryText)
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.torrent_seed_after), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.torrent_seed_after_desc), style = MaterialTheme.typography.bodySmall, color = T.extra.secondaryText)
                        }
                        Switch(seed, { vm.seedAfter.value = it })
                    }
                    if (seed) SliderRow(stringResource(R.string.torrent_ratio), stringResource(R.string.torrent_ratio_desc), ratio, 0.5f..5f, 8,
                        Fmt.iso(String.format(java.util.Locale.ROOT, "%.1f", ratio)), { vm.ratio.value = it })
                    Button(onClick = { vm.start(onDismiss) }, enabled = wanted.isNotEmpty() && error != R.string.torrent_exists,
                        modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 48.dp)) { Text(stringResource(R.string.add_start)) }
                }
            }
        }
    }
}

fun torrentStateText(s: TorrentState?): Int = when (s) {
    TorrentState.CHECKING -> R.string.torrent_checking
    TorrentState.METADATA -> R.string.torrent_fetching
    TorrentState.DOWNLOADING -> R.string.st_running
    TorrentState.FINISHED -> R.string.st_completed
    TorrentState.SEEDING -> R.string.torrent_seeding
    TorrentState.PAUSED -> R.string.st_paused
    TorrentState.ERROR -> R.string.st_failed
    else -> R.string.st_queued
}

@Composable
fun TorrentCard(t: TorrentEntity, s: TorrentInfoSnapshot?, onClick: () -> Unit) {
    com.tadaffuq.app.ui.components.Card(onClick = onClick) {
        Text(t.name, style = MaterialTheme.typography.titleSmall)
        val done = s?.wantedDone ?: if (t.state == TorrentRunState.COMPLETED) t.wantedSize else 0
        Text(buildString {
            append(Fmt.bytes(done)).append(" / ").append(Fmt.bytes(t.wantedSize))
            if (s != null) {
                append(" · ↓ ").append(Fmt.speed(s.downBps.toLong())).append(" · ↑ ").append(Fmt.speed(s.upBps.toLong()))
            }
        }, style = MaterialTheme.typography.bodySmall, color = T.extra.secondaryText)
        Spacer(Modifier.height(8.dp))
        ProgressLine(if (t.wantedSize > 0) done.toFloat() / t.wantedSize else 0f, T.extra.chartLine)
        Row(Modifier.padding(top = 6.dp)) {
            val state = if (t.state == TorrentRunState.PAUSED) TorrentState.PAUSED else if (t.state == TorrentRunState.COMPLETED && s?.state != TorrentState.SEEDING) TorrentState.FINISHED else s?.state
            Text(stringResource(R.string.torrent_badge) + " · " + stringResource(torrentStateText(state)), style = MaterialTheme.typography.labelMedium, color = T.extra.chartLine, modifier = Modifier.weight(1f))
            if (s != null) Text(com.tadaffuq.app.core.pr(R.plurals.peers_n, s.peers, s.peers) + " · " + com.tadaffuq.app.core.pr(R.plurals.seeds_n, s.seeds, s.seeds), style = MaterialTheme.typography.labelMedium, color = T.extra.secondaryText)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TorrentDetailSheet(hash: String, onDismiss: () -> Unit, vm: TorrentViewModel = hiltViewModel()) {
    val torrents by vm.torrents.collectAsState()
    val live by vm.live.collectAsState()
    val t = torrents.firstOrNull { it.infoHash == hash } ?: return
    val s = live[hash]
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var peers by remember { mutableStateOf(vm.manager.peers(hash)) }
    var confirmRemove by remember { mutableStateOf(false) }
    LaunchedEffect(hash) { while (true) { peers = vm.manager.peers(hash); delay(2_000) } }
    val meta = remember(t.infoHash) { runCatching { vm.manager.parse(t.raw) }.getOrNull() }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp)) {
            Text(t.name, style = MaterialTheme.typography.titleLarge)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                StatTile(stringResource(R.string.downloaded), "${Fmt.bytes(s?.wantedDone ?: 0)} / ${Fmt.bytes(t.wantedSize)}", Modifier.weight(1f))
                StatTile(stringResource(R.string.torrent_ratio), Fmt.iso(String.format(java.util.Locale.ROOT, "%.2f", s?.ratio ?: (if (t.downloaded > 0) t.uploaded.toFloat() / t.downloaded else 0f))), Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth()) {
                StatTile("↓", Fmt.speed((s?.downBps ?: 0).toLong()), Modifier.weight(1f))
                StatTile("↑", Fmt.speed((s?.upBps ?: 0).toLong()), Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth()) {
                StatTile(stringResource(R.string.torrent_uploaded), Fmt.bytes(s?.uploaded ?: t.uploaded), Modifier.weight(1f))
                StatTile(stringResource(R.string.torrent_peers), com.tadaffuq.app.core.pr(R.plurals.peers_n, s?.peers ?: 0, s?.peers ?: 0) + " · " + com.tadaffuq.app.core.pr(R.plurals.seeds_n, s?.seeds ?: 0, s?.seeds ?: 0), Modifier.weight(1f))
            }
            s?.error?.let { Text(it, color = T.extra.danger, style = MaterialTheme.typography.bodySmall) }

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.torrent_seed_after), style = MaterialTheme.typography.bodyLarge)
                    Text(sr(R.string.torrent_seed_rule, Fmt.iso(String.format(java.util.Locale.ROOT, "%.1f", t.ratioLimit))), style = MaterialTheme.typography.bodySmall, color = T.extra.secondaryText)
                }
                Switch(t.seedAfter, { v -> scope.launch { vm.manager.setSeeding(hash, v, t.ratioLimit) } })
            }

            if (meta != null) {
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.torrent_files), style = MaterialTheme.typography.titleSmall)
                val wanted = t.wantedSet()
                meta.files.forEach { f ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(f.index in wanted, { on -> scope.launch { vm.manager.setWanted(hash, if (on) wanted + f.index else wanted - f.index) } })
                        Column(Modifier.weight(1f)) {
                            Text(f.path.substringAfter('/'), style = MaterialTheme.typography.bodyMedium)
                            Text(Fmt.bytes(f.size), style = MaterialTheme.typography.bodySmall, color = T.extra.secondaryText)
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(sr(R.string.torrent_peers_n, peers.size), style = MaterialTheme.typography.titleSmall)
            if (peers.isEmpty()) Text(stringResource(R.string.torrent_no_peers), color = T.extra.secondaryText, style = MaterialTheme.typography.bodySmall)
            peers.take(30).forEach { p ->
                Text("${Fmt.iso(p.ip)} · ${p.client.ifBlank { "?" }} · ↓ ${Fmt.speed(p.downBps.toLong())} · ↑ ${Fmt.speed(p.upBps.toLong())} · ${Fmt.iso("${(p.progress * 100).toInt()}%")}",
                    style = MaterialTheme.typography.bodySmall, color = T.extra.secondaryText)
            }

            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (t.state == TorrentRunState.PAUSED || s?.state == TorrentState.PAUSED) AssistChip({ scope.launch { vm.manager.resume(hash) } }, { Text(stringResource(R.string.action_resume)) })
                else AssistChip({ scope.launch { vm.manager.pause(hash) } }, { Text(stringResource(R.string.action_pause)) })
                AssistChip({ scope.launch { vm.manager.recheck(hash) } }, { Text(stringResource(R.string.torrent_recheck)) })
                AssistChip({
                    (vm.manager.magnet(hash) ?: t.magnet)?.let { ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("magnet", it)) }
                }, { Text(stringResource(R.string.torrent_copy_magnet)) })
                AssistChip({ confirmRemove = true }, { Text(stringResource(R.string.action_delete)) })
            }
            Spacer(Modifier.height(20.dp))
        }
    }
    if (confirmRemove) {
        var withFiles by remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { confirmRemove = false }, title = { Text(stringResource(R.string.torrent_remove)) },
            text = { Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(withFiles, { withFiles = it }); Text(stringResource(R.string.delete_files_too)) } },
            confirmButton = { TextButton(onClick = { scope.launch { vm.manager.remove(hash, withFiles); confirmRemove = false; onDismiss() } }) { Text(stringResource(R.string.action_delete), color = T.extra.danger) } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.cancel)) } })
    }
}
