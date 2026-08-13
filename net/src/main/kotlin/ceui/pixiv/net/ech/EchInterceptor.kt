package ceui.pixiv.net.ech

import ceui.pixiv.net.PixivHosts
import okhttp3.Interceptor
import okhttp3.Response

/**
 * ECH（加密 ClientHello）拦截器：对 Pixiv API host 优先走 ECH 直连，
 * 失败时回退链上后续拦截器（QUIC），ECH 完全不可用时应用仍可工作。
 *
 * 链位置：在 QUIC 拦截器之前（Logging 之后），因此：
 * - ECH 请求/响应照常被 HttpLoggingInterceptor 记录（BODY 级）；
 * - ECH 抛异常 → chain.proceed 继续走 QUIC；
 * - HTTP 4xx/5xx 是服务器正常响应，不会触发回退。
 */
class EchInterceptor(
    private val echClient: EchClient = EchClient,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!PixivHosts.shouldEch(request.url.host)) return chain.proceed(request)
        if (!echClient.available) return chain.proceed(request)
        return try {
            echClient.execute(request)
        } catch (e: Exception) {
            // ECH 链路失败（网络/配置/库）→ 回退 QUIC
            println("[EchInterceptor] ECH failed for ${request.url.host}: ${e.message}")
            chain.proceed(request)
        }
    }
}
