package ceui.pixiv.net.interceptor

import ceui.pixiv.net.abstractions.TokenRefresher
import ceui.pixiv.net.abstractions.TokenStore
import ceui.pixiv.net.config.PixivConstants
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

class TokenFetcherInterceptor(
    private val tokenStore: TokenStore,
    private val refresher: TokenRefresher,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        return if (response.code == 400) {
            val gson = response.peekBody(Long.MAX_VALUE).string()
            // 未登录 / 已登出时不尝试刷新 token,直接返回 400,避免 refreshAccessToken→getAccessToken 抛 "account not found" 炸 OkHttp 线程
            if (tokenStore.isLoggedIn &&
                (gson.contains(PixivConstants.TOKEN_ERROR_1) || gson.contains(PixivConstants.TOKEN_ERROR_2))
            ) {
                response.close()
                val tokenForThisRequest = request.header(PixivConstants.HEADER_AUTH)
                    ?.substring(PixivConstants.TOKEN_HEAD.length) ?: ""
                val refreshedAccessToken = runBlocking { refresher.refreshAccessToken(tokenForThisRequest) }
                if (refreshedAccessToken != null) {
                    val newRequest = chain.request()
                        .newBuilder()
                        .header(PixivConstants.HEADER_AUTH, PixivConstants.TOKEN_HEAD + refreshedAccessToken)
                        .build()
                    chain.proceed(newRequest)
                } else {
                    response
                }
            } else {
                response
            }
        } else {
            response
        }
    }
}
