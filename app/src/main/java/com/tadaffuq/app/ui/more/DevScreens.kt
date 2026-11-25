package com.tadaffuq.app.ui.more

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tadaffuq.app.BuildConfig
import com.tadaffuq.app.R
import com.tadaffuq.app.core.Fmt
import com.tadaffuq.app.core.TechLog
import com.tadaffuq.app.core.sr
import com.tadaffuq.app.download.AppCredentials
import com.tadaffuq.app.download.ClientProvider
import com.tadaffuq.app.download.DownloadManager
import com.tadaffuq.app.download.NewTask
import com.tadaffuq.app.download.RpcController
import com.tadaffuq.app.download.StorageDirs
import com.tadaffuq.app.ui.MainViewModel
import com.tadaffuq.app.ui.components.Card
import com.tadaffuq.app.ui.components.SectionHeader
import com.tadaffuq.app.ui.theme.T
import com.tadaffuq.data.SettingsRepository
import com.tadaffuq.engine.GitHubReleases
import com.tadaffuq.engine.HfFile
import com.tadaffuq.engine.HuggingFace
import com.tadaffuq.engine.Release
import com.tadaffuq.engine.ReleaseAsset
import com.tadaffuq.engine.SourceException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class DevViewModel @Inject constructor(
    private val clients: ClientProvider,
    private val settings: SettingsRepository,
    val manager: DownloadManager,
    val credentials: AppCredentials,
) : ViewModel() {
    val releases = MutableStateFlow<List<Release>>(emptyList())
    val sums = MutableStateFlow<Map<String, String>>(emptyMap())
    val hfFiles = MutableStateFlow<List<HfFile>>(emptyList())
    val loading = MutableStateFlow(false)
    val error = MutableStateFlow<SourceException.Kind?>(null)
    val message = MutableStateFlow<String?>(null)

    private suspend fun client() = clients.get(settings.current())

    fun loadGitHub(repo: String) = viewModelScope.launch {
        loading.value = true; error.value = null
        runCatching {
            val gh = GitHubReleases(client())
            val r = gh.releases(repo)
            releases.value = r
            sums.value = r.firstOrNull()?.let { gh.checksums(it) } ?: emptyMap()
        }.onFailure { error.value = (it as? SourceException)?.kind ?: SourceException.Kind.NETWORK }
        loading.value = false
    }

    fun loadHf(repo: String) = viewModelScope.launch {
        loading.value = true; error.value = null
        runCatching { hfFiles.value = HuggingFace(client()).files(repo, credentials.hfToken()) }
            .onFailure { error.value = (it as? SourceException)?.kind ?: SourceException.Kind.NETWORK }
        loading.value = false
    }

    /** كل ملف بمسار ثابت، والبصمة المنشورة تُتحقق بعد الاكتمال. */
    fun download(items: List<Triple<String, String, String?>>, folder: String) = viewModelScope.launch {
        val dir = File(StorageDirs.root(), folder).path
        manager.enqueue(items.map { (url, name, sha) -> NewTask(url = url, fileName = name, dirPath = dir, expectedHash = sha) })
        message.value = "ok"
    }
}

