package ceui.pixiv.net

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PixivHostsTest {
    @Test
    fun `shouldQuic only for quic-capable hosts`() {
        assertTrue(PixivHosts.shouldQuic("app-api.pixiv.net"))
        assertTrue(PixivHosts.shouldQuic("oauth.secure.pixiv.net"))
        assertTrue(PixivHosts.shouldQuic("www.pixiv.net"))
        assertTrue(PixivHosts.shouldQuic("comic.pixiv.net"))
        assertTrue(!PixivHosts.shouldQuic("i.pximg.net"))
        assertTrue(!PixivHosts.shouldQuic(""))
    }
}
