package ceui.pixiv.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PixivHostsTest {
    @Test
    fun `app-api host and walkthrough path`() {
        assertEquals("app-api.pixiv.net", PixivHosts.APP_API_HOST)
        assertEquals("/v1/walkthrough/illusts", PixivHosts.WALKTHROUGH_PATH)
    }

    @Test
    fun `CF IPs from CronetInterceptor`() {
        assertEquals(listOf("104.18.42.239", "172.64.145.17"), PixivHosts.CF_IPS)
    }

    @Test
    fun `shouldQuic only for app-api and oauth`() {
        assertTrue(PixivHosts.shouldQuic("app-api.pixiv.net"))
        assertTrue(PixivHosts.shouldQuic("oauth.secure.pixiv.net"))
        assertTrue(!PixivHosts.shouldQuic("i.pximg.net"))
    }

    @Test
    fun `iOS UA persona`() {
        assertEquals("PixivIOSApp/8.6.10 (iOS 26.5; iPhone16,2)", PixivHosts.IOS_UA)
    }
}
