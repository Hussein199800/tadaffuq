package com.tadaffuq.app.ui.more

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Rule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.tadaffuq.app.R
import com.tadaffuq.app.core.Backup
import com.tadaffuq.app.core.Fmt
import com.tadaffuq.app.core.sr
import com.tadaffuq.app.download.StorageDirs
import com.tadaffuq.app.ui.MainViewModel
import com.tadaffuq.app.ui.components.Card
import com.tadaffuq.app.ui.components.EmptyState
import com.tadaffuq.app.ui.theme.T
import com.tadaffuq.app.vault.VaultItem
import com.tadaffuq.data.Rule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ===== قواعد التنظيم =====

@Composable
fun RulesScreen(vm: MainViewModel, padding: PaddingValues, onBack: () -> Unit) {
    val s by vm.settings.collectAsState()
    val rules = s.rules
    var editing by remember { mutableStateOf<Rule?>(null) }
    fun save(list: List<Rule>) = vm.updateSettings { it.copy(rulesJson = Rule.listTo(list)) }
    LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp)) {
        item { SubTitle(stringResource(R.string.rules), stringResource(R.string.rules_desc), onBack) }
        item { Button(onClick = { editing = Rule(name = "") }, modifier = Modifier.padding(horizontal = 16.dp)) { Text(stringResource(R.string.rule_add)) } }
        if (rules.isEmpty()) item { EmptyState(Icons.Rounded.Rule, stringResource(R.string.rules_empty), stringResource(R.string.rules_empty_text)) }
        items(rules, key = { it.id }) { r ->
            Card(onClick = { editing = r }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.name.ifBlank { stringResource(R.string.rule_unnamed) }, style = MaterialTheme.typography.titleSmall)
                        Text(ruleSummary(r), style = MaterialTheme.typography.bodySmall, color = T.extra.secondaryText)
                    }
                    Switch(r.enabled, { v -> save(rules.map { if (it.id == r.id) it.copy(enabled = v) else it }) })
                }
            }
        }
    }
    editing?.let { r ->
        RuleDialog(r, onDismiss = { editing = null }, onDelete = { save(rules.filterNot { it.id == r.id }); editing = null }) { nr ->
            save(if (rules.any { it.id == nr.id }) rules.map { if (it.id == nr.id) nr else it } else rules + nr); editing = null
        }
    }
}

@Composable
private fun ruleSummary(r: Rule): String = listOfNotNull(
    r.extensions.takeIf { it.isNotBlank() }?.let { sr(R.string.rule_sum_ext, it) },
    r.hostContains.takeIf { it.isNotBlank() }?.let { sr(R.string.rule_sum_host, Fmt.iso(it)) },
    r.minSizeMb.takeIf { it > 0 }?.let { sr(R.string.rule_sum_size, it) },
    r.folder.takeIf { it.isNotBlank() }?.let { sr(R.string.rule_sum_folder, it) },
    r.priority.takeIf { it != 0 }?.let { sr(R.string.rule_sum_priority, it) },
    r.speedLimitKbps.takeIf { it > 0 }?.let { sr(R.string.rule_sum_speed, it) },
    if (r.extract) stringResource(R.string.add_extract) else null,
).joinToString(" · ")

@Composable
private fun RuleDialog(r: Rule, onDismiss: () -> Unit, onDelete: () -> Unit, onSave: (Rule) -> Unit) {
    var name by remember { mutableStateOf(r.name) }
    var ext by remember { mutableStateOf(r.extensions) }
    var host by remember { mutableStateOf(r.hostContains) }
    var size by remember { mutableStateOf(if (r.minSizeMb > 0) r.minSizeMb.toString() else "") }
    var folder by remember { mutableStateOf(r.folder) }
    var prio by remember { mutableStateOf(if (r.priority != 0) r.priority.toString() else "") }
    var speed by remember { mutableStateOf(if (r.speedLimitKbps > 0) r.speedLimitKbps.toString() else "") }
    var extract by remember { mutableStateOf(r.extract) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.rule_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.rule_name)) }, singleLine = true)
                Text(stringResource(R.string.rule_when), style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(ext, { ext = it }, label = { Text(stringResource(R.string.rule_ext)) }, singleLine = true, supportingText = { Text("iso, img, zip") })
                OutlinedTextField(host, { host = it.trim() }, label = { Text(stringResource(R.string.rule_host)) }, singleLine = true)
                OutlinedTextField(size, { size = it }, label = { Text(stringResource(R.string.rule_size)) }, singleLine = true)
                Text(stringResource(R.string.rule_then), style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(folder, { folder = it }, label = { Text(stringResource(R.string.rule_folder)) }, singleLine = true, supportingText = { Text(sr(R.string.rule_folder_desc, StorageDirs.ROOT)) })
                OutlinedTextField(prio, { prio = it }, label = { Text(stringResource(R.string.priority)) }, singleLine = true)
                OutlinedTextField(speed, { speed = it }, label = { Text(stringResource(R.string.rule_speed)) }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) { Switch(extract, { extract = it }); Text(stringResource(R.string.add_extract), modifier = Modifier.padding(start = 8.dp)) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(r.copy(name = name.trim(), extensions = ext, hostContains = host, minSizeMb = Fmt.parseNumber(size)?.toLong() ?: 0,
                    folder = Rule.safeFolder(folder), priority = Fmt.parseNumber(prio)?.toInt() ?: 0, speedLimitKbps = Fmt.parseNumber(speed)?.toLong() ?: 0, extract = extract))
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { Row { TextButton(onClick = onDelete) { Text(stringResource(R.string.action_delete), color = T.extra.danger) }; TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } } })
}


