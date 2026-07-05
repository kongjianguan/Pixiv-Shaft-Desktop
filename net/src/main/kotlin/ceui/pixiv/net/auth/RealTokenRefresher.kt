package ceui.pixiv.net.auth

import ceui.pixiv.net.abstractions.TokenRefresher
import ceui.pixiv.net.abstractions.TokenStore
import kotlin.coroutines.cancellation.CancellationException

class RealTokenRefresher(
    private val tokenStore: TokenStore,
    private val tokenExchange: TokenExchange,
) : TokenRefresher {

    override suspend fun refreshAccessToken(currentAccessToken: String?): String? = try {
        val refreshToken = tokenStore.getRefreshToken() ?: return null
        val resp = tokenExchange.refreshToken(refreshToken)
        if (resp.accessToken != null) {
            tokenStore.saveTokens(resp.accessToken, resp.refreshToken)
            resp.accessToken
        } else null
    } catch (e: CancellationException) { throw e
    } catch (e: Exception) {
        null
    }
}
