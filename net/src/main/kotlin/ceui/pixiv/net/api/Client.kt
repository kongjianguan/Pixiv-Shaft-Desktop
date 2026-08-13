package ceui.pixiv.net.api

import ceui.pixiv.net.NettyQuicInterceptor
import ceui.pixiv.net.abstractions.LanguageProvider
import ceui.pixiv.net.abstractions.Logger
import ceui.pixiv.net.abstractions.Settings
import ceui.pixiv.net.abstractions.TokenRefresher
import ceui.pixiv.net.abstractions.TokenStore
import ceui.pixiv.net.config.PixivConstants
import ceui.pixiv.net.ech.EchInterceptor
import ceui.pixiv.net.interceptor.HeaderInterceptor
import ceui.pixiv.net.interceptor.TokenFetcherInterceptor
import ceui.pixiv.net.interceptor.WebHeaderInterceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class Client(
    private val settings: Settings,
    tokenStore: TokenStore,
    refresher: TokenRefresher,
    lang: LanguageProvider,
    private val logger: Logger,
    // 测试注入点：不传时用 Retrofit 构建真实 API；传了则完全替换
    api: API? = null,
    webApi: PixivWebApi? = null,
    comicApi: ComicApi? = null,
) {

    private val quicInterceptor = NettyQuicInterceptor()

    private val appOkhttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        .addInterceptor(HeaderInterceptor(tokenStore, lang))
        .addInterceptor(TokenFetcherInterceptor(tokenStore, refresher))
        .addInterceptor(HttpLoggingInterceptor().apply {
            setLevel(HttpLoggingInterceptor.Level.BODY)
        })
        // ECH 优先（加密 SNI 的 TCP 直连），失败自动回退 QUIC
        .apply {
            if (settings.isDirectConnect) {
                addInterceptor(EchInterceptor())
                addInterceptor(quicInterceptor)
            }
        }
        .build()

    private val webOkhttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        .addInterceptor(WebHeaderInterceptor())
        .addInterceptor(HttpLoggingInterceptor().apply {
            setLevel(HttpLoggingInterceptor.Level.BASIC)
        })
        .apply {
            if (settings.isDirectConnect) {
                addInterceptor(EchInterceptor())
                addInterceptor(quicInterceptor)
            }
        }
        .build()

    val appApi: API = api ?: Retrofit.Builder()
        .baseUrl(PixivConstants.APP_API_HOST)
        .addConverterFactory(GsonConverterFactory.create())
        .client(appOkhttpClient)
        .build()
        .create(API::class.java)

    val webApi: PixivWebApi = webApi ?: Retrofit.Builder()
        .baseUrl(PixivConstants.WEB_API_HOST)
        .addConverterFactory(GsonConverterFactory.create())
        .client(webOkhttpClient)
        .build()
        .create(PixivWebApi::class.java)

    val comicApi: ComicApi = comicApi ?: Retrofit.Builder()
        .baseUrl(PixivConstants.COMIC_API_HOST)
        .addConverterFactory(GsonConverterFactory.create())
        .client(appOkhttpClient)
        .build()
        .create(ComicApi::class.java)

    fun close() {
        quicInterceptor.close()
    }
}
