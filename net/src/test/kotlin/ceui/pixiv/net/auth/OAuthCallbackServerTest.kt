package ceui.pixiv.net.auth

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.HttpURLConnection
import java.net.URL

class OAuthCallbackServerTest {
    @Test fun `receives code from callback request`() {
        OAuthCallbackServer(0).use { server ->
            val port = server.actualPort
            // 模拟浏览器重定向到 callback
            val url = URL("http://localhost:$port/callback?code=test-auth-code-123&state=xyz")
            val conn = url.openConnection() as HttpURLConnection
            assertEquals(200, conn.responseCode)
            conn.inputStream.readBytes()
            conn.disconnect()
            // waitForCode 应立即返回 code
            val code = server.waitForCode(1000)
            assertEquals("test-auth-code-123", code)
        }
    }
}
