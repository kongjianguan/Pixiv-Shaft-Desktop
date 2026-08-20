package ceui.pixiv.net

object PixivHosts {
    const val APP_API_HOST = "app-api.pixiv.net"
    const val OAUTH_HOST = "oauth.secure.pixiv.net"
    const val WEB_API_HOST = "www.pixiv.net"
    const val COMIC_HOST = "comic.pixiv.net"
    // 源自 CronetInterceptor.java:42-43
    val CF_IPS = listOf("104.18.42.239", "172.64.145.17")

    // 源自 HeaderInterceptor.kt:13-16（iOS 人设）
    const val IOS_UA = "PixivIOSApp/8.6.10 (iOS 26.5; iPhone16,2)"
    const val APP_OS = "ios"
    const val APP_OS_VERSION = "26.5"
    const val APP_VERSION = "8.6.10"

    fun shouldQuic(host: String): Boolean =
        host == APP_API_HOST || host == OAUTH_HOST || host == WEB_API_HOST || host == COMIC_HOST

    /** 走 ECH（加密 ClientHello）直连的 host；与 rust/ech 的 PIXIV_HOSTS 保持一致。 */
    fun shouldEch(host: String): Boolean = shouldQuic(host)
}
