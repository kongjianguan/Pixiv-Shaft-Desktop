package ceui.pixiv.net.ech

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * 真实网络冒烟测试：ECH 链路必须能到达 Pixiv 服务器（任意 HTTP 响应都算到达；
 * 未带 token 时 app-api 返回 400/401 即证明 TLS+HTTP 全通）。
 *
 * 原生库未加载（如 CI 上未构建 dylib）时自动跳过，不阻塞其他测试。
 */
class EchSmokeTest {

    @Test
    fun `ech request reaches pixiv app api`() {
        assumeTrue(EchClient.available, "ECH native library not loaded")
        val client = OkHttpClient.Builder()
            .addInterceptor(EchInterceptor())
            .build()
        val request = Request.Builder()
            .url("https://app-api.pixiv.net/v1/illust/recommended?include_ranking_illusts=false&include_privacy_policy=true&filter=for_ios")
            .build()
        val code = client.newCall(request).execute().use { it.code }
        println("[EchSmokeTest] app-api via ECH: HTTP $code")
        assertTrue(code in 400..500, "expected a server response (4xx/5xx), got $code")
    }
}
