package ceui.pixiv.net.auth

import ceui.pixiv.net.abstractions.TokenStore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RealTokenRefresherTest {
    private fun fakeStore(rt: String?) = object : TokenStore {
        var access: String? = "old-at"
        override val isLoggedIn get() = access != null
        override fun getAccessToken() = access
        override fun getRefreshToken() = rt
        override fun saveTokens(accessToken: String?, refreshToken: String?, userJson: String?) {
            access = accessToken
        }
        override fun clear() { access = null }
    }
    private class FakeExchange(val resp: OAuthTokenResponse, val shouldThrow: Boolean = false) : TokenExchange() {
        override suspend fun refreshToken(refreshToken: String): OAuthTokenResponse {
            if (shouldThrow) throw RuntimeException("network error")
            return resp
        }
    }

    @Test fun `refresh succeeds returns new token and persists`() = runBlocking {
        val store = fakeStore("old-rt")
        val refresher = RealTokenRefresher(store, FakeExchange(OAuthTokenResponse("new-at", "new-rt")))
        assertEquals("new-at", refresher.refreshAccessToken("old-at"))
        assertEquals("new-at", store.getAccessToken())
    }
    @Test fun `no refresh token returns null`() = runBlocking {
        val store = fakeStore(null)
        val refresher = RealTokenRefresher(store, FakeExchange(OAuthTokenResponse("x", "y")))
        assertNull(refresher.refreshAccessToken("old-at"))
    }
    @Test fun `exchange throws returns null`() = runBlocking {
        val store = fakeStore("old-rt")
        val refresher = RealTokenRefresher(store, FakeExchange(OAuthTokenResponse("x", "y"), shouldThrow = true))
        assertNull(refresher.refreshAccessToken("old-at"))
    }
}
