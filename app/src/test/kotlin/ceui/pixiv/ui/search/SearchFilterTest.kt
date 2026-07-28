package ceui.pixiv.ui.search

import ceui.pixiv.ui.screen.search.SearchFilter
import ceui.pixiv.ui.screen.search.SearchRatio
import ceui.pixiv.ui.screen.search.SearchSort
import ceui.pixiv.ui.screen.search.SearchTarget
import ceui.pixiv.ui.screen.search.shouldLoadSearchMore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SearchFilterTest {

    @Test
    fun `partial tag target is omitted from the request`() {
        assertNull(SearchTarget.PartialTags.queryValue())
        assertEquals("title_and_caption", SearchTarget.TitleCaption.queryValue())
    }

    @Test
    fun `active count includes sort and illust-only filters`() {
        val filter = SearchFilter(
            sort = SearchSort.PopularPreview,
            ratio = SearchRatio.Portrait,
        )

        assertEquals(2, filter.activeCount(isNovel = false))
        assertEquals(1, filter.activeCount(isNovel = true))
    }

    @Test
    fun `default search filter has no active conditions`() {
        assertTrue(SearchFilter().activeCount(isNovel = false) == 0)
        assertTrue(SearchFilter().activeCount(isNovel = true) == 0)
    }

    @Test
    fun `empty filtered page can request another page`() {
        assertTrue(shouldLoadSearchMore(itemCount = 0, lastVisibleIndex = 0, hasMore = true, isLoadingMore = false))
        assertFalse(shouldLoadSearchMore(itemCount = 0, lastVisibleIndex = 0, hasMore = false, isLoadingMore = false))
        assertFalse(shouldLoadSearchMore(itemCount = 0, lastVisibleIndex = 0, hasMore = true, isLoadingMore = true))
    }
}
