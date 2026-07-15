package ceui.pixiv.ui.novel

import ceui.loxia.ImageUrls
import ceui.loxia.NovelImages
import ceui.loxia.WebIllust
import ceui.loxia.WebIllustHolder
import ceui.loxia.WebNovel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NovelReaderTest {

    @Test
    fun `webview html parser extracts novel payload`() {
        val html = """
            <html><script>
            Object.defineProperty(window, 'pixiv', { value: {
              "novel": {"id":"42","title":"Demo","text":"hello\n[newpage]\nworld"}
            }});
            </script></html>
        """.trimIndent()

        val novel = NovelWebParser.parse(html)

        assertNotNull(novel)
        assertEquals("42", novel?.id)
        assertEquals("hello\n[newpage]\nworld", novel?.text)
    }

    @Test
    fun `webview html parser accepts javascript trailing commas`() {
        val html = """
            <script>
            Object.defineProperty(window, 'pixiv', { value: {
              "novel": {"id":"42","text":"hello",},
            }});
            </script>
        """.trimIndent()

        assertEquals("hello", NovelWebParser.parse(html)?.text)
    }

    @Test
    fun `tokenizer resolves newpage jump targets`() {
        val tokens = ContentParser.tokenize("intro\n[newpage]\n[jump:2]\nsecond")

        assertTrue(tokens.any { it is ContentToken.PageBreak })
        val target = ContentParser.resolveJumpTarget(tokens, 2)
        assertNotNull(target)
        assertTrue(tokens.indexOfFirst { it.sourceEnd >= target!! } >= 0)
    }

    @Test
    fun `chapter cleanup removes only quoted chapter number`() {
        assertEquals("第0章", ContentParser.cleanChapterTitle("第'0章'"))
        assertEquals("第12章", ContentParser.cleanChapterTitle("第‘12’章’"))
        assertEquals("第2章 John's return", ContentParser.cleanChapterTitle("第2章 John's return"))
    }

    @Test
    fun `embedded image resolver finds uploaded and pixiv urls`() {
        val webNovel = WebNovel(
            images = mapOf(
                "9" to NovelImages(
                    urls = mapOf("original" to "https://img/uploaded.jpg"),
                ),
            ),
            illusts = mapOf(
                "12-1" to WebIllustHolder(
                    illust = WebIllust(
                        id = 12,
                        height = 100,
                        width = 100,
                        images = ImageUrls(original = "https://img/pixiv.jpg"),
                    ),
                ),
            ),
        )

        assertEquals(
            "https://img/uploaded.jpg",
            NovelImageResolver.urlFor(webNovel, ContentToken.UploadedImage(0, 0, 9)),
        )
        assertEquals(
            "https://img/pixiv.jpg",
            NovelImageResolver.urlFor(webNovel, ContentToken.PixivImage(0, 0, 12, 1)),
        )
        assertNull(NovelImageResolver.urlFor(webNovel, ContentToken.BlankLine(0, 0)))
    }
}
