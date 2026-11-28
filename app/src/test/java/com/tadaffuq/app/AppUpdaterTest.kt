package com.tadaffuq.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tadaffuq.app.update.AppUpdater
import com.tadaffuq.app.update.UpdateState
import com.tadaffuq.data.SettingsRepository
import com.tadaffuq.engine.ReleaseAsset
import com.tadaffuq.engine.SelfUpdate
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.random.Random

/** التحديث لا يثبّت إلا حزمة تطابق بصمتها المنشورة وموقّعة بشهادة التطبيق نفسها. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AppUpdaterTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val server = MockWebServer().apply { start() }
    private val data = Random(7).nextBytes(300_000)
    private fun sha(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    @After fun tearDown() { server.shutdown() }

    private fun updater() = AppUpdater(ctx, com.tadaffuq.app.download.ClientProvider(com.tadaffuq.app.core.SecretStore(ctx)), SettingsRepository(ctx))
    private fun candidate(sha256: String): SelfUpdate.Candidate {
        val name = "tadaffuq-9.9.9-999-abcdef1-github-arm64-v8a-release.apk"
        return SelfUpdate.Candidate("9.9.9", 999, "arm64-v8a", ReleaseAsset(name, data.size.toLong(), server.url("/$name").toString(), sha256, 0), sha256, "", "v9.9.9")
    }
    private fun updateDir() = File(ctx.cacheDir, "update")

    @Test fun checksumMismatchIsRejectedAndDeleted() = runBlocking {
        server.enqueue(MockResponse().setBody(Buffer().write(data)))
        val u = updater()
        u.downloadAndInstall(candidate("0".repeat(64)))
        val s = u.state.value
        assertTrue("$s", s is UpdateState.Failed && s.reason == UpdateState.Reason.CHECKSUM)
        assertTrue("nothing left", updateDir().listFiles().orEmpty().isEmpty())
    }

    @Test fun validChecksumButNotASignedApkIsNotInstalled() = runBlocking {
        server.enqueue(MockResponse().setBody(Buffer().write(data)))
        val u = updater()
        u.downloadAndInstall(candidate(sha(data)))
        val s = u.state.value
        assertTrue("$s", s is UpdateState.Failed && s.reason == UpdateState.Reason.SIGNATURE)
        assertFalse("rejected package deleted", updateDir().listFiles().orEmpty().any { it.name.endsWith(".apk") })
    }

    /** 1.4.0 (مراجعة Codex): فشل جلب البصمات لا يُعرض «لديك أحدث إصدار». */
    @Test fun unverifiableIsNotUpToDate() {
        org.junit.Assert.assertTrue(AppUpdater.stateFor(null, verifyFailed = true, manual = true) is com.tadaffuq.app.update.UpdateState.Failed)
        org.junit.Assert.assertEquals(com.tadaffuq.app.update.UpdateState.UpToDate, AppUpdater.stateFor(null, verifyFailed = false, manual = true))
        org.junit.Assert.assertEquals(com.tadaffuq.app.update.UpdateState.Idle, AppUpdater.stateFor(null, verifyFailed = true, manual = false))
    }

    @Test fun networkErrorIsReported() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        val u = updater()
        u.downloadAndInstall(candidate(sha(data)))
        assertEquals(UpdateState.Reason.NETWORK, (u.state.value as UpdateState.Failed).reason)
    }
}