@Composable
fun DevScreen(padding: PaddingValues, onBack: () -> Unit, vm: DevViewModel = hiltViewModel()) {
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(0) }
    var repo by rememberSaveable { mutableStateOf("") }
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    val releases by vm.releases.collectAsState()
    val sums by vm.sums.collectAsState()
    val hf by vm.hfFiles.collectAsState()
    val msg by vm.message.collectAsState()
    var ggufOnly by rememberSaveable { mutableStateOf(true) }
    var chosen by remember { mutableStateOf(setOf<String>()) }
    var token by remember { mutableStateOf("") }
    val abis = remember { Build.SUPPORTED_ABIS.toList() }

    LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp)) {
        item { SubTitle(stringResource(R.string.dev), stringResource(R.string.dev_desc), onBack) }
        item {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(tab == 0, { tab = 0 }, label = { Text("GitHub Releases") })
                FilterChip(tab == 1, { tab = 1 }, label = { Text("Hugging Face") })
                FilterChip(tab == 2, { tab = 2 }, label = { Text(stringResource(R.string.tech_log)) })
            }
        }
        if (tab < 2) item {
            Column(Modifier.padding(16.dp)) {
                OutlinedTextField(repo, { repo = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(if (tab == 0) R.string.gh_repo else R.string.hf_repo)) },
                    supportingText = { Text(if (tab == 0) "owner/repo · https://github.com/owner/repo" else "org/model-GGUF") })
                if (tab == 1) {
                    OutlinedTextField(token, { token = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.hf_token)) }, visualTransformation = PasswordVisualTransformation(),
                        supportingText = { Text(stringResource(if (vm.credentials.hfToken() != null && token.isEmpty()) R.string.secret_saved else R.string.hf_token_desc)) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = repo.isNotBlank() && !loading, onClick = {
                        chosen = emptySet()
                        if (tab == 0) vm.loadGitHub(repo) else { if (token.isNotEmpty()) vm.credentials.setHfToken(token); vm.loadHf(repo) }
                    }) { Text(stringResource(if (loading) R.string.add_probing else R.string.dev_load)) }
                    if (tab == 1 && vm.credentials.hfToken() != null) TextButton(onClick = { vm.credentials.setHfToken(null); token = "" }) { Text(stringResource(R.string.hf_token_forget)) }
                }
                error?.let { Text(stringResource(sourceError(it)), color = T.extra.danger, style = MaterialTheme.typography.bodySmall) }
                msg?.let { Text(stringResource(R.string.dev_added), color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall) }
            }
        }
        if (tab == 0) {
            releases.forEachIndexed { idx, rel ->
                item { SectionHeader(rel.name + if (rel.prerelease) " · " + ctx.getString(R.string.prerelease) else "") }
                val ranked = GitHubReleases.rankForAbis(rel.assets, abis)
                items(ranked, key = { "${rel.tag}/${it.name}" }) { a ->
                    AssetRow(a, best = idx == 0 && a == ranked.firstOrNull() && a.name.endsWith(".apk", true), sha = if (idx == 0) sums[a.name] ?: a.sha256 else a.sha256) {
                        vm.download(listOf(Triple(a.url, a.name, if (idx == 0) sums[a.name] ?: a.sha256 else a.sha256)), "GitHub/" + repo.substringAfterLast('/'))
                    }
                }
            }
        }
        if (tab == 1 && hf.isNotEmpty()) {
            val shown = if (ggufOnly) hf.filter { it.isGguf } else hf
            item {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Switch(ggufOnly, { ggufOnly = it }); Text(stringResource(R.string.gguf_only), modifier = Modifier.padding(start = 8.dp).weight(1f))
                    TextButton(enabled = chosen.isNotEmpty(), onClick = {
                        vm.download(hf.filter { it.path in chosen }.map { Triple(it.url, it.path.substringAfterLast('/'), it.sha256) }, "HuggingFace/" + repo.substringAfterLast('/'))
                    }) { Text(sr(R.string.download_n, chosen.size)) }
                }
            }
            items(shown, key = { it.path }) { f ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(f.path in chosen, { on ->
                        // اختيار جزء من نموذج مجزأ يختار كل أجزائه
                        val group = f.shardGroup?.let { g -> HuggingFace.shards(hf, g).map { it.path } } ?: listOf(f.path)
                        chosen = if (on) chosen + group else chosen - group.toSet()
                    })
                    Column(Modifier.weight(1f)) {
                        Text(f.path, style = MaterialTheme.typography.bodyMedium)
                        Text(Fmt.bytes(f.size) + (if (f.sha256 != null) " · SHA-256" else "") + (f.shardGroup?.let { " · " + ctx.getString(R.string.shard) } ?: ""),
                            style = MaterialTheme.typography.bodySmall, color = T.extra.secondaryText)
                    }
                }
            }
        }
        if (tab == 2) item {
            Card {
                Text(stringResource(R.string.tech_log_desc), style = MaterialTheme.typography.bodySmall, color = T.extra.secondaryText)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val text = TechLog.export("Tadaffuq ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
                    }) { Text(stringResource(R.string.action_share)) }
                    OutlinedButton(onClick = {
                        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("log", TechLog.export("Tadaffuq ${BuildConfig.VERSION_NAME}")))
                    }) { Text(stringResource(R.string.copy)) }
                }
            }
        }
    }
}

private fun sourceError(k: SourceException.Kind) = when (k) {
    SourceException.Kind.NOT_FOUND -> R.string.src_not_found
    SourceException.Kind.RATE_LIMITED -> R.string.src_rate_limited
    SourceException.Kind.UNAUTHORIZED -> R.string.src_unauthorized
    SourceException.Kind.BAD_INPUT -> R.string.src_bad_input
    SourceException.Kind.NETWORK -> R.string.err_network
}

@Composable
private fun AssetRow(a: ReleaseAsset, best: Boolean, sha: String?, onDownload: () -> Unit) {
    Card(onClick = onDownload) {
        Text(a.name, style = MaterialTheme.typography.bodyLarge)
        Text(listOfNotNull(Fmt.bytes(a.size), if (sha != null) "SHA-256 ✓" else null, if (best) stringResource(R.string.best_for_device) else null).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = if (best) T.extra.success else T.extra.secondaryText)
    }
}

@Composable
fun RpcScreen(vm: MainViewModel, padding: PaddingValues, onBack: () -> Unit, rpc: RpcControllerHolder = hiltViewModel()) {
    val s by vm.settings.collectAsState()
    val state by rpc.controller.state.collectAsState()
    val ctx = LocalContext.current
    var showToken by remember { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp)) {
        item { SubTitle(stringResource(R.string.rpc), stringResource(R.string.rpc_desc), onBack) }
        item {
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.rpc_enable), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Switch(s.rpcEnabled, { v -> vm.updateSettings { it.copy(rpcEnabled = v) } })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.rpc_network), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.rpc_network_desc), style = MaterialTheme.typography.bodySmall, color = T.extra.warning)
                    }
                    Switch(s.rpcAllowNetwork, { v -> vm.updateSettings { it.copy(rpcAllowNetwork = v) } })
                }
                Text(state?.let { sr(R.string.rpc_listening, Fmt.iso(it)) } ?: stringResource(R.string.rpc_off), color = MaterialTheme.colorScheme.secondary)
                rpc.controller.error?.let { Text(it, color = T.extra.danger) }
                Row {
                    TextButton(onClick = { showToken = !showToken }) { Text(stringResource(R.string.rpc_show_token)) }
                    TextButton(onClick = { ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("token", rpc.controller.token())) }) { Text(stringResource(R.string.copy)) }
                    TextButton(onClick = { rpc.controller.rotateToken() }) { Text(stringResource(R.string.rpc_rotate)) }
                }
                if (showToken) Text(Fmt.iso(rpc.controller.token()), style = MaterialTheme.typography.bodySmall)
            }
        }
        item { Card { Text(stringResource(R.string.rpc_usage), style = MaterialTheme.typography.bodySmall) } }
    }
}

@HiltViewModel
class RpcControllerHolder @Inject constructor(val controller: RpcController) : ViewModel()
