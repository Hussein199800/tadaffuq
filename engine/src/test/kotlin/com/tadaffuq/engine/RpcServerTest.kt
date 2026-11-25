package com.tadaffuq.engine

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.Socket

class RpcServerTest {
    private val token = RpcServer.newToken()
    private val added = mutableListOf<String>()
    private val handler = object : RpcHandler {
        override fun status() = "{\"active\":1}"
        override fun tasks() = "[{\"id\":1,\"name\":\"${RpcServer.esc("ملف \"x\".zip")}\"}]"
        override fun add(text: String, referer: String?): Int = LinkTools.extract(text).filter(LinkTools::isSupported).also { added += it }.size
        override fun pause(id: Long) = id == 1L
        override fun resume(id: Long) = id == 1L
    }
    private var server: RpcServer? = null
    private val client = HttpClients.create()
    @After fun tearDown() { server?.stop() }

    private fun call(path: String, method: String = "GET", body: String? = null, tok: String? = token): Pair<Int, String> {
        val b = Request.Builder().url("http://127.0.0.1:${server!!.boundPort}$path")
        tok?.let { b.header("Authorization", "Bearer $it") }
        if (method == "POST") b.post((body ?: "").toRequestBody("text/plain".toMediaType()))
        return client.newCall(b.build()).execute().use { it.code to it.body!!.string() }
    }

    @Test fun authRequiredAndEndpointsWork() {
        server = RpcServer(0, allowNetwork = false, token = token, handler = handler).apply { start() }
        assertEquals(401, call("/api/status", tok = null).first)
        assertEquals(401, call("/api/status", tok = "wrong-token-wrong-token").first)
        assertEquals(200 to "{\"active\":1}", call("/api/status"))
        assertTrue(call("/api/tasks").second.contains("\\\"x\\\""))
        val (code, body) = call("/api/add?referer=https%3A%2F%2Fsite", "POST", "انظر https://a.org/x.zip و https://b.org/y.iso")
        assertEquals(200, code); assertEquals("{\"added\":2}", body)
        assertEquals(listOf("https://a.org/x.zip", "https://b.org/y.iso"), added)
        assertEquals(422, call("/api/add", "POST", "no links").first)
        assertEquals(200, call("/api/pause/1", "POST").first)
        assertEquals(404, call("/api/pause/9", "POST").first)
        assertEquals(404, call("/nope").first)
        assertEquals(413, call("/api/add", "POST", "x".repeat(RpcServer.MAX_BODY + 1)).first)
    }

    @Test fun localOnlyByDefault() {
        server = RpcServer(0, allowNetwork = false, token = token, handler = handler).apply { start() }
        val lan = NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
        if (lan != null) {
            val refused = runCatching { Socket(lan, server!!.boundPort).close() }.isFailure
            assertTrue("must not listen on $lan", refused)
        }
    }

    @Test fun shortTokenRejected() {
        try { RpcServer(0, false, "short", handler).start(); throw AssertionError() } catch (_: IllegalArgumentException) { }
    }
}
