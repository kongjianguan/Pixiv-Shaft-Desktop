package ceui.pixiv.poc

import ceui.pixiv.net.api.Client
import ceui.pixiv.net.impl.DefaultLanguageProvider
import ceui.pixiv.net.impl.FileTokenStore
import ceui.pixiv.net.impl.InMemorySettings
import ceui.pixiv.net.impl.StdoutLogger
import ceui.pixiv.net.impl.StubTokenRefresher
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

fun main() = runBlocking {
    val client = Client(
        settings = InMemorySettings(isDirectConnect = true),
        tokenStore = FileTokenStore(
            Path.of(System.getProperty("user.home"),
                "Library/Application Support/PixivShaft/token.json")
        ),
        refresher = StubTokenRefresher(),
        lang = DefaultLanguageProvider(),
        logger = StdoutLogger,
    )
    val resp = client.appApi.getWalkthroughWorks()
    println("HTTP via QUIC — illusts count = ${resp.illusts.size}")
    resp.illusts.firstOrNull()?.let {
        println("first illust id=${it.id} title=${it.title}")
    }
    println("PLAN 2 GATE PASSED")
    client.close()
}
