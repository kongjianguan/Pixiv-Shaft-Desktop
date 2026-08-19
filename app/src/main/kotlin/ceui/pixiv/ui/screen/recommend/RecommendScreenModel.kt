package ceui.pixiv.ui.screen.recommend

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import ceui.loxia.HomeIllustResponse
import ceui.loxia.Illust
import ceui.loxia.IllustResponse
import ceui.loxia.Novel
import ceui.loxia.NovelResponse
import ceui.pixiv.di.AppContainer
import ceui.pixiv.ui.state.Pager
import ceui.pixiv.ui.state.UiState
import ceui.pixiv.ui.util.observeR18Toggle
import ceui.pixiv.ui.util.visibleNovels
import ceui.pixiv.ui.util.visibleItems
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
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
    private val illustPager = Pager<HomeIllustResponse, Illust>(client, HomeIllustResponse::class.java)
    private val _illustState = MutableStateFlow<UiState<List<Illust>>>(UiState.Loading)
    val illustState: StateFlow<UiState<List<Illust>>> = _illustState.asStateFlow()

    // --- Manga (漫画) ---
    private val mangaPager = Pager<HomeIllustResponse, Illust>(client, HomeIllustResponse::class.java)
    private val _mangaState = MutableStateFlow<UiState<List<Illust>>>(UiState.Loading)
    val mangaState: StateFlow<UiState<List<Illust>>> = _mangaState.asStateFlow()

    // --- Novel (小说) ---
    private val novelPager = Pager<NovelResponse, Novel>(client, NovelResponse::class.java)
    private val _novelState = MutableStateFlow<UiState<List<Novel>>>(UiState.Loading)
    val novelState: StateFlow<UiState<List<Novel>>> = _novelState.asStateFlow()

    // --- Walkthrough (最新) ---
    private val walkPager = Pager<IllustResponse, Illust>(client, IllustResponse::class.java)
    private val _walkState = MutableStateFlow<UiState<List<Illust>>>(UiState.Loading)
    val walkState: StateFlow<UiState<List<Illust>>> = _walkState.asStateFlow()

    private val illustLoadingMore = AtomicBoolean(false)
    private val mangaLoadingMore = AtomicBoolean(false)
    private val novelLoadingMore = AtomicBoolean(false)
    private val walkLoadingMore = AtomicBoolean(false)
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
        if (showLoading) _illustState.value = UiState.Loading
        try {
            val resp = client.appApi.getHomeData("illust")
            illustPager.refresh(resp)
            _illustState.value = UiState.Success(visibleItems(illustPager.items.value))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (_illustState.value !is UiState.Success)
                _illustState.value = UiState.Error(e.message ?: "Failed")
        }
    }

    private suspend fun loadManga(showLoading: Boolean = false) {
        if (showLoading) _mangaState.value = UiState.Loading
        try {
            val resp = client.appApi.getHomeData("manga")
            mangaPager.refresh(resp)
            _mangaState.value = UiState.Success(visibleItems(mangaPager.items.value))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (_mangaState.value !is UiState.Success)
                _mangaState.value = UiState.Error(e.message ?: "Failed")
        }
    }

    private suspend fun loadNovel(showLoading: Boolean = false) {
        if (showLoading) _novelState.value = UiState.Loading
        try {
            val resp = client.appApi.getRecmdNovels()
            novelPager.refresh(resp)
            _novelState.value = UiState.Success(visibleNovels())
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (_novelState.value !is UiState.Success)
                _novelState.value = UiState.Error(e.message ?: "Failed")
        }
    }

    private suspend fun loadWalk(showLoading: Boolean = false) {
        if (showLoading) _walkState.value = UiState.Loading
        try {
            val resp = client.appApi.getWalkthroughWorks()
            walkPager.refresh(resp)
            _walkState.value = UiState.Success(visibleItems(walkPager.items.value))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (_walkState.value !is UiState.Success)
                _walkState.value = UiState.Error(e.message ?: "Failed")
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

    fun loadMoreIllust() = loadMore(illustPager, _illustState, illustLoadingMore)
    fun loadMoreManga() = loadMore(mangaPager, _mangaState, mangaLoadingMore)
    fun loadMoreNovel() {
        if (!novelPager.hasNext.value || !novelLoadingMore.compareAndSet(false, true)) return
        screenModelScope.launch {
            try {
                novelPager.loadMore()
                _novelState.value = UiState.Success(visibleNovels())
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* keep existing items */ }
            finally { novelLoadingMore.set(false) }
        }
    }
    fun loadMoreWalk() = loadMore(walkPager, _walkState, walkLoadingMore)

    fun toggleNovelBookmark(novel: Novel) {
        ceui.pixiv.ui.util.toggleNovelBookmark(
            scope = screenModelScope,
            client = client,
            novel = novel,
            inFlight = novelBookmarksInFlight,
            updateLocal = ::updateNovelBookmark,
        )
    }

    private fun visibleNovels(): List<Novel> = visibleNovels(novelPager.items.value)

    private fun updateNovelBookmark(novelId: Long, isBookmarked: Boolean) {
        novelPager.updateItems { novels ->
            novels.map { novel ->
                if (novel.id == novelId) novel.copy(is_bookmarked = isBookmarked) else novel
            }
        }
        _novelState.value = UiState.Success(visibleNovels())
    }

    /** R18 开关变化时重新过滤已加载内容（Pager 保留完整数据） */
    private fun republishIfLoaded() {
        if (_illustState.value is UiState.Success) {
            _illustState.value = UiState.Success(visibleItems(illustPager.items.value))
        }
        if (_mangaState.value is UiState.Success) {
            _mangaState.value = UiState.Success(visibleItems(mangaPager.items.value))
        }
        if (_novelState.value is UiState.Success) {
            _novelState.value = UiState.Success(visibleNovels())
        }
        if (_walkState.value is UiState.Success) {
            _walkState.value = UiState.Success(visibleItems(walkPager.items.value))
        }
    }

    private fun <T : ceui.loxia.KListShow<Item>, Item : Any> loadMore(
        pager: Pager<T, Item>,
        state: MutableStateFlow<UiState<List<Item>>>,
        loadingLock: AtomicBoolean
    ) {
        if (!pager.hasNext.value || !loadingLock.compareAndSet(false, true)) return
        screenModelScope.launch {
            try {
                pager.loadMore()
                state.value = UiState.Success(visibleItems(pager.items.value))
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* keep existing items */ }
            finally { loadingLock.set(false) }
        }
    }
}
