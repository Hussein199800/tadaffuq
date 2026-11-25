package com.tadaffuq.engine

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.ssl.SslConfigurationFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.TransferRatePermission
import org.apache.ftpserver.usermanager.impl.WritePermission
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.ServerSocket
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import kotlin.random.Random

/** FTP وFTPS وSFTP على خوادم حقيقية تعمل داخل الاختبار. */
class RemoteProtocolsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val client = HttpClients.create()
    private val content = Random(11).nextBytes(3 * 1024 * 1024 + 17)

    private fun freePort() = ServerSocket(0).use { it.localPort }

    private class Recorder : DownloadListener {
        @Volatile var last: ResumeState? = null
        val bytes = AtomicLong()
        @Volatile var restarted = false
        override fun onStart(probe: ProbeResult, restarted: Boolean) { this.restarted = restarted }
        override suspend fun persist(state: ResumeState) { last = state }
        override fun onBytes(count: Long) { bytes.addAndGet(count) }
    }

    private class Creds(val pass: String?, var hostKey: String? = null, override val testTrustManager: TrustManager? = null) : Credentials {
        override fun password(target: RemoteTarget) = pass
        override fun knownHostKey(host: String, port: Int) = hostKey
    }

    private fun download(url: String, f: File, creds: Credentials, saved: ResumeState? = null, rec: Recorder = Recorder()) =
        Protocols.downloader(client, RequestSpec(url), f, DownloadConfig(baseRetryDelayMs = 20, persistIntervalMs = 100), saved, RateLimiter(), RateLimiter(), rec, creds)

    // ===== FTP =====

    private fun ftpServer(root: File, port: Int, ssl: SslConfigurationFactory? = null, rateBps: Int = 0): org.apache.ftpserver.FtpServer {
        val f = FtpServerFactory()
        val l = ListenerFactory().apply {
            this.port = port
            if (ssl != null) { sslConfiguration = ssl.createSslConfiguration(); isImplicitSsl = false }
        }
        f.addListener("default", l.createListener())
        val um = org.apache.ftpserver.usermanager.PropertiesUserManagerFactory().createUserManager()
        um.save(BaseUser().apply {
            name = "u"; password = "secret"; homeDirectory = root.absolutePath
            authorities = listOf(WritePermission(), TransferRatePermission(rateBps, 0))
        })
        f.userManager = um
        return f.createServer().apply { start() }
    }

    @Test fun ftpDownloadPauseResumeIntact() = runBlocking {
        val root = tmp.newFolder("ftp"); File(root, "a.bin").writeBytes(content)
        val port = freePort()
        val server = ftpServer(root, port, rateBps = 600 * 1024)
        try {
            val url = "ftp://u@127.0.0.1:$port/a.bin"
            val probe = Protocols.probe(client, RequestSpec(url), Creds("secret"))
            assertEquals(content.size.toLong(), probe.totalBytes)
            assertTrue("REST supported", probe.resumable)
            val f = tmp.newFile("a.part")
            val rec = Recorder()
            val job = async { download(url, f, Creds("secret"), rec = rec).run() }
            withTimeout(30_000) { while ((rec.last?.segments?.single()?.downloaded ?: 0) < 600_000) delay(50) }
            job.cancelAndJoin()
            val saved = rec.last!!
            val done = saved.segments.single().downloaded
            val rec2 = Recorder()
            download(url, f, Creds("secret"), saved, rec2).run()
            assertFalse(rec2.restarted)
            assertEquals(content.size - done, rec2.bytes.get())
            assertArrayEquals(content, f.readBytes())
        } finally { server.stop() }
    }

    @Test fun ftpWrongPasswordIsAuthFailed() = runBlocking {
        val root = tmp.newFolder("ftp2"); File(root, "a.bin").writeBytes(content)
        val port = freePort()
        val server = ftpServer(root, port)
        try {
            Protocols.probe(client, RequestSpec("ftp://u@127.0.0.1:$port/a.bin"), Creds("wrong")); fail()
        } catch (e: DownloadException) {
            assertEquals(DownloadError.AuthFailed, e.error)
        } finally { server.stop() }
    }

    private fun selfSigned(): File {
        val ks = File(tmp.root, "ks.jks")
        val p = ProcessBuilder("keytool", "-genkeypair", "-alias", "s", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=127.0.0.1",
            "-ext", "SAN=ip:127.0.0.1", "-validity", "2", "-keystore", ks.path, "-storepass", "changeit", "-keypass", "changeit", "-storetype", "JKS")
            .redirectErrorStream(true).start()
        p.inputStream.readBytes(); p.waitFor()
        return ks
    }

    @Test fun ftpsExplicitTlsWithTrustedCertAndRejectsUntrusted() = runBlocking {
        val root = tmp.newFolder("ftps"); File(root, "a.bin").writeBytes(content)
        val ks = selfSigned()
        val ssl = SslConfigurationFactory().apply { keystoreFile = ks; keystorePassword = "changeit"; keyPassword = "changeit" }
        val port = freePort()
        val server = ftpServer(root, port, ssl)
        try {
            val url = "ftps://u@127.0.0.1:$port/a.bin"
            // شهادة ذاتية التوقيع وغير موثوقة لدى النظام: يجب الرفض، لا التجاوز
            try { Protocols.probe(client, RequestSpec(url), Creds("secret")); fail("untrusted cert accepted") }
            catch (e: DownloadException) { assertTrue("${e.error}", e.error is DownloadError.Network) }
            // في الاختبار فقط نثق بهذه الشهادة صراحة
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                init(KeyStore.getInstance("JKS").apply { ks.inputStream().use { load(it, "changeit".toCharArray()) } })
            }.trustManagers.first()
            val f = tmp.newFile("s.part")
            download(url, f, Creds("secret", testTrustManager = trust)).run()
            assertArrayEquals(content, f.readBytes())
        } finally { server.stop() }
    }

    // ===== SFTP =====

    private fun sshServer(root: File, port: Int, hostKeyFile: File): SshServer = SshServer.setUpDefaultServer().apply {
        this.port = port
        keyPairProvider = SimpleGeneratorHostKeyProvider(hostKeyFile.toPath())
        setPasswordAuthenticator { u, p, _ -> u == "u" && p == "secret" }
        subsystemFactories = listOf(SftpSubsystemFactory())
        fileSystemFactory = VirtualFileSystemFactory(root.toPath())
        start()
    }

    @Test fun sftpHostKeyTrustOnFirstUseThenDownloadAndResume() = runBlocking {
        val root = tmp.newFolder("sftp"); File(root, "a.bin").writeBytes(content)
        val port = freePort()
        val keyFile = File(tmp.root, "hostkey.ser")
        val server = sshServer(root, port, keyFile)
        try {
            val url = "sftp://u@127.0.0.1:$port/a.bin"
            val creds = Creds("secret")
            val fp = try { Protocols.probe(client, RequestSpec(url), creds); fail(); "" } catch (e: DownloadException) {
                val err = e.error
                assertTrue("$err", err is DownloadError.HostKeyUnknown)
                (err as DownloadError.HostKeyUnknown).fingerprint
            }
            assertTrue(fp.startsWith("SHA256:"))
            creds.hostKey = fp // المستخدم يوافق على البصمة
            val probe = Protocols.probe(client, RequestSpec(url), creds)
            assertEquals(content.size.toLong(), probe.totalBytes)

            // استئناف: نصف الملف موجود من جلسة سابقة بنفس الحجم ووقت التعديل
            val f = tmp.newFile("sf.part")
            val half = content.size / 2L
            f.writeBytes(content.copyOf(half.toInt()))
            val saved = ResumeState(probe, listOf(SegmentState(0, 0, content.size - 1L, half)))
            val rec = Recorder()
            download(url, f, creds, saved, rec).run()
            assertEquals(content.size - half, rec.bytes.get())
            assertArrayEquals(content, f.readBytes())

            // مفتاح مختلف عن المحفوظ = رفض دائم
            creds.hostKey = "SHA256:somethingElse"
            try { Protocols.probe(client, RequestSpec(url), creds); fail() } catch (e: DownloadException) {
                assertTrue("${e.error}", e.error is DownloadError.HostKeyChanged)
            }
        } finally { server.stop(true) }
    }

    @Test fun passwordIsStrippedFromUrl() {
        assertEquals("ftp://user@host:21/a.zip", RemoteTarget.stripPassword("ftp://user:p%40ss@host:21/a.zip"))
        assertEquals("p@ss", RemoteTarget.passwordInUrl("ftp://user:p%40ss@host:21/a.zip"))
        assertEquals("anonymous", RemoteTarget.parse("ftp://host/x")!!.user)
        assertEquals(22, RemoteTarget.parse("sftp://u@h/x")!!.port)
    }
}
