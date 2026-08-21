package ceui.pixiv.ui.screen.recommend

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import ceui.loxia.HomeIllustResponse
import ceui.loxia.Illust
import ceui.loxia.IllustResponse
import ceui.loxia.KListShow
import ceui.loxia.Novel
import ceui.loxia.NovelResponse
import ceui.pixiv.di.AppContainer
import ceui.pixiv.ui.state.PagedFeed
import ceui.pixiv.ui.state.UiState
import ceui.pixiv.ui.util.observeR18Toggle
import ceui.pixiv.ui.util.visibleNovels
import ceui.pixiv.ui.util.visibleItems
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

enum class RecommendPage(val label: String) {
    ILLUST("推荐"),
    MANGA("漫画"),
    NOVEL("小说"),
    WALKTHROUGH("最新")
}

class RecommendScreenModel : ScreenModel {

    private val client = AppContainer.client

    // --- Illust (推荐) ---
    private val illustFeed = PagedFeed<HomeIllustResponse, Illust>(client, HomeIllustResponse::class.java) {
        visibleItems(it)
    }
    val illustState: StateFlow<UiState<List<Illust>>> = illustFeed.state

    // --- Manga (漫画) ---
    private val mangaFeed = PagedFeed<HomeIllustResponse, Illust>(client, HomeIllustResponse::class.java) {
        visibleItems(it)
    }
    val mangaState: StateFlow<UiState<List<Illust>>> = mangaFeed.state

    // --- Novel (小说) ---
    private val novelFeed = PagedFeed<NovelResponse, Novel>(client, NovelResponse::class.java) {
        visibleNovels(it)
    }
    val novelState: StateFlow<UiState<List<Novel>>> = novelFeed.state

    // --- Walkthrough (最新) ---
    private val walkFeed = PagedFeed<IllustResponse, Illust>(client, IllustResponse::class.java) {
        visibleItems(it)
    }
    val walkState: StateFlow<UiState<List<Illust>>> = walkFeed.state

    private val novelBookmarksInFlight = ConcurrentHashMap.newKeySet<Long>()

    init {
        // Load all tabs in parallel on start
        screenModelScope.launch {
            launch { loadIllust(showLoading = true) }
            launch { loadManga(showLoading = true) }
            launch { loadNovel(showLoading = true) }
            launch { loadWalk(showLoading = true) }
        }
        observeR18Toggle(::republishIfLoaded)
    }

    // --- Load functions ---

    private suspend fun loadIllust(showLoading: Boolean = false) {
        if (showLoading) illustFeed.setLoading()
        try {
            val resp = client.appApi.getHomeData("illust")
            illustFeed.refresh(resp)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (!illustFeed.isSuccess()) illustFeed.setError(e.message ?: "Failed")
        }
    }

    private suspend fun loadManga(showLoading: Boolean = false) {
        if (showLoading) mangaFeed.setLoading()
        try {
            val resp = client.appApi.getHomeData("manga")
            mangaFeed.refresh(resp)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (!mangaFeed.isSuccess()) mangaFeed.setError(e.message ?: "Failed")
        }
    }

    private suspend fun loadNovel(showLoading: Boolean = false) {
        if (showLoading) novelFeed.setLoading()
        try {
            val resp = client.appApi.getRecmdNovels()
            novelFeed.refresh(resp)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (!novelFeed.isSuccess()) novelFeed.setError(e.message ?: "Failed")
        }
    }

    private suspend fun loadWalk(showLoading: Boolean = false) {
        if (showLoading) walkFeed.setLoading()
        try {
            val resp = client.appApi.getWalkthroughWorks()
            walkFeed.refresh(resp)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (!walkFeed.isSuccess()) walkFeed.setError(e.message ?: "Failed")
        }
    }

    // --- Refresh ---

    fun refreshIllust() {
        screenModelScope.launch { loadIllust() }
    }

    fun refreshManga() {
        screenModelScope.launch { loadManga() }
    }

    fun refreshNovel() {
        screenModelScope.launch { loadNovel() }
    }

    fun refreshWalk() {
        screenModelScope.launch { loadWalk() }
    }

    // --- Load more ---

    fun loadMoreIllust() = loadMore(illustFeed)
    fun loadMoreManga() = loadMore(mangaFeed)
    fun loadMoreNovel() = loadMore(novelFeed)
    fun loadMoreWalk() = loadMore(walkFeed)

    fun toggleNovelBookmark(novel: Novel) {
        ceui.pixiv.ui.util.toggleNovelBookmark(
            scope = screenModelScope,
            client = client,
            novel = novel,
            inFlight = novelBookmarksInFlight,
            updateLocal = ::updateNovelBookmark,
        )
    }

    private fun updateNovelBookmark(novelId: Long, isBookmarked: Boolean) {
        novelFeed.pager.updateItems { novels ->
            novels.map { novel ->
                if (novel.id == novelId) novel.copy(is_bookmarked = isBookmarked) else novel
            }
        }
        novelFeed.publish()
    }

    /** R18 开关变化时重新过滤已加载内容（feed 保留完整数据） */
    private fun republishIfLoaded() {
        illustFeed.republishIfLoaded()
        mangaFeed.republishIfLoaded()
        novelFeed.republishIfLoaded()
        walkFeed.republishIfLoaded()
    }

    private fun <T : KListShow<Item>, Item : Any> loadMore(feed: PagedFeed<T, Item>) {
        if (!feed.tryBeginLoadMore()) return
        screenModelScope.launch {
            try {
                feed.loadMoreAndPublishOnce()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* keep existing items */ }
            finally { feed.endLoadMore() }
        }
    }
}
