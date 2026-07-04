package ceui.pixiv.net.interceptor

import ceui.pixiv.net.abstractions.LanguageProvider
import ceui.pixiv.net.abstractions.TokenStore
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class HeaderInterceptorTest {

    private class FakeChain(initialRequest: Request) : Interceptor.Chain {
        var proceededRequest: Request? = null
        private val current = initialRequest
        override fun request(): Request = current
        override fun proceed(request: Request): Response {
            proceededRequest = request
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("".toResponseBody(null))
                .build()
        }
        override fun call() = throw UnsupportedOperationException()
        override fun connection(): Connection? = null
        override fun connectTimeoutMillis() = 0
        override fun readTimeoutMillis() = 0
        override fun writeTimeoutMillis() = 0
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    }

    private fun loggedInTokenStore(token: String) = object : TokenStore {
        override val isLoggedIn: Boolean = true
        override fun getAccessToken(): String? = token
        override fun getRefreshToken(): String? = null
        override fun saveTokens(accessToken: String?, refreshToken: String?, userJson: String?) {}
        override fun clear() {}
    }

    private val lang = object : LanguageProvider {
        override fun acceptLanguage() = "en"
        override fun appAcceptLanguage() = "en-US"
    }

    @Test
    fun `logged in request gets bearer auth and all pixiv headers`() {
        val tokenStore = loggedInTokenStore("at")
        val interceptor = HeaderInterceptor(tokenStore, lang)
        val initial = Request.Builder().url("https://app-api.pixiv.net/v1/test").build()
        val chain = FakeChain(initial)

        interceptor.intercept(chain)

        val req = chain.proceededRequest ?: error("chain.proceed was not invoked")
        assertEquals("Bearer at", req.header("authorization"))
        assertEquals("ios", req.header("app-os"))
        assertEquals("8.6.10", req.header("app-version"))
        assertEquals("26.5", req.header("app-os-version"))
        assertEquals("PixivIOSApp/8.6.10 (iOS 26.5; iPhone16,2)", req.header("user-agent"))
        assertEquals("en", req.header("accept-language"))
        assertEquals("en-US", req.header("app-accept-language"))
        val hash = req.header("x-client-hash")
        assertNotNull(hash)
        assertTrue(hash!!.isNotEmpty(), "x-client-hash must be non-empty")
        assertEquals(32, hash.length, "x-client-hash should be an MD5 hex digest")
        val time = req.header("x-client-time")
        assertNotNull(time)
        assertTrue(time!!.contains("T"), "x-client-time should look like an ISO timestamp")
    }

    @Test
    fun `logged out request omits authorization but keeps other headers`() {
        val tokenStore = object : TokenStore {
            override val isLoggedIn: Boolean = false
            override fun getAccessToken(): String? = null
            override fun getRefreshToken(): String? = null
            override fun saveTokens(accessToken: String?, refreshToken: String?, userJson: String?) {}
            override fun clear() {}
        }
        val interceptor = HeaderInterceptor(tokenStore, lang)
        val initial = Request.Builder().url("https://app-api.pixiv.net/v1/test").build()
        val chain = FakeChain(initial)

        interceptor.intercept(chain)

        val req = chain.proceededRequest!!
        assertNull(req.header("authorization"), "Authorization must be omitted when logged out")
        assertEquals("ios", req.header("app-os"))
        assertTrue(req.header("x-client-hash")!!.isNotEmpty())
    }

    @Test
    fun `x client hash is consistent with request nonce for same time`() {
        val tokenStore = loggedInTokenStore("at")
        val interceptor = HeaderInterceptor(tokenStore, lang)
        val initial = Request.Builder().url("https://app-api.pixiv.net/v1/test").build()
        val chain = FakeChain(initial)

        interceptor.intercept(chain)

        val req = chain.proceededRequest!!
        val time = req.header("x-client-time")!!
        val hash = req.header("x-client-hash")!!
        val expected = ceui.pixiv.net.RequestNonce.forTime(time).xClientHash
        assertEquals(expected, hash, "x-client-hash must match RequestNonce algorithm for the emitted time")
    }
}
