package ceui.pixiv.ui.search

import ceui.pixiv.ui.screen.search.SearchAiMode
import ceui.pixiv.ui.screen.search.SearchFilter
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
    fun `active count includes sort and illust-only filters`() {
        val filter = SearchFilter(
            sort = SearchSort.PopularPreview,
            ratio = SearchRatio.Portrait,
        )

        assertEquals(2, filter.activeCount(isNovel = false))
        assertEquals(1, filter.activeCount(isNovel = true))
    }

    @Test
    fun `fromApiValue parses server values and falls back to partial tags`() {
        assertEquals(SearchTarget.ExactTags, SearchTarget.fromApiValue("exact_match_for_tags"))
        assertEquals(SearchTarget.TitleCaption, SearchTarget.fromApiValue("title_and_caption"))
        assertEquals(SearchTarget.NovelText, SearchTarget.fromApiValue("text"))
        assertEquals(SearchTarget.PartialTags, SearchTarget.fromApiValue(null))
        assertEquals(SearchTarget.PartialTags, SearchTarget.fromApiValue("bogus_value"))
    }

    @Test
    fun `serverValue maps exclude ai to 0 and all other modes to 1`() {
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

    @Test
    fun `load more triggers near bottom and only when hasMore and not loading`() {
        assertTrue(shouldLoadSearchMore(itemCount = 10, lastVisibleIndex = 5, hasMore = true, isLoadingMore = false))
        assertFalse(shouldLoadSearchMore(itemCount = 10, lastVisibleIndex = 2, hasMore = true, isLoadingMore = false))
        assertFalse(shouldLoadSearchMore(itemCount = 10, lastVisibleIndex = 5, hasMore = true, isLoadingMore = true))
        assertFalse(shouldLoadSearchMore(itemCount = 10, lastVisibleIndex = 5, hasMore = false, isLoadingMore = false))
    }
}
