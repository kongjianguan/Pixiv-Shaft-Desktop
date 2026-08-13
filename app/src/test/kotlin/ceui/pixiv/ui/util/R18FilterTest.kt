package ceui.pixiv.ui.util

import ceui.lisa.models.MarkedNovelItem
import ceui.lisa.models.NovelBean
import ceui.lisa.models.WatchlistMangaItem
import ceui.lisa.models.WatchlistNovelItem
import ceui.loxia.Illust
import ceui.loxia.Novel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class R18FilterTest {

    @Test
    fun `loxia illust novel by x_restrict`() {
        assertFalse(isR18(Illust(id = 1, x_restrict = 0)))
        assertFalse(isR18(Illust(id = 1, x_restrict = null)))
        assertTrue(isR18(Illust(id = 1, x_restrict = 1)))
        assertTrue(isR18(Illust(id = 1, x_restrict = 2)))
        assertFalse(isR18(Novel(id = 1, x_restrict = 0)))
        assertTrue(isR18(Novel(id = 1, x_restrict = 1)))
    }

    @Test
    fun `lisa beans by x_restrict`() {
        assertFalse(isR18(WatchlistMangaItem().apply { x_restrict = 0 }))
        assertTrue(isR18(WatchlistMangaItem().apply { x_restrict = 1 }))
        assertFalse(isR18(WatchlistNovelItem()))
        assertTrue(isR18(WatchlistNovelItem().apply { x_restrict = 2 }))
    }

    @Test
    fun `marked novel delegates to inner novel`() {
        val safe = MarkedNovelItem().apply { novel = NovelBean().apply { x_restrict = 0 } }
        val r18 = MarkedNovelItem().apply { novel = NovelBean().apply { x_restrict = 1 } }
        assertFalse(isR18(safe))
        assertTrue(isR18(r18))
    }

    @Test
    fun `null and unknown types are safe`() {
        assertFalse(isR18(null))
        assertFalse(isR18("not a work"))
        assertFalse(isR18(42))
    }

    @Test
    fun `visibleItems keeps everything when r18 enabled and filters when disabled`() {
        val items = listOf(
            Illust(id = 1, x_restrict = 0),
            Illust(id = 2, x_restrict = 1),
            Illust(id = 3, x_restrict = 2),
        )

        assertEquals(listOf(1L, 2L, 3L), visibleItems(items, showR18 = true).map { it.id })
        assertEquals(listOf(1L), visibleItems(items, showR18 = false).map { it.id })
    }

    @Test
    fun `hasHiddenR18 reports filtered r18 works only when disabled`() {
        val items = listOf(Illust(id = 1, x_restrict = 0), Illust(id = 2, x_restrict = 1))

        assertFalse(hasHiddenR18(items, showR18 = true))
        assertTrue(hasHiddenR18(items, showR18 = false))
        assertFalse(hasHiddenR18(listOf(Illust(id = 1, x_restrict = 0)), showR18 = false))
    }

    @Test
    fun `visibleNovels filters r18 and invisible novels`() {
        val items = listOf(
            Novel(id = 1, x_restrict = 0, visible = true),
            Novel(id = 2, x_restrict = 0, visible = false),
            Novel(id = 3, x_restrict = 1, visible = true),
            Novel(id = 4, x_restrict = 0, visible = null),
        )

        assertEquals(listOf(1L, 4L), visibleNovels(items, showR18 = false).map { it.id })
        assertEquals(listOf(1L, 3L, 4L), visibleNovels(items, showR18 = true).map { it.id })
    }

    @Test
    fun `visibleMarkedNovels filters unavailable r18 and malformed items`() {
        fun marker(id: Int, visible: Boolean, xRestrict: Int): MarkedNovelItem =
            MarkedNovelItem().apply {
                novel = NovelBean().apply {
                    setId(id)
                    setVisible(visible)
                    setX_restrict(xRestrict)
                }
                novel_marker = MarkedNovelItem.NovelMarker()
            }

        val malformed = MarkedNovelItem().apply {
            novel = NovelBean().apply {
                setId(4)
                setVisible(true)
            }
        }
        val items = listOf(
            marker(id = 1, visible = true, xRestrict = 0),
            marker(id = 2, visible = false, xRestrict = 0),
            marker(id = 3, visible = true, xRestrict = 1),
            malformed,
        )

        assertEquals(listOf(1), visibleMarkedNovels(items, showR18 = false).map { it.novel.id })
        assertEquals(listOf(1, 3), visibleMarkedNovels(items, showR18 = true).map { it.novel.id })
    }

}
