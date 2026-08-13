package ceui.loxia

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WebIllustMappingTest {

    @Test
    fun `maps a single page web work to app model`() {
        val illust = WebIllustBody(
            illustTitle = "测试作品",
            description = "正文 <a href=\"/jump.php?url=https%3A%2F%2Fexample.com\">链接</a>",
            createDate = "2026-08-13T00:00:00+00:00",
            urls = WebIllustUrls(
                small = "https://i.pximg.net/small.jpg",
                original = "https://i.pximg.net/original.jpg",
            ),
            userId = "42",
            userName = "画师",
            userAccount = "artist",
            width = 1200,
            height = 800,
            pageCount = 1,
            bookmarkCount = 12,
            viewCount = 34,
        ).toIllust(100L)

        assertEquals(100L, illust.id)
        assertEquals("测试作品", illust.title)
        assertEquals("2026-08-13T09:00:00+09:00", illust.create_date)
        assertEquals("https://i.pximg.net/original.jpg", illust.meta_single_page?.original_image_url)
        // jump.php 外链应解码成可直接点击的完整 URL，不残留 "url=" 前缀
        assertEquals("正文 <a href=\"https://example.com\">链接</a>", illust.caption)
        assertEquals(42L, illust.user?.id)
    }

    @Test
    fun `uses page endpoint urls before filename fallback`() {
        val body = WebIllustBody(
            urls = WebIllustUrls(
                regular = "https://i.pximg.net/regular_p0.jpg",
                original = "https://i.pximg.net/original_p0.jpg",
            ),
            pageCount = 2,
            width = 100,
            height = 100,
        )
        val pages = listOf(
            WebIllustPage(urls = WebIllustUrls(original = "https://i.pximg.net/page0.jpg")),
            WebIllustPage(urls = WebIllustUrls(original = "https://i.pximg.net/page1.jpg")),
        )

        val illust = body.toIllust(101L, pages)

        assertEquals(2, illust.meta_pages?.size)
        assertEquals("https://i.pximg.net/page1.jpg", illust.meta_pages?.get(1)?.image_urls?.original)
    }

    @Test
    fun `falls back to pixiv page suffix convention`() {
        val illust = WebIllustBody(
            urls = WebIllustUrls(original = "https://i.pximg.net/original_p0.jpg"),
            pageCount = 2,
            width = 100,
            height = 100,
        ).toIllust(102L)

        assertEquals("https://i.pximg.net/original_p1.jpg", illust.meta_pages?.get(1)?.image_urls?.original)
        assertNotNull(illust.meta_pages?.get(0)?.image_urls?.original)
        assertTrue(illust.visible == true)
    }
}
