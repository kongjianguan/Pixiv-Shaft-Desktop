@file:OptIn(coil3.annotation.DelicateCoilApi::class)
package ceui.pixiv.di

import ceui.pixiv.image.ImageLoaderFactory
import ceui.pixiv.net.NettyQuicInterceptor
import ceui.pixiv.net.api.Client
import ceui.pixiv.net.auth.RealTokenRefresher
import ceui.pixiv.net.auth.TokenExchange
import ceui.pixiv.net.impl.DefaultLanguageProvider
import ceui.pixiv.net.impl.StdoutLogger
import ceui.pixiv.store.Database
import ceui.pixiv.store.KeychainTokenStore
import ceui.pixiv.store.SettingsStore
import ceui.pixiv.store.createDatabase
import ceui.pixiv.ui.auth.AuthState
import coil3.ImageLoader
import coil3.SingletonImageLoader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object AppContainer {
    lateinit var client: Client
        private set
    lateinit var tokenStore: KeychainTokenStore
        private set
    lateinit var settingsStore: SettingsStore
        private set
    lateinit var imageLoader: ImageLoader
        private set
    lateinit var imageClient: OkHttpClient
        private set
    lateinit var database: Database
        private set

    lateinit var tokenExchange: TokenExchange
        private set
    private var oauthQuicInterceptor: NettyQuicInterceptor? = null

    private val _authState = MutableStateFlow<AuthState>(AuthState.LoggedOut)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    fun updateAuthState() {
        _authState.value = if (tokenStore.isLoggedIn) AuthState.LoggedIn()
                           else AuthState.LoggedOut
    }

    fun init() {
        settingsStore = SettingsStore()
        tokenStore = KeychainTokenStore()

        // QUIC-enabled OkHttpClient for OAuth (no HeaderInterceptor, no TokenFetcherInterceptor)
        val oauthClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .apply {
                if (settingsStore.isDirectConnect) {
                    oauthQuicInterceptor = NettyQuicInterceptor()
                    addInterceptor(oauthQuicInterceptor!!)
                }
            }
            .build()
        tokenExchange = TokenExchange(oauthClient)
        val refresher = RealTokenRefresher(tokenStore, tokenExchange)
        client = Client(settingsStore, tokenStore, refresher, DefaultLanguageProvider(), StdoutLogger)
        imageLoader = ImageLoaderFactory.create(settingsStore)
        imageClient = ImageLoaderFactory.createImageClient(settingsStore)
        SingletonImageLoader.setUnsafe(imageLoader)
        database = createDatabase()

        updateAuthState()
    }

    fun close() {
        client.close()
        oauthQuicInterceptor?.close()
    }
}
