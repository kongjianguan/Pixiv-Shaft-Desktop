package ceui.pixiv.net.api

import ceui.pixiv.net.NettyQuicInterceptor
import ceui.pixiv.net.abstractions.LanguageProvider
import ceui.pixiv.net.abstractions.Logger
import ceui.pixiv.net.abstractions.Settings
import ceui.pixiv.net.abstractions.TokenRefresher
import ceui.pixiv.net.abstractions.TokenStore
import ceui.pixiv.net.config.PixivConstants
import ceui.pixiv.net.interceptor.HeaderInterceptor
import ceui.pixiv.net.interceptor.TokenFetcherInterceptor
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
) {

    private val quicInterceptor = NettyQuicInterceptor()

    private val okhttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        .addInterceptor(HeaderInterceptor(tokenStore, lang))
        .addInterceptor(TokenFetcherInterceptor(tokenStore, refresher))
        .addInterceptor(HttpLoggingInterceptor().apply {
            setLevel(HttpLoggingInterceptor.Level.BODY)
        })
        .apply { if (settings.isDirectConnect) addInterceptor(quicInterceptor) }
        .build()

    val appApi: API = Retrofit.Builder()
        .baseUrl(PixivConstants.APP_API_HOST)
        .addConverterFactory(GsonConverterFactory.create())
        .client(okhttpClient)
        .build()
        .create(API::class.java)

    fun close() {
        quicInterceptor.close()
    }
}
