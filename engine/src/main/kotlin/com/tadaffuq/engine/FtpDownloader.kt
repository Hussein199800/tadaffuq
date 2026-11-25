package com.tadaffuq.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.KeyStore
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * FTP وFTPS (TLS صريح عبر AUTH TLS). شهادة الخادم تُتحقق بسلسلة الثقة واسم المضيف
 * مثل HTTPS تماماً؛ لا خيار لتعطيل التحقق. الاستئناف بأمر REST، والتحقق بـ SIZE وMDTM.
 */
class FtpDownloader(private val t: RemoteTarget, private val creds: Credentials) : StreamOpener {

    private fun connect(): FTPClient {
        val c: FTPClient = if (t.scheme == "ftps") FTPSClient(false).apply {
            trustManager = creds.testTrustManager ?: systemTrust()
            hostnameVerifier = if (creds.testTrustManager != null) null else HttpsURLConnection.getDefaultHostnameVerifier()
            isEndpointCheckingEnabled = creds.testTrustManager == null
        } else FTPClient()
        c.connectTimeout = 15_000
        c.setDataTimeout(java.time.Duration.ofSeconds(30))
        c.controlEncoding = "UTF-8"
        try {
            c.connect(t.host, t.port)
            if (!FTPReply.isPositiveCompletion(c.replyCode)) throw DownloadException(DownloadError.Http(c.replyCode))
            if (c is FTPSClient) { c.execPBSZ(0); c.execPROT("P") }
            val pass = if (t.user == "anonymous") (creds.password(t) ?: "anonymous@") else creds.password(t).orEmpty()
            if (!c.login(t.user, pass)) throw DownloadException(DownloadError.AuthFailed)
            c.enterLocalPassiveMode()
            c.setFileType(FTP.BINARY_FILE_TYPE)
            return c
        } catch (e: Exception) {
            runCatching { c.disconnect() }
            throw when (e) {
                is DownloadException -> e
                is IOException -> DownloadException(DownloadError.Network(NetFail.of(e)), e)
                else -> e
            }
        }
    }

    override suspend fun probe(): ProbeResult = withContext(Dispatchers.IO) {
        val c = connect()
        try {
            val size = c.mlistFile(t.path)?.size?.takeIf { it >= 0 }
                ?: c.sendCommand("SIZE", t.path).let { if (it == 213) c.replyString.trim().substringAfterLast(' ').toLongOrNull() else null }
                ?: run {
                    if (c.listFiles(t.path).isEmpty()) throw DownloadException(DownloadError.NotFound)
                    -1L
                }
            val mdtm = c.getModificationTime(t.path)?.trim()?.substringAfterLast(' ')
            // REST مدعوم إن ردّ الخادم 350 على REST 0
            val rest = c.sendCommand("REST", "0") == 350
            ProbeResult(
                finalUrl = "${t.scheme}://${t.user}@${t.host}:${t.port}${t.path}",
                totalBytes = size, acceptRanges = rest && size > 0, etag = null, lastModified = mdtm,
                fileName = FileNames.sanitize(t.path.substringAfterLast('/').ifBlank { "download" }), mimeType = null,
            )
        } catch (e: IOException) {
            throw DownloadException(DownloadError.Network(NetFail.of(e)), e)
        } finally { runCatching { c.logout(); c.disconnect() } }
    }

    override suspend fun open(offset: Long): Pair<InputStream, () -> Unit> = withContext(Dispatchers.IO) {
        val c = connect()
        if (offset > 0) c.restartOffset = offset
        val input = c.retrieveFileStream(t.path)
        if (input == null) {
            val code = c.replyCode
            runCatching { c.disconnect() }
            throw DownloadException(if (code == 550) DownloadError.NotFound else DownloadError.Http(code))
        }
        val wrapped = object : FilterInputStream(input) {}
        wrapped to { runCatching { input.close(); c.completePendingCommand() }; runCatching { c.logout(); c.disconnect() }; Unit }
    }

    companion object {
        suspend fun probe(t: RemoteTarget, creds: Credentials) = FtpDownloader(t, creds).probe()

        fun systemTrust(): X509TrustManager {
            val f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            f.init(null as KeyStore?)
            return f.trustManagers.filterIsInstance<X509TrustManager>().first()
        }
    }
}
