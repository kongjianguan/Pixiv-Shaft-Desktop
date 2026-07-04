package ceui.pixiv.net.imagehost

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ImageHostManagerTest {

    private val pximgUrl = "https://i.pximg.net/img-original/img/2024/01/01/00/00/00/12345_p0.jpg"
    private val sximgUrl = "https://s.pximg.net/profile.jpg"

    @Test
    fun `PIXIV mode returns url unchanged`() {
        ImageHostManager.setMode(ImageHostManager.Mode.PIXIV)
        assertEquals(pximgUrl, ImageHostManager.rewrite(pximgUrl))
    }

    @Test
    fun `PIXIV_CAT rewrites i and s pximg net`() {
        ImageHostManager.setMode(ImageHostManager.Mode.PIXIV_CAT)
        assertEquals(
            "https://i.pixiv.cat/img-original/img/2024/01/01/00/00/00/12345_p0.jpg",
            ImageHostManager.rewrite(pximgUrl),
        )
        assertEquals(
            "https://s.pixiv.cat/profile.jpg",
            ImageHostManager.rewrite(sximgUrl),
        )
    }

    @Test
    fun `PIXIV_RE rewrites to pixiv re`() {
        ImageHostManager.setMode(ImageHostManager.Mode.PIXIV_RE)
        assertEquals(
            "https://i.pixiv.re/img-original/img/2024/01/01/00/00/00/12345_p0.jpg",
            ImageHostManager.rewrite(pximgUrl),
        )
    }

    @Test
    fun `CUSTOM with empty host returns url unchanged`() {
        ImageHostManager.setMode(ImageHostManager.Mode.CUSTOM)
        ImageHostManager.setCustomHost("")
        assertEquals(pximgUrl, ImageHostManager.rewrite(pximgUrl))
    }

    @Test
    fun `CUSTOM with prefix rewrites host only`() {
        ImageHostManager.setMode(ImageHostManager.Mode.CUSTOM)
        ImageHostManager.setCustomHost("https://my.proxy/subpath")
        assertEquals(
            "https://my.proxy/subpath/img-original/img/2024/01/01/00/00/00/12345_p0.jpg",
            ImageHostManager.rewrite(pximgUrl),
        )
    }

    @Test
    fun `non-pximg host returns url unchanged`() {
        ImageHostManager.setMode(ImageHostManager.Mode.PIXIV_CAT)
        assertEquals("https://example.com/x.jpg", ImageHostManager.rewrite("https://example.com/x.jpg"))
    }

    @Test
    fun `requiresStandardClient is false for PIXIV, true otherwise`() {
        ImageHostManager.setMode(ImageHostManager.Mode.PIXIV)
        assertFalse(ImageHostManager.requiresStandardClient())
        ImageHostManager.setMode(ImageHostManager.Mode.PIXIV_CAT)
        assertTrue(ImageHostManager.requiresStandardClient())
    }

    @Test
    fun `mode ordinal roundtrip clamps unknown to PIXIV`() {
        ImageHostManager.setModeOrdinal(1)
        assertEquals(ImageHostManager.Mode.PIXIV_CAT, ImageHostManager.getMode())
        ImageHostManager.setModeOrdinal(99)
        assertEquals(ImageHostManager.Mode.PIXIV, ImageHostManager.getMode())
    }
}
