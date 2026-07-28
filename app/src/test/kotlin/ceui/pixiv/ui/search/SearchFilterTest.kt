package ceui.pixiv.ui.search

import ceui.pixiv.ui.screen.search.SearchFilter
import ceui.pixiv.ui.screen.search.SearchAiMode
import ceui.pixiv.ui.screen.search.SearchRatio
import ceui.pixiv.ui.screen.search.SearchSort
import ceui.pixiv.ui.screen.search.SearchTarget
import ceui.pixiv.ui.screen.search.serverValue
import ceui.pixiv.ui.screen.search.shouldLoadSearchMore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SearchFilterTest {

    @Test
    fun `all search targets are sent explicitly`() {
        assertEquals("partial_match_for_tags", SearchTarget.PartialTags.queryValue())
        assertEquals("exact_match_for_tags", SearchTarget.ExactTags.queryValue())
        assertEquals("title_and_caption", SearchTarget.TitleCaption.queryValue())
        assertEquals("text", SearchTarget.NovelText.queryValue())
        assertEquals("keyword", SearchTarget.NovelKeyword.queryValue())
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
    fun `ai search mode keeps AI works available for local filtering`() {
        assertEquals(1, SearchAiMode.All.serverValue())
        assertEquals(0, SearchAiMode.ExcludeAi.serverValue())
        assertEquals(1, SearchAiMode.OnlyAi.serverValue())
    }

    @Test
    fun `empty filtered page can request another page`() {
        assertTrue(shouldLoadSearchMore(itemCount = 0, lastVisibleIndex = 0, hasMore = true, isLoadingMore = false))
        assertFalse(shouldLoadSearchMore(itemCount = 0, lastVisibleIndex = 0, hasMore = false, isLoadingMore = false))
        assertFalse(shouldLoadSearchMore(itemCount = 0, lastVisibleIndex = 0, hasMore = true, isLoadingMore = true))
    }
}
