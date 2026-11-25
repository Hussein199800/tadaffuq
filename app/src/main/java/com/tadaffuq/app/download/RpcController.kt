package com.tadaffuq.app.download

import com.tadaffuq.app.core.SecretStore
import com.tadaffuq.data.AppDatabase
import com.tadaffuq.data.Rule
import com.tadaffuq.data.SettingsRepository
import com.tadaffuq.engine.FileNames
import com.tadaffuq.engine.LinkTools
import com.tadaffuq.engine.Protocols
import com.tadaffuq.engine.RpcHandler
import com.tadaffuq.engine.RpcServer
import com.tadaffuq.engine.RpcServer.Companion.esc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** يشغّل خادم RPC حسب الإعدادات. يعمل ما دامت عملية التطبيق حية (مفتوحاً أو أثناء التنزيل). */
@Singleton
class RpcController @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val secrets: SecretStore,
    private val manager: DownloadManager,
    private val db: AppDatabase,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: RpcServer? = null
    private val _state = MutableStateFlow<String?>(null)
    /** null = متوقف، وإلا العنوان الذي يستمع عليه. */
    val state: StateFlow<String?> = _state
    @Volatile var error: String? = null
        private set

    fun token(): String = secrets.get(SecretStore.RPC_TOKEN) ?: RpcServer.newToken().also { secrets.put(SecretStore.RPC_TOKEN, it) }
    fun rotateToken() { secrets.put(SecretStore.RPC_TOKEN, RpcServer.newToken()); restart() }

    fun start() {
        scope.launch {
            settingsRepo.settings.map { Triple(it.rpcEnabled, it.rpcAllowNetwork, it.rpcPort) }.distinctUntilChanged().collect { restart() }
        }
    }

    @Synchronized private fun restart() {
        server?.stop(); server = null; _state.value = null; error = null
        val s = runBlocking { settingsRepo.current() }
        if (!s.rpcEnabled) return
        runCatching {
            RpcServer(s.rpcPort, s.rpcAllowNetwork, token(), handler).also { it.start(); server = it }
        }.onSuccess { _state.value = (if (s.rpcAllowNetwork) "0.0.0.0" else "127.0.0.1") + ":" + it.boundPort }
            .onFailure { error = it.message }
    }

    private val handler = object : RpcHandler {
        override fun status(): String {
            val live = manager.live.value
            return "{\"running\":${manager.runningIds.value.size},\"speedBps\":${live.values.sumOf { it.speedBps }},\"held\":${manager.hold.value?.let { "\"$it\"" } ?: "null"}}"
        }

        override fun tasks(): String = runBlocking {
            db.tasks().all().filter { it.trashedAt == null }.takeLast(200).joinToString(",", "[", "]") {
                "{\"id\":${it.id},\"name\":\"${esc(it.fileName)}\",\"status\":\"${it.status}\",\"total\":${it.totalBytes},\"done\":${manager.live.value[it.id]?.downloaded ?: it.downloadedBytes}}"
            }
        }

        override fun add(text: String, referer: String?): Int = runBlocking {
            val s = settingsRepo.current()
            val urls = LinkTools.extract(text).filter(Protocols::isSupported).distinct().take(500)
            manager.enqueue(urls.map { u ->
                val name = FileNames.resolve(null, u, null)
                val rule = Rule.firstMatch(s.rules, name, u, -1)
                val dir = rule?.folder?.let(Rule::safeFolder)?.takeIf { it.isNotEmpty() }?.let { File(StorageDirs.root(), it).path }
                    ?: StorageDirs.dirFor(FileNames.category(name), s.sortByCategory).path
                NewTask(url = u, fileName = name, dirPath = dir, referer = referer?.takeIf { LinkTools.isSupported(it) },
                    priority = rule?.priority ?: 0, speedLimitBps = (rule?.speedLimitKbps ?: 0) * 1024, postExtract = rule?.extract == true)
            }).size
        }

        override fun pause(id: Long): Boolean = runBlocking { db.tasks().get(id)?.let { manager.pause(listOf(id)); true } ?: false }
        override fun resume(id: Long): Boolean = runBlocking { db.tasks().get(id)?.let { manager.resume(listOf(id)); true } ?: false }
    }
}
