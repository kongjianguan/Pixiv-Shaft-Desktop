package ceui.pixiv.download

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DownloadTemplateTest {

    private fun values(
        title: String = "Title",
        id: Long = 42L,
        author: String = "Artist",
        page: String = "",
        ext: String = ".jpg",
        series: String = "",
        seriesOrder: String = "1",
        chapters: String = "1",
    ) = DownloadTemplateValues(
        title = title,
        id = id,
        author = author,
        authorId = 7L,
        page = page,
        ext = ext,
        series = series,
        seriesOrder = seriesOrder,
        chapters = chapters,
    )

    @Test
    fun `default illust template keeps legacy single-page path structure`() {
        assertEquals(
            "Illusts/Artist/Title 42.jpg",
            DownloadTemplate.renderPath(
                "Illusts/{author}/{title} {id}{page}", values(), autoPageSuffix = "", ext = ".jpg",
            ),
        )
    }

    @Test
    fun `default illust template keeps legacy multi-page path structure`() {
        assertEquals(
            "Illusts/Artist/Title 42 p2.jpg",
            DownloadTemplate.renderPath(
                "Illusts/{author}/{title} {id}{page}", values(page = " p2"), autoPageSuffix = " p2", ext = ".jpg",
            ),
        )
    }

    @Test
    fun `auto page suffix appended only when template lacks page variable`() {
        assertEquals(
            "Illusts/Artist/Title 42 p2.jpg",
            DownloadTemplate.renderPath(
                "Illusts/{author}/{title} {id}", values(), autoPageSuffix = " p2", ext = ".jpg",
            ),
        )
        // 模板自带 {page} 时不再追加，避免页码出现两次
        assertEquals(
            "Illusts/Artist/Title 42 p2.jpg",
            DownloadTemplate.renderPath(
                "Illusts/{author}/{title} {id}{page}", values(page = " p2"), autoPageSuffix = " p2", ext = ".jpg",
            ),
        )
    }

    @Test
    fun `default ugoira and novel templates render expected names`() {
        assertEquals(
            "Ugoira/Artist/Title 42.gif",
            DownloadTemplate.renderPath(
                "Ugoira/{author}/{title} {id}", values(ext = ".gif"), ext = ".gif",
            ),
        )
        assertEquals(
            "Novels/Artist/Title_42.txt",
            DownloadTemplate.renderPath(
                "Novels/{author}/{title}_{id}", values(ext = ".txt"), ext = ".txt",
            ),
        )
    }

    @Test
    fun `default novel template drops empty series dir for single chapter`() {
        assertEquals(
            "Novels/1_Title_42.txt",
            DownloadTemplate.renderPath(
                "Novels/{series}/{series_order}_{title}_{id}", values(ext = ".txt"), ext = ".txt",
            ),
        )
        assertEquals(
            "Novels/My Series/3_Title_42.txt",
            DownloadTemplate.renderPath(
                "Novels/{series}/{series_order}_{title}_{id}",
                values(series = "My Series", seriesOrder = "3", chapters = "12", ext = ".txt"),
                ext = ".txt",
            ),
        )
    }

    @Test
    fun `template with embedded ext is not double-appended`() {
        assertEquals(
            "Illusts/Artist/Title 42.jpg",
            DownloadTemplate.renderPath(
                "Illusts/{author}/{title} {id}{ext}", values(ext = ".jpg"), ext = ".jpg",
            ),
        )
        // 模板不含 {ext} 时才追加
        assertEquals(
            "Illusts/Artist/Title 42.jpg",
            DownloadTemplate.renderPath(
                "Illusts/{author}/{title} {id}", values(ext = ".jpg"), ext = ".jpg",
            ),
        )
    }

    @Test
    fun `segments are sanitized and blank segments fall back to untitled`() {
        // 值内非法字符（作者名/标题里的 / 与 :）替换为 _，不产生新目录层
        assertEquals(
            "Illusts/A_B/C_D.jpg",
            DownloadTemplate.renderPath(
                "Illusts/{author}/{title}{ext}", values(title = "C:D", author = "A/B"), ext = "",
            ),
        )
        assertEquals(
            "untitled/untitled.txt",
            DownloadTemplate.renderPath("  /{title}", values(title = "  "), ext = ".txt"),
        )
    }

    @Test
    fun `path traversal segments are neutralized`() {
        assertEquals(
            "Illusts/_/Title 42.jpg",
            DownloadTemplate.renderPath(
                "Illusts/{author}/{title} {id}", values(author = ".."), ext = ".jpg",
            ),
        )
    }

    @Test
    fun `series variables render single-chapter defaults`() {
        assertEquals(
            "Novels/1of1/Title 42.txt",
            DownloadTemplate.renderPath(
                "Novels/{series_order}of{chapters}/{title} {id}",
                values(seriesOrder = "1", chapters = "1"),
                ext = ".txt",
            ),
        )
        assertEquals(
            "Novels/Series 3of12/Title 42.txt",
            DownloadTemplate.renderPath(
                "Novels/{series} {series_order}of{chapters}/{title} {id}",
                values(series = "Series", seriesOrder = "3", chapters = "12"),
                ext = ".txt",
            ),
        )
    }

    @Test
    fun `trailing slash after empty series variable drops the empty dir segment`() {
        // 系列为空且 {series} 处于目录段末尾（模板以 / 结尾）时，{series}/ 整体去掉后
        // 不再渲染出 untitled 目录
        assertEquals(
            "Novels/Title_42.txt",
            DownloadTemplate.renderPath(
                "Novels/{series}/{title}_{id}/", values(ext = ".txt"), ext = ".txt",
            ),
        )
        assertEquals(
            "Novels/My Series/Title_42.txt",
            DownloadTemplate.renderPath(
                "Novels/{series}/{title}_{id}/",
                values(series = "My Series", ext = ".txt"),
                ext = ".txt",
            ),
        )
    }

    @Test
    fun `auto page suffix lands before embedded ext`() {
        // 模板内嵌 {ext} 时，自动页码必须插在扩展名之前
        assertEquals(
            "Illusts/Artist/Title p2.jpg",
            DownloadTemplate.renderPath(
                "Illusts/{author}/{title}{ext}", values(ext = ".jpg"), autoPageSuffix = " p2", ext = ".jpg",
            ),
        )
        // 标题自身以扩展名文本结尾时，后缀仍插在真正的扩展名（{ext} 的渲染值）之前
        assertEquals(
            "Illusts/Artist/pic.jpg p2.jpg",
            DownloadTemplate.renderPath(
                "Illusts/{author}/{title}{ext}", values(title = "pic.jpg", ext = ".jpg"), autoPageSuffix = " p2", ext = ".jpg",
            ),
        )
    }

    @Test
    fun `page suffix anchors to ext token when ext is not the last token`() {
        // {ext} 不在模板末尾、且标题本身以 ".jpg" 结尾时，后缀仍只锚定真正的 {ext}，
        // 不会插进标题的 ".jpg" 里
        assertEquals(
            "Illusts/Artist/pic.jpg p2.jpg 42",
            DownloadTemplate.renderPath(
                "Illusts/{author}/{title}{ext} {id}", values(title = "pic.jpg", ext = ".jpg"), autoPageSuffix = " p2", ext = ".jpg",
            ),
        )
    }

    @Test
    fun `template without page variable appends suffix when ext not embedded`() {
        assertEquals(
            "Illusts/Artist/Title p2.jpg",
            DownloadTemplate.renderPath(
                "Illusts/{author}/{title}", values(), autoPageSuffix = " p2", ext = ".jpg",
            ),
        )
    }

    @Test
    fun `literal tokens inside substituted values are not re-rendered`() {
        // 标题里恰好含 {id} 字面量时，单遍替换后保持原样，不会被二次扫描成作品 id
        assertEquals(
            "Illusts/Artist/{id}のアレ.jpg",
            DownloadTemplate.renderPath(
                "Illusts/{author}/{title}", values(title = "{id}のアレ"), ext = ".jpg",
            ),
        )
        assertEquals(
            "Illusts/Artist/Title 42.jpg",
            DownloadTemplate.renderPath(
                "Illusts/{author}/{title} {id}", values(), ext = ".jpg",
            ),
        )
    }

    @Test
    fun `unknown tokens stay literal`() {
        // 拼错的变量名（如 {titel}）保持原样，不静默替换成空串
        assertEquals(
            "Illusts/Artist/{titel}.jpg",
            DownloadTemplate.renderPath(
                "Illusts/{author}/{titel}", values(), ext = ".jpg",
            ),
        )
    }

    @Test
    fun `long cjk segment is truncated by utf-8 bytes not code points`() {
        val path = DownloadTemplate.renderPath(
            "{title}", values(title = "あ".repeat(100)), ext = "",
        )
        assertEquals(240, path.toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun `combined tokens in one segment stay within byte limit`() {
        val path = DownloadTemplate.renderPath(
            "{title}_{author}",
            values(title = "あ".repeat(60), author = "い".repeat(60)),
            ext = "",
        )
        // 240 字节边界落在下一个码点中间时，取不超过边界的最长前缀
        assertEquals("あ".repeat(60) + "_" + "い".repeat(19), path)
        assertTrue(path.toByteArray(Charsets.UTF_8).size <= 240)
    }

    @Test
    fun `emoji at byte boundary is dropped whole instead of leaving lone surrogate`() {
        val title = "あ".repeat(80) + "😀"
        val path = DownloadTemplate.renderPath("{title}", values(title = title), ext = "")
        assertEquals("あ".repeat(80), path)
        assertFalse(path.toCharArray().any { Character.isSurrogate(it) })
    }

    @Test
    fun `truncated segment plus page suffix and ext stays within 255 bytes`() {
        val path = DownloadTemplate.renderPath(
            "{title}", values(title = "あ".repeat(100)), autoPageSuffix = " p2", ext = ".jpg",
        )
        assertEquals(247, path.toByteArray(Charsets.UTF_8).size)
    }
}
