package ceui.pixiv.net.auth

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class OAuthCallbackServer(port: Int = 0) : AutoCloseable {
    private val codeFuture = CompletableFuture<String>()
    private val server: HttpServer = HttpServer.create(InetSocketAddress(port), 0).apply {
        createContext("/callback") { exchange ->
            val query = exchange.requestURI.rawQuery ?: ""
            val code = query.split("&")
                .mapNotNull { it.split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
                .firstOrNull { it.first == "code" }?.second
            val resp = if (code != null) {
                codeFuture.complete(code)
                """<html><body><h2>Login successful</h2><p>You can close this window.</p></body></html>"""
            } else {
                """<html><body><h2>Login failed</h2><p>No code received.</p></body></html>"""
            }
            val bytes = resp.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        start()
    }

    val actualPort: Int get() = server.address.port
    val redirectUri: String get() = "http://localhost:$actualPort/callback"

    fun waitForCode(timeoutMs: Long = 120_000): String? = try {
        codeFuture.get(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (e: TimeoutException) { null } catch (e: ExecutionException) { null }

    override fun close() {
        codeFuture.cancel(false)
        server.stop(0)
    }
}
