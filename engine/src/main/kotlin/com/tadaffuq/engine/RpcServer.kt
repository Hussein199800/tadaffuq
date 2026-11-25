package com.tadaffuq.engine

import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** ما يوفره التطبيق لواجهة RPC. كل الردود JSON. */
interface RpcHandler {
    fun status(): String
    fun tasks(): String
    /** يستخرج الروابط من أي نص (سطر لكل رابط أو JSON) ويضيفها؛ يعيد عددها. */
    fun add(text: String, referer: String?): Int
    fun pause(id: Long): Boolean
    fun resume(id: Long): Boolean
}

/**
 * خادم HTTP صغير وموثّق في docs/RPC.md. مغلق افتراضياً. يستمع على 127.0.0.1 فقط،
 * أو على كل الواجهات إن سمح المستخدم صراحة (لاستقبال روابط من الحاسوب). كل طلب يحتاج
 * الرمز (Authorization: Bearer) ويُقارن بزمن ثابت. لا ترويسات CORS: صفحات الويب لا تستطيع استدعاءه.
 * هذا ليس تنفيذاً لواجهة aria2 ولا يدّعي التوافق معها.
 */
class RpcServer(
    private val port: Int,
    private val allowNetwork: Boolean,
    private val token: String,
    private val handler: RpcHandler,
) {
    private var socket: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(4) { r -> Thread(r, "tadaffuq-rpc").apply { isDaemon = true } }
    private val active = AtomicInteger()
    val boundPort: Int get() = socket?.localPort ?: -1

    fun start() {
        require(token.length >= 16) { "token too short" }
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(if (allowNetwork) InetAddress.getByName("0.0.0.0") else InetAddress.getLoopbackAddress(), port), 16)
        socket = s
        Thread({
            while (!s.isClosed) {
                val c = runCatching { s.accept() }.getOrNull() ?: break
                if (active.incrementAndGet() > 8) { active.decrementAndGet(); c.close(); continue }
                pool.execute { try { serve(c) } finally { active.decrementAndGet() } }
            }
        }, "tadaffuq-rpc-accept").apply { isDaemon = true }.start()
    }

    fun stop() { runCatching { socket?.close() }; pool.shutdownNow() }

    private fun serve(c: Socket) = c.use { sock ->
        sock.soTimeout = 10_000
        val input = BufferedInputStream(sock.getInputStream())
        val out = sock.getOutputStream()
        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 2) return respond(out, 400, "{\"error\":\"bad request\"}")
        val method = parts[0]
        val target = parts[1]
        val headers = HashMap<String, String>()
        while (true) {
            val l = readLine(input) ?: break
            if (l.isEmpty()) break
            val k = l.substringBefore(':').trim().lowercase()
            headers[k] = l.substringAfter(':', "").trim()
            if (headers.size > 50) return respond(out, 431, "{\"error\":\"headers\"}")
        }
        val path = target.substringBefore('?')
        val auth = headers["authorization"]?.removePrefix("Bearer ")?.trim()
        if (auth == null || !MessageDigest.isEqual(auth.toByteArray(), token.toByteArray())) return respond(out, 401, "{\"error\":\"unauthorized\"}")
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        if (len > MAX_BODY) return respond(out, 413, "{\"error\":\"too large\"}")
        val body = ByteArray(len).also { var o = 0; while (o < len) { val n = input.read(it, o, len - o); if (n < 0) break; o += n } }.toString(Charsets.UTF_8)
        try {
            when {
                method == "GET" && path == "/api/status" -> respond(out, 200, handler.status())
                method == "GET" && path == "/api/tasks" -> respond(out, 200, handler.tasks())
                method == "POST" && path == "/api/add" -> {
                    val referer = target.substringAfter("referer=", "").substringBefore('&').takeIf { it.isNotEmpty() }?.let { URLDecoder.decode(it, "UTF-8") }
                    val n = handler.add(body, referer)
                    respond(out, if (n > 0) 200 else 422, "{\"added\":$n}")
                }
                method == "POST" && path.startsWith("/api/pause/") -> idOp(out, path) { handler.pause(it) }
                method == "POST" && path.startsWith("/api/resume/") -> idOp(out, path) { handler.resume(it) }
                else -> respond(out, 404, "{\"error\":\"not found\"}")
            }
        } catch (e: Exception) {
            respond(out, 500, "{\"error\":\"internal\"}")
        }
    }

    private fun idOp(out: OutputStream, path: String, op: (Long) -> Boolean) {
        val id = path.substringAfterLast('/').toLongOrNull() ?: return respond(out, 400, "{\"error\":\"id\"}")
        val ok = op(id)
        respond(out, if (ok) 200 else 404, "{\"ok\":$ok}")
    }

    private fun readLine(input: BufferedInputStream): String? {
        val b = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (b.isEmpty()) null else b.toString()
            if (c == '\n'.code) return b.toString().trimEnd('\r')
            b.append(c.toChar())
            if (b.length > 8192) return null
        }
    }

    private fun respond(out: OutputStream, code: Int, json: String) {
        val bytes = json.toByteArray()
        val reason = when (code) { 200 -> "OK"; 401 -> "Unauthorized"; 404 -> "Not Found"; 413 -> "Payload Too Large"; 422 -> "Unprocessable"; else -> "Error" }
        out.write("HTTP/1.1 $code $reason\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(bytes)
        out.flush()
    }

    companion object {
        const val MAX_BODY = 256 * 1024
        fun newToken(): String = ByteArray(24).also { java.security.SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        fun esc(s: String) = buildString { s.forEach { c -> when (c) { '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t"); else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c) } } }
    }
}
