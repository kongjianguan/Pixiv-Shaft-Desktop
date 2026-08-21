package ceui.pixiv.ui.screen.profile

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.google.gson.Gson
import ceui.loxia.Illust
import ceui.loxia.IllustResponse
import ceui.loxia.KListShow
import ceui.loxia.Novel
import ceui.loxia.NovelResponse
import ceui.loxia.ProfileBean
import ceui.loxia.SelfProfile
import ceui.pixiv.di.AppContainer
import ceui.pixiv.net.api.Client
import ceui.pixiv.store.Database
import ceui.pixiv.store.SettingsStore
import ceui.pixiv.ui.state.PagedFeed
import ceui.pixiv.ui.state.UiState
import ceui.pixiv.ui.history.decodeBrowseHistoryItem
import ceui.pixiv.ui.util.visibleItems
import ceui.pixiv.ui.util.visibleNovels
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

class ProfileScreenModel(
    private val client: Client = AppContainer.client,
    private val db: Database = AppContainer.database,
    private val settingsStore: SettingsStore = AppContainer.settingsStore,
) : ScreenModel {

    // 4 个 tab 各自独立 Pager，常驻不重建：插画收藏 / 小说收藏 / 我的插画 / 我的小说
    private val bookmarkFeed = PagedFeed<IllustResponse, Illust>(client, IllustResponse::class.java) {
        visibleItems(it, settingsStore.isShowR18)
    }
    private val novelBookmarkFeed = PagedFeed<NovelResponse, Novel>(client, NovelResponse::class.java) {
        visibleNovels(it, settingsStore.isShowR18)
    }
    private val createdIllustFeed = PagedFeed<IllustResponse, Illust>(client, IllustResponse::class.java) {
        visibleItems(it, settingsStore.isShowR18)
    }
    private val createdNovelFeed = PagedFeed<NovelResponse, Novel>(client, NovelResponse::class.java) {
        visibleNovels(it, settingsStore.isShowR18)
    }

    private val _profileState = MutableStateFlow<UiState<SelfProfile>>(UiState.Loading)
    val profileState: StateFlow<UiState<SelfProfile>> = _profileState.asStateFlow()

    private val _profileDetailState = MutableStateFlow<UiState<ProfileBean>>(UiState.Loading)
    val profileDetailState: StateFlow<UiState<ProfileBean>> = _profileDetailState.asStateFlow()

    val bookmarksState: StateFlow<UiState<List<Illust>>> = bookmarkFeed.state

    val novelBookmarksState: StateFlow<UiState<List<Novel>>> = novelBookmarkFeed.state

    val createdIllustsState: StateFlow<UiState<List<Illust>>> = createdIllustFeed.state

    val createdNovelsState: StateFlow<UiState<List<Novel>>> = createdNovelFeed.state

    private val _history = MutableStateFlow<List<Illust>>(emptyList())
    val history: StateFlow<List<Illust>> = _history.asStateFlow()
    private val gson = Gson()

    /** 浏览历史原始数据（不过滤），R18 开关变化时重新过滤发布，与 tab 发布方式一致 */
    private var historyRaw: List<Illust> = emptyList()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val novelBookmarksInFlight = ConcurrentHashMap.newKeySet<Long>()

    init {
        loadInitial()
        // R18 开关在设置页切换后，「我的」页常驻不重建，需要监听开关重新发布过滤后的列表
        screenModelScope.launch {
            settingsStore.isShowR18Flow.collect { republishIfLoaded() }
        }
    }

    private fun loadInitial() {
        screenModelScope.launch {
            loadProfile()
            loadHistory()
        }
    }

    fun refresh() {
        screenModelScope.launch {
            _isRefreshing.value = true
            try {
                loadProfile()
                loadHistory()
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    private suspend fun loadProfile() {
        if (_profileState.value !is UiState.Success) {
            _profileState.value = UiState.Loading
        }
        try {
            val profile = client.appApi.getSelfProfile()
            _profileState.value = UiState.Success(profile)
            val userId = profile.profile.user_id.takeIf { it > 0 } ?: profile.profile.id
            loadProfileDetail(userId)
            loadBookmarks(userId)
            loadNovelBookmarks(userId)
            loadCreatedWorks(userId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _profileState.value = UiState.Error(e.message ?: "Failed to load profile")
        }
    }

    private fun loadProfileDetail(userId: Long) {
        screenModelScope.launch {
            _profileDetailState.value = UiState.Loading
            try {
                val detail = client.appApi.getUserDetail(userId)
                _profileDetailState.value = UiState.Success(detail.profile ?: ProfileBean())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _profileDetailState.value = UiState.Error(e.message ?: "Failed to load profile detail")
            }
        }
    }

    private fun loadBookmarks(userId: Long) {
        loadChannel(
            feed = bookmarkFeed,
            errorMessage = "Failed to load bookmarks",
        ) { client.appApi.getUserBookmarkedIllusts(userId, "public") }
    }

    private fun loadNovelBookmarks(userId: Long) {
        loadChannel(
            feed = novelBookmarkFeed,
            errorMessage = "Failed to load novel bookmarks",
        ) { client.appApi.getUserBookmarkedNovels(userId, "public") }
    }

    private fun loadCreatedWorks(userId: Long) {
        loadChannel(
            feed = createdIllustFeed,
            errorMessage = "Failed to load works",
        ) { client.appApi.getUserCreatedIllusts(userId, "illust") }
        loadChannel(
            feed = createdNovelFeed,
            errorMessage = "Failed to load works",
        ) { client.appApi.getUserCreatedNovels(userId) }
    }

    private fun <Response : KListShow<Item>, Item : Any> loadChannel(
        feed: PagedFeed<Response, Item>,
        errorMessage: String,
        fetch: suspend () -> Response,
    ) {
        screenModelScope.launch {
            feed.setLoading()
            try {
                feed.refreshUntilVisible(fetch())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                feed.setError(e.message ?: errorMessage)
            }
        }
    }

    fun loadMoreBookmarks() {
        loadMoreWith(bookmarkFeed)
    }

    fun loadMoreNovelBookmarks() {
        loadMoreWith(novelBookmarkFeed)
    }

    fun loadMoreCreatedIllusts() {
        loadMoreWith(createdIllustFeed)
    }

    fun loadMoreCreatedNovels() {
        loadMoreWith(createdNovelFeed)
    }

    private fun <Response : KListShow<Item>, Item : Any> loadMoreWith(feed: PagedFeed<Response, Item>) {
        if (!feed.tryBeginLoadMore()) return
        screenModelScope.launch {
            try {
                feed.loadMoreAndPublish()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 加载失败保留已有数据
            } finally {
                feed.endLoadMore()
            }
        }
    }

    /** 小说收藏乐观切换（与排行流同款：先更新本地再调 API，失败回滚）。 */
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
        novelBookmarkFeed.pager.updateItems { items ->
            items.map { item ->
                if (item.id == novelId) item.copy(is_bookmarked = isBookmarked) else item
            }
        }
        novelBookmarkFeed.publish()
        // 同一本小说可能同时出现在「我的小说」tab，同步更新避免书签图标不一致。
        // 只在已加载完成时重新发布：Pager 初始为空，提前发布会把仍在加载的
        // 「我的小说」列表闪成 Success(empty)
        if (createdNovelFeed.isSuccess()) {
            createdNovelFeed.pager.updateItems { items ->
                items.map { item ->
                    if (item.id == novelId) item.copy(is_bookmarked = isBookmarked) else item
                }
            }
            createdNovelFeed.publish()
        }
    }

    /** R18 开关变化时重新发布已加载的 tab，已过滤的列表保持过滤（Pager 数据完整，重新过滤即可）。 */
    private fun republishIfLoaded() {
        bookmarkFeed.republishIfLoaded()
        novelBookmarkFeed.republishIfLoaded()
        createdIllustFeed.republishIfLoaded()
        createdNovelFeed.republishIfLoaded()
        if (historyRaw.isNotEmpty()) {
            _history.value = visibleItems(historyRaw, settingsStore.isShowR18)
        }
    }

    private suspend fun loadHistory() {
        try {
            val rows = db.browseHistory.list(
                contentType = "illust",
                limit = 50L,
                offset = 0L,
            )
            val illusts = rows.mapNotNull { row -> decodeBrowseHistoryItem(row, gson)?.illust }
            historyRaw = illusts
            _history.value = visibleItems(illusts, settingsStore.isShowR18)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // History is best-effort
        }
    }
}
