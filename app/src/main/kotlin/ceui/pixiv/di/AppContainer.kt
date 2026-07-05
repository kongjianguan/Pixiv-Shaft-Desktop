package ceui.pixiv.di

import ceui.pixiv.image.ImageLoaderFactory
import ceui.pixiv.net.api.Client
import ceui.pixiv.net.auth.RealTokenRefresher
import ceui.pixiv.net.auth.TokenExchange
import ceui.pixiv.net.impl.DefaultLanguageProvider
import ceui.pixiv.net.impl.StdoutLogger
import ceui.pixiv.store.Database
import ceui.pixiv.store.KeychainTokenStore
import ceui.pixiv.store.SettingsStore
import ceui.pixiv.store.createDatabase
import coil3.ImageLoader

object AppContainer {
    lateinit var client: Client
        private set
    lateinit var tokenStore: KeychainTokenStore
        private set
    lateinit var settingsStore: SettingsStore
        private set
    lateinit var imageLoader: ImageLoader
        private set
    lateinit var database: Database
        private set

    fun init() {
        settingsStore = SettingsStore()
        tokenStore = KeychainTokenStore()
        val refresher = RealTokenRefresher(tokenStore, TokenExchange())
        client = Client(settingsStore, tokenStore, refresher, DefaultLanguageProvider(), StdoutLogger)
        imageLoader = ImageLoaderFactory.create(settingsStore)
        database = createDatabase()
    }
}