// ===== المجلد الخاص =====

@Composable
fun VaultScreen(vm: MainViewModel, padding: PaddingValues, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val vault = vm.vault
    var unlocked by remember { mutableStateOf(false) }
    var items by remember { mutableStateOf<List<VaultItem>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    val canAuth = remember { BiometricManager.from(ctx).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL) }

    fun refresh() = scope.launch { items = runCatching { withContext(Dispatchers.IO) { vault.list() } }.getOrElse { error = it.message; emptyList() } }

    fun authenticate() {
        val act = ctx as? FragmentActivity ?: return
        runCatching { vault.ensureKey() }.onFailure { error = it.message; return }
        val prompt = BiometricPrompt(act, ContextCompat.getMainExecutor(ctx), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { unlocked = true; refresh() }
        })
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle(ctx.getString(R.string.vault))
            .setSubtitle(ctx.getString(R.string.vault_prompt))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL).build())
    }
    LaunchedEffect(Unit) { vault.clearTemp() }

    LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp)) {
        item { SubTitle(stringResource(R.string.vault), stringResource(R.string.vault_desc), onBack) }
        item { Card { Text(stringResource(R.string.vault_limits), style = MaterialTheme.typography.bodySmall, color = T.extra.secondaryText) } }
        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) item {
            Card { Text(stringResource(R.string.vault_need_lock), color = T.extra.warning) }
        } else if (!unlocked) item {
            EmptyState(Icons.Rounded.Lock, stringResource(R.string.vault_locked), stringResource(R.string.vault_locked_text), stringResource(R.string.vault_unlock)) { authenticate() }
        } else {
            item { Text(stringResource(R.string.vault_add_hint), style = MaterialTheme.typography.bodySmall, color = T.extra.secondaryText, modifier = Modifier.padding(horizontal = 20.dp)) }
            if (items.isEmpty()) item { EmptyState(Icons.Rounded.Lock, stringResource(R.string.vault_empty), stringResource(R.string.vault_empty_text)) }
            items(items, key = { it.id }) { it2 ->
                Card {
                    Text(it2.name, style = MaterialTheme.typography.bodyLarge)
                    Text(Fmt.bytes(it2.size) + " · " + Fmt.date(it2.addedAt), style = MaterialTheme.typography.bodySmall, color = T.extra.secondaryText)
                    Row {
                        TextButton(onClick = {
                            scope.launch {
                                runCatching { withContext(Dispatchers.IO) { vault.openTemp(it2) } }
                                    .onSuccess { f -> runCatching { ctx.startActivity(StorageDirs.openIntentCache(ctx, f)) } }
                                    .onFailure { e -> if (e is android.security.keystore.UserNotAuthenticatedException) unlocked = false else error = e.message }
                            }
                        }) { Text(stringResource(R.string.action_open)) }
                        TextButton(onClick = {
                            scope.launch {
                                runCatching { withContext(Dispatchers.IO) { val d = java.io.File(StorageDirs.directRoot(), it2.name).let { f -> java.io.File(f.parentFile, com.tadaffuq.engine.FileNames.uniqueIn(f.parentFile!!.apply { mkdirs() }, f.name)) }; vault.export(it2, d); d } }
                                    .onSuccess { Toast.makeText(ctx, ctx.getString(R.string.vault_exported, it.name), Toast.LENGTH_SHORT).show() }
                                    .onFailure { e -> if (e is android.security.keystore.UserNotAuthenticatedException) unlocked = false else error = e.message }
                            }
                        }) { Text(stringResource(R.string.vault_export)) }
                        TextButton(onClick = { scope.launch { runCatching { withContext(Dispatchers.IO) { vault.delete(it2) } }; refresh() } }) { Text(stringResource(R.string.delete_forever), color = T.extra.danger) }
                    }
                }
            }
        }
        error?.let { e -> item { Text(e, color = T.extra.danger, modifier = Modifier.padding(20.dp)) } }
    }
}

// ===== النسخ الاحتياطي =====

@Composable
fun BackupScreen(vm: MainViewModel, padding: PaddingValues, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                val json = vm.exportBackup()
                withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)!!.use { it.write(json.toByteArray()) } }
            }.onSuccess { message = ctx.getString(R.string.backup_done) }.onFailure { message = it.message }
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                val text = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) } }
                vm.importBackup(text)
            }.onSuccess { r -> message = ctx.getString(R.string.restore_done, r.tasks, r.links, r.bookmarks) }
                .onFailure { message = ctx.getString(R.string.restore_failed) }
        }
    }
    LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp)) {
        item { SubTitle(stringResource(R.string.backup), stringResource(R.string.backup_desc), onBack) }
        item {
            Card {
                Text(stringResource(R.string.backup_what), style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { create.launch("tadaffuq-backup-${Fmt.day(System.currentTimeMillis())}.json") }) { Text(stringResource(R.string.backup_export)) }
                    OutlinedButton(onClick = { open.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Text(stringResource(R.string.backup_import)) }
                }
                message?.let { Text(it, modifier = Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.secondary) }
            }
        }
    }
}
