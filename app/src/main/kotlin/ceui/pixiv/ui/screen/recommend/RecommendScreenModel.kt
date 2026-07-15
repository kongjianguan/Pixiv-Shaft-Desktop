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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

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
    private val _illustRefreshing = MutableStateFlow(false)
    val illustRefreshing: StateFlow<Boolean> = _illustRefreshing.asStateFlow()

    // --- Manga (漫画) ---
    private val mangaPager = Pager<HomeIllustResponse, Illust>(client, HomeIllustResponse::class.java)
    private val _mangaState = MutableStateFlow<UiState<List<Illust>>>(UiState.Loading)
    val mangaState: StateFlow<UiState<List<Illust>>> = _mangaState.asStateFlow()
    private val _mangaRefreshing = MutableStateFlow(false)
    val mangaRefreshing: StateFlow<Boolean> = _mangaRefreshing.asStateFlow()

    // --- Novel (小说) ---
    private val novelPager = Pager<NovelResponse, Novel>(client, NovelResponse::class.java)
    private val _novelState = MutableStateFlow<UiState<List<Novel>>>(UiState.Loading)
    val novelState: StateFlow<UiState<List<Novel>>> = _novelState.asStateFlow()
    private val _novelRefreshing = MutableStateFlow(false)
    val novelRefreshing: StateFlow<Boolean> = _novelRefreshing.asStateFlow()

    // --- Walkthrough (最新) ---
    private val walkPager = Pager<IllustResponse, Illust>(client, IllustResponse::class.java)
    private val _walkState = MutableStateFlow<UiState<List<Illust>>>(UiState.Loading)
    val walkState: StateFlow<UiState<List<Illust>>> = _walkState.asStateFlow()
    private val _walkRefreshing = MutableStateFlow(false)
    val walkRefreshing: StateFlow<Boolean> = _walkRefreshing.asStateFlow()

    private val illustLoadingMore = AtomicBoolean(false)
    private val mangaLoadingMore = AtomicBoolean(false)
    private val novelLoadingMore = AtomicBoolean(false)
    private val walkLoadingMore = AtomicBoolean(false)

    init {
        // Load all tabs in parallel on start
        screenModelScope.launch {
            launch { loadIllust(showLoading = true) }
            launch { loadManga(showLoading = true) }
            launch { loadNovel(showLoading = true) }
            launch { loadWalk(showLoading = true) }
        }
    }

    // --- Load functions ---

    private suspend fun loadIllust(showLoading: Boolean = false) {
        if (showLoading) _illustState.value = UiState.Loading
        try {
            val resp = client.appApi.getHomeData("illust")
            illustPager.refresh(resp)
            _illustState.value = UiState.Success(illustPager.items.value)
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
            _mangaState.value = UiState.Success(mangaPager.items.value)
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
            _novelState.value = UiState.Success(novelPager.items.value)
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
            _walkState.value = UiState.Success(walkPager.items.value)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (_walkState.value !is UiState.Success)
                _walkState.value = UiState.Error(e.message ?: "Failed")
        }
    }

    // --- Refresh ---

    fun refreshIllust() {
        screenModelScope.launch {
            _illustRefreshing.value = true
            loadIllust()
            _illustRefreshing.value = false
        }
    }

    fun refreshManga() {
        screenModelScope.launch {
            _mangaRefreshing.value = true
            loadManga()
            _mangaRefreshing.value = false
        }
    }

    fun refreshNovel() {
        screenModelScope.launch {
            _novelRefreshing.value = true
            loadNovel()
            _novelRefreshing.value = false
        }
    }

    fun refreshWalk() {
        screenModelScope.launch {
            _walkRefreshing.value = true
            loadWalk()
            _walkRefreshing.value = false
        }
    }

    // --- Load more ---

    fun loadMoreIllust() = loadMore(illustPager, _illustState, illustLoadingMore)
    fun loadMoreManga() = loadMore(mangaPager, _mangaState, mangaLoadingMore)
    fun loadMoreNovel() = loadMore(novelPager, _novelState, novelLoadingMore)
    fun loadMoreWalk() = loadMore(walkPager, _walkState, walkLoadingMore)

    private fun <T : ceui.loxia.KListShow<Item>, Item : Any> loadMore(
        pager: Pager<T, Item>,
        state: MutableStateFlow<UiState<List<Item>>>,
        loadingLock: AtomicBoolean
    ) {
        if (!pager.hasNext.value || !loadingLock.compareAndSet(false, true)) return
        screenModelScope.launch {
            try {
                pager.loadMore()
                state.value = UiState.Success(pager.items.value)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* keep existing items */ }
            finally { loadingLock.set(false) }
        }
    }
}
