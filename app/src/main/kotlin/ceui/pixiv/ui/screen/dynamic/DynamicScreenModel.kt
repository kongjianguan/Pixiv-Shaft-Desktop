package ceui.pixiv.ui.screen.dynamic

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import ceui.loxia.Illust
import ceui.loxia.IllustResponse
import ceui.loxia.Novel
import ceui.loxia.NovelResponse
import ceui.loxia.UserPreview
import ceui.pixiv.di.AppContainer
import ceui.pixiv.net.api.Client
import ceui.pixiv.store.SettingsStore
import ceui.pixiv.ui.state.Pager
import ceui.pixiv.ui.state.UiState
import ceui.pixiv.ui.util.observeR18Toggle
import ceui.pixiv.ui.util.visibleNovels
import ceui.pixiv.ui.util.visibleItems
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/** 动态页作品流：按 (type, restrict) 组合独立加载，Pager 分页 + 三段式状态。 */
class DynamicScreenModel(
    private val type: String,
    private val restrict: String,
    private val client: Client = AppContainer.client,
    private val settingsStore: SettingsStore = AppContainer.settingsStore,
) : ScreenModel {

    private val illustPager = Pager<IllustResponse, Illust>(client, IllustResponse::class.java)
    private val novelPager = Pager<NovelResponse, Novel>(client, NovelResponse::class.java)

    private val _illustState = MutableStateFlow<UiState<List<Illust>>>(UiState.Loading)
    val illustState: StateFlow<UiState<List<Illust>>> = _illustState.asStateFlow()

    private val _novelState = MutableStateFlow<UiState<List<Novel>>>(UiState.Loading)
    val novelState: StateFlow<UiState<List<Novel>>> = _novelState.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val loadingMore = AtomicBoolean(false)
    private val novelBookmarksInFlight = ConcurrentHashMap.newKeySet<Long>()

    init {
        screenModelScope.launch { fetchInitial() }
        observeR18Toggle(::republishIfLoaded, settingsStore)
    }

    private suspend fun fetchInitial() {
        try {
            fetchCurrent()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setError(e.message ?: "Failed to load follow feed")
        }
    }

    fun refresh() {
        screenModelScope.launch {
            _isRefreshing.value = true
            try {
                fetchCurrent()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 刷新失败时保留已有数据，只有尚无数据时才切到 Error
                if (currentState() !is UiState.Success) {
                    setError(e.message ?: "Failed to load follow feed")
                }
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun loadMore() {
        val pager = if (type == "novel") novelPager else illustPager
        if (!pager.hasNext.value || !loadingMore.compareAndSet(false, true)) return
        screenModelScope.launch {
            try {
                pager.loadMore()
                setSuccess()
                // 加载的页被 R18 过滤后整页为空：继续翻页直到出现可见内容或没有更多页
                pager.loadMoreUntil(::hasVisibleContent, ::setSuccess)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 加载失败保留已有数据
            } finally {
                loadingMore.set(false)
            }
        }
    }

    fun toggleNovelBookmark(novel: Novel) {
        ceui.pixiv.ui.util.toggleNovelBookmark(
            scope = screenModelScope,
            client = client,
            novel = novel,
            inFlight = novelBookmarksInFlight,
            updateLocal = ::updateNovelBookmark,
        )
    }

    private suspend fun fetchCurrent() {
        val pager = if (type == "novel") novelPager else illustPager
        if (type == "novel") {
            val resp = client.appApi.getFollowingCreatedNovels(restrict)
            novelPager.refresh(resp)
        } else {
            val resp = client.appApi.followUserPosts(type, restrict)
            illustPager.refresh(resp)
        }
        setSuccess()
        // 首页被 R18 过滤后整页为空（且还有下一页）时自动翻页，避免卡在空态
        pager.loadMoreUntil(::hasVisibleContent, ::setSuccess)
    }

    private fun hasVisibleContent(): Boolean = if (type == "novel") {
        (_novelState.value as? UiState.Success)?.data?.isNotEmpty() == true
    } else {
        (_illustState.value as? UiState.Success)?.data?.isNotEmpty() == true
    }

    private fun setSuccess() {
        if (type == "novel") {
            _novelState.value = UiState.Success(visibleNovels())
        } else {
            _illustState.value = UiState.Success(visibleItems(illustPager.items.value, settingsStore.isShowR18))
        }
    }

    /** 与 RecommendScreenModel 一致：小说流还要过滤 visible=false（列表接口间歇返回，详情页会 crash） */
    private fun visibleNovels(): List<Novel> =
        visibleNovels(novelPager.items.value, settingsStore.isShowR18)

    private fun setError(message: String) {
        if (type == "novel") {
            _novelState.value = UiState.Error(message)
        } else {
            _illustState.value = UiState.Error(message)
        }
    }

    private fun currentState(): UiState<*> =
        if (type == "novel") _novelState.value else _illustState.value

    private fun updateNovelBookmark(novelId: Long, isBookmarked: Boolean) {
        novelPager.updateItems { items ->
            items.map { item ->
                if (item.id == novelId) item.copy(is_bookmarked = isBookmarked) else item
            }
        }
        // 与 setSuccess 一致，重新过一遍 R18/visible 过滤，避免收藏操作把未过滤的 pager 数据发布出去
        _novelState.value = UiState.Success(visibleNovels())
    }

    /** R18 开关变化时重新过滤已加载内容（Pager 保留完整数据） */
    private fun republishIfLoaded() {
        if (_illustState.value is UiState.Success) {
            _illustState.value = UiState.Success(visibleItems(illustPager.items.value, settingsStore.isShowR18))
        }
        if (_novelState.value is UiState.Success) {
            _novelState.value = UiState.Success(visibleNovels())
        }
    }
}

/** 动态页「推荐用户」货架：独立于 type/restrict，避免切换类型时重复请求。 */
class DynamicRecommendedModel : ScreenModel {

    private val client = AppContainer.client

    private val _recommendedState = MutableStateFlow<UiState<List<UserPreview>>>(UiState.Loading)
    val recommendedState: StateFlow<UiState<List<UserPreview>>> = _recommendedState.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init {
        screenModelScope.launch { fetch() }
    }

    fun refresh() {
        screenModelScope.launch {
            _isRefreshing.value = true
            try {
                fetch()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 推荐用户刷新失败保持现状，不阻塞主列表
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    private suspend fun fetch() {
        try {
            val resp = client.appApi.recommendedUsers()
            _recommendedState.value = UiState.Success(resp.displayList)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _recommendedState.value = UiState.Error(e.message ?: "Failed to load recommended users")
        }
    }
}
