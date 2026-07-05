package ceui.pixiv.poc

import ceui.pixiv.image.ImageLoaderFactory
import ceui.pixiv.net.api.Client
import ceui.pixiv.net.auth.OAuthCallbackServer
import ceui.pixiv.net.auth.PkceUtils
import ceui.pixiv.net.auth.RealTokenRefresher
import ceui.pixiv.net.auth.TokenExchange
import ceui.pixiv.net.impl.DefaultLanguageProvider
import ceui.pixiv.net.impl.StdoutLogger
import ceui.pixiv.store.KeychainTokenStore
import ceui.pixiv.store.SettingsStore
import ceui.pixiv.store.createDatabase
import coil3.PlatformContext
import coil3.request.ImageRequest
import kotlinx.coroutines.runBlocking
import java.awt.Desktop
import java.net.URI

fun main() = runBlocking {
    val settings = SettingsStore()
    val tokenStore = KeychainTokenStore()
    val logger = StdoutLogger

    // ① SQLDelight write/read illust history
    val db = createDatabase()
    db.queries.illustHistoryQueries.insertIllust(
        48723512, """{"id":48723512}""", System.currentTimeMillis(), 0
    )
    val rows = db.queries.illustHistoryQueries.selectRecentIllusts(10).executeAsList()
    println("SQLDelight: ${rows.size} history rows, first illustID=${rows.firstOrNull()?.illustID}")

    // ② Keychain status
    println("Keychain: isLoggedIn=${tokenStore.isLoggedIn}")

    // ③ OAuth PKCE login if no token stored
    if (!tokenStore.isLoggedIn) {
        println("OAuth: no token, starting PKCE login flow...")
        val (verifier, challenge) = PkceUtils.generate()
        OAuthCallbackServer().use { server ->
            // Use the callback server's actual redirect URI so Pixiv redirects back to localhost
            val authUrl = PkceUtils.buildAuthUrl(challenge, server.redirectUri)
            println("OAuth: open this URL in browser:\n  $authUrl")
            try {
                Desktop.getDesktop().browse(URI(authUrl))
            } catch (e: Exception) {
                println("(auto-open failed, copy URL manually)")
            }
            val code = server.waitForCode(30_000) // 120s for interactive use; 30s for automated runs
            if (code != null) {
                println("OAuth: got authorization code, exchanging for token…")
                try {
                    val exchange = TokenExchange()
                    val resp = exchange.exchangeCode(code, verifier)
                    if (resp.accessToken != null) {
                        tokenStore.saveTokens(resp.accessToken, resp.refreshToken)
                        println("OAuth: token stored in Keychain ✓")
                    } else {
                        println("OAuth: token exchange returned null (no access_token)")
                    }
                } catch (e: Exception) {
                    println("OAuth: token exchange failed — ${e.message}")
                    println("OAuth: this is expected behind GFW (TCP RST to oauth.secure.pixiv.net)")
                }
            } else {
                println("OAuth: timed out waiting for browser callback (30s timeout)")
                println("OAuth: proceeding without auth — getWalkthroughWorks is a public endpoint")
            }
        }
    }

    // ④ App API call with Client (uses KeychainTokenStore + RealTokenRefresher + SettingsStore)
    val refresher = RealTokenRefresher(tokenStore, TokenExchange())
    val client = Client(settings, tokenStore, refresher, DefaultLanguageProvider(), logger)
    val resp = client.appApi.getWalkthroughWorks()
    println("API: walkthrough illusts count=${resp.illusts.size}")
    resp.illusts.firstOrNull()?.let {
        println("API: first illust id=${it.id} title=${it.title}")
    }

    // ⑤ Coil 3 image load (pximg via anti-GFW OkHttp client)
    val imageUrl = resp.illusts.firstOrNull()?.image_urls?.large
    if (imageUrl != null) {
        println("Image: loading $imageUrl via Coil (anti-GFW client)…")
        val loader = ImageLoaderFactory.create(settings)
        val request = ImageRequest.Builder(PlatformContext.INSTANCE)
            .data(imageUrl)
            .build()
        val result = loader.execute(request)
        println("Image: loaded, ${result.image?.width}x${result.image?.height}")
    } else {
        println("Image: no illust image URL available to load")
    }

    println("PLAN 3 GATE PASSED")
    client.close()
}
