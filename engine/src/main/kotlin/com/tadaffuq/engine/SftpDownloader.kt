package com.tadaffuq.engine

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64

/**
 * SFTP عبر JSch. مفتاح الخادم يُقارن ببصمة SHA-256 محفوظة (ثقة عند أول استعمال بموافقة المستخدم):
 * مفتاح مجهول ← HostKeyUnknown لتعرض الواجهة البصمة، ومفتاح تغيّر ← HostKeyChanged ويُرفض دائماً.
 */
class SftpDownloader(private val t: RemoteTarget, private val creds: Credentials) : StreamOpener {

    private fun session(): Session {
        val jsch = JSch()
        var seenFingerprint: String? = null
        jsch.hostKeyRepository = object : HostKeyRepository {
            override fun check(host: String?, key: ByteArray): Int {
                val fp = fingerprint(key)
                seenFingerprint = fp
                val known = creds.knownHostKey(t.host, t.port) ?: return HostKeyRepository.NOT_INCLUDED
                return if (known == fp) HostKeyRepository.OK else HostKeyRepository.CHANGED
            }
            override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
            override fun remove(host: String?, type: String?) = Unit
            override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
            override fun getKnownHostsRepositoryID() = "tadaffuq"
            override fun getHostKey(): Array<HostKey> = emptyArray()
            override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
        }
        val s = jsch.getSession(t.user, t.host, t.port)
        s.setPassword(creds.password(t).orEmpty())
        s.setConfig("StrictHostKeyChecking", "yes")
        s.setConfig("PreferredAuthentications", "password,keyboard-interactive")
        try {
            s.connect(15_000)
        } catch (e: JSchException) {
            val msg = e.message.orEmpty()
            val fp = seenFingerprint
            throw when {
                fp != null && msg.contains("HostKey has been changed", true) -> DownloadException(DownloadError.HostKeyChanged(t.host, fp))
                fp != null && (msg.contains("reject HostKey", true) || msg.contains("UnknownHostKey", true)) -> DownloadException(DownloadError.HostKeyUnknown(t.host, fp))
                msg.contains("Auth fail", true) || msg.contains("Auth cancel", true) -> DownloadException(DownloadError.AuthFailed)
                e.cause is java.io.IOException -> DownloadException(DownloadError.Network(NetFail.of(e.cause!!)), e)
                else -> DownloadException(DownloadError.Network(NetFail.RESET), e)
            }
        }
        return s
    }

    private fun <T> withSftp(block: (ChannelSftp, Session) -> T): T {
        val s = session()
        val ch = (s.openChannel("sftp") as ChannelSftp).apply { connect(15_000) }
        try {
            return block(ch, s)
        } catch (e: SftpException) {
            throw DownloadException(if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) DownloadError.NotFound else DownloadError.Storage("sftp ${e.id}"))
        } finally {
            ch.disconnect(); s.disconnect()
        }
    }

    override suspend fun probe(): ProbeResult = withContext(Dispatchers.IO) {
        withSftp { ch, _ ->
                val a = ch.stat(t.path)
                ProbeResult(
                    finalUrl = "sftp://${t.user}@${t.host}:${t.port}${t.path}",
                    totalBytes = a.size, acceptRanges = a.size > 0, etag = null, lastModified = a.mTime.toString(),
                    fileName = FileNames.sanitize(t.path.substringAfterLast('/').ifBlank { "download" }), mimeType = null,
                )
        }
    }

    override suspend fun open(offset: Long): Pair<InputStream, () -> Unit> = withContext(Dispatchers.IO) {
        val s = session()
        val ch = (s.openChannel("sftp") as ChannelSftp).apply { connect(15_000) }
        val input = try { ch.get(t.path, null, offset) } catch (e: SftpException) {
            ch.disconnect(); s.disconnect()
            throw DownloadException(if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) DownloadError.NotFound else DownloadError.Storage("sftp ${e.id}"))
        }
        object : FilterInputStream(input) {} to { runCatching { input.close() }; ch.disconnect(); s.disconnect(); Unit }
    }

    companion object {
        suspend fun probe(t: RemoteTarget, creds: Credentials) = SftpDownloader(t, creds).probe()

        fun fingerprint(key: ByteArray): String =
            "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(key))
    }
}
