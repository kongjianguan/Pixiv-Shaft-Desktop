package ceui.pixiv.net.interceptor

import ceui.pixiv.net.config.PixivConstants
import okhttp3.Interceptor
import okhttp3.Response

/** Headers for anonymous Pixiv web Ajax requests. No app OAuth token is sent. */
class WebHeaderInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("accept", "application/json")
            .header("accept-language", "zh-CN,zh;q=0.9,en;q=0.8")
            .header("referer", "https://www.pixiv.net/")
            .header("user-agent", PixivConstants.WEB_USER_AGENT)
            .build()
        return chain.proceed(request)
    }
}
