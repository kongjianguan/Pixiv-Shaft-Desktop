package ceui.pixiv.net.interceptor

import ceui.pixiv.net.abstractions.TokenRefresher
import ceui.pixiv.net.abstractions.TokenStore
import ceui.pixiv.net.config.PixivConstants
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class TokenFetcherInterceptorTest {

    private class FakeChain(
        initialRequest: Request,
        private val responses: List<Response>,
    ) : Interceptor.Chain {
        private val current = initialRequest
        val proceededRequests = mutableListOf<Request>()
        private var callIndex = 0

        override fun request(): Request = current
        override fun proceed(request: Request): Response {
            proceededRequests += request
            val resp = responses.getOrNull(callIndex)
                ?: error("chain.proceed called more times than expected (call #${callIndex + 1})")
            callIndex++
            return resp.newBuilder().request(request).build()
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

    private class FakeTokenRefresher(val newToken: String?) : TokenRefresher {
        var refreshCalled = false
        var callCount = 0
        var receivedAccessToken: String? = null
        override suspend fun refreshAccessToken(currentAccessToken: String?): String? {
            refreshCalled = true
            callCount++
            receivedAccessToken = currentAccessToken
            return newToken
        }
    }

    private fun loggedInTokenStore() = object : TokenStore {
        override val isLoggedIn: Boolean = true
        override fun getAccessToken(): String? = "old-at"
        override fun getRefreshToken(): String? = "old-rt"
        override fun saveTokens(accessToken: String?, refreshToken: String?, userJson: String?) {}
        override fun clear() {}
    }

    private fun build400Response(bodyText: String): Response {
        val mediaType = "text/plain".toMediaTypeOrNull()
        return Response.Builder()
            .request(Request.Builder().url("https://app-api.pixiv.net/v1/test").build())
            .protocol(Protocol.HTTP_1_1)
            .code(400)
            .message("Bad Request")
            .body(bodyText.toResponseBody(mediaType))
            .build()
    }

    private fun build200Response(): Response {
        return Response.Builder()
            .request(Request.Builder().url("https://app-api.pixiv.net/v1/test").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("".toResponseBody(null))
            .build()
    }

    private fun authedInitialRequest(token: String = "old-at"): Request =
        Request.Builder()
            .url("https://app-api.pixiv.net/v1/test")
            .header(PixivConstants.HEADER_AUTH, PixivConstants.TOKEN_HEAD + token)
            .build()

    @Test
    fun `400 with invalid refresh token body triggers refresh and retries with new token`() {
        val refresher = FakeTokenRefresher(newToken = "new-at")
        val interceptor = TokenFetcherInterceptor(loggedInTokenStore(), refresher)
        val chain = FakeChain(
            authedInitialRequest(),
            listOf(build400Response(PixivConstants.TOKEN_ERROR_2), build200Response()),
        )

        val response = interceptor.intercept(chain)

        assertEquals(200, response.code, "Should return the retried 200 response")
        assertTrue(refresher.refreshCalled, "refreshAccessToken must be called on 400 token-error body")
        assertEquals(1, refresher.callCount, "refreshAccessToken should be called exactly once")
        assertEquals(
            "old-at",
            refresher.receivedAccessToken,
            "refresher should receive the access token extracted from the original auth header",
        )
        assertEquals(2, chain.proceededRequests.size, "chain.proceed should be called twice (initial + retry)")
        val retriedRequest = chain.proceededRequests[1]
        assertEquals(
            PixivConstants.TOKEN_HEAD + "new-at",
            retriedRequest.header(PixivConstants.HEADER_AUTH),
            "retry request must carry the refreshed bearer token",
        )
    }

    @Test
    fun `400 with oauth process error body also triggers refresh`() {
        val refresher = FakeTokenRefresher(newToken = "new-at")
        val interceptor = TokenFetcherInterceptor(loggedInTokenStore(), refresher)
        val chain = FakeChain(
            authedInitialRequest(),
            listOf(build400Response(PixivConstants.TOKEN_ERROR_1), build200Response()),
        )

        val response = interceptor.intercept(chain)

        assertEquals(200, response.code)
        assertTrue(refresher.refreshCalled, "refreshAccessToken must be called on TOKEN_ERROR_1 too")
        assertEquals("old-at", refresher.receivedAccessToken)
    }

    @Test
    fun `200 pass-through does not trigger refresh`() {
        val refresher = FakeTokenRefresher(newToken = "new-at")
        val interceptor = TokenFetcherInterceptor(loggedInTokenStore(), refresher)
        val chain = FakeChain(authedInitialRequest(), listOf(build200Response()))

        val response = interceptor.intercept(chain)

        assertEquals(200, response.code)
        assertFalse(refresher.refreshCalled, "refreshAccessToken must NOT be called on 200")
        assertEquals(1, chain.proceededRequests.size, "chain.proceed should be called once (no retry)")
        assertEquals(
            PixivConstants.TOKEN_HEAD + "old-at",
            chain.proceededRequests[0].header(PixivConstants.HEADER_AUTH),
            "request should pass through unchanged on 200",
        )
    }

    @Test
    fun `400 without token error body passes through without refresh`() {
        val refresher = FakeTokenRefresher(newToken = "new-at")
        val interceptor = TokenFetcherInterceptor(loggedInTokenStore(), refresher)
        val chain = FakeChain(authedInitialRequest(), listOf(build400Response("some other server error")))

        val response = interceptor.intercept(chain)

        assertEquals(400, response.code, "400 without token-error body should pass through")
        assertFalse(refresher.refreshCalled, "refresh must not be called when body is not a token error")
        assertEquals(1, chain.proceededRequests.size, "no retry when body is not a token error")
    }

    @Test
    fun `400 with token error body when logged out does not refresh`() {
        val refresher = FakeTokenRefresher(newToken = "new-at")
        val loggedOutStore = object : TokenStore {
            override val isLoggedIn: Boolean = false
            override fun getAccessToken(): String? = null
            override fun getRefreshToken(): String? = null
            override fun saveTokens(accessToken: String?, refreshToken: String?, userJson: String?) {}
            override fun clear() {}
        }
        val interceptor = TokenFetcherInterceptor(loggedOutStore, refresher)
        val chain = FakeChain(authedInitialRequest(), listOf(build400Response(PixivConstants.TOKEN_ERROR_2)))

        val response = interceptor.intercept(chain)

        assertEquals(400, response.code, "logged out: 400 should pass through")
        assertFalse(refresher.refreshCalled, "refresh must not be called when logged out")
        assertEquals(1, chain.proceededRequests.size, "no retry when logged out")
    }

    @Test
    fun `400 token error when refresher returns null does not retry`() {
        val refresher = FakeTokenRefresher(newToken = null)
        val interceptor = TokenFetcherInterceptor(loggedInTokenStore(), refresher)
        val chain = FakeChain(authedInitialRequest(), listOf(build400Response(PixivConstants.TOKEN_ERROR_2)))

        val response = interceptor.intercept(chain)

        assertTrue(refresher.refreshCalled, "refresh should still be attempted")
        assertEquals(400, response.code, "when refresh returns null, original 400 response should be returned")
        assertEquals(1, chain.proceededRequests.size, "no retry when refresh returns null")
    }
}
