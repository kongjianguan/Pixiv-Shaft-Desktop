package ceui.pixiv.ui.screen.pixivision

import ceui.loxia.Article
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PixivisionScreenTest {

    @Test
    fun `relative article url is completed with pixivision origin`() {
        assertEquals(
            "https://www.pixivision.net/novel/view/123",
            fullArticleUrl(Article(id = 1L, article_url = "/novel/view/123")),
        )
    }

    @Test
    fun `absolute article url is kept as is`() {
        assertEquals(
            "https://example.com/a/b",
            fullArticleUrl(Article(id = 1L, article_url = "https://example.com/a/b")),
        )
    }

    @Test
    fun `missing article url returns null`() {
        assertNull(fullArticleUrl(Article(id = 1L)))
    }
}
