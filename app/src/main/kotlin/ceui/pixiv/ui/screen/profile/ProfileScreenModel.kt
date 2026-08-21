package ceui.pixiv.ui.screen.profile

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import ceui.loxia.Illust
import ceui.loxia.IllustResponse
import ceui.loxia.Novel
import ceui.loxia.NovelResponse
import ceui.loxia.ProfileBean
import ceui.loxia.SelfProfile
import ceui.pixiv.di.AppContainer
import ceui.pixiv.net.api.Client
import ceui.pixiv.store.Database
import ceui.pixiv.store.SettingsStore
import ceui.pixiv.ui.state.Pager
import ceui.pixiv.ui.state.UiState
import ceui.pixiv.ui.state.hasVisibleContent
import ceui.pixiv.ui.util.visibleItems
import ceui.pixiv.ui.util.visibleNovels
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

class ProfileScreenModel(
    private val client: Client = AppContainer.client,
    private val db: Database = AppContainer.database,
    private val settingsStore: SettingsStore = AppContainer.settingsStore,
) : ScreenModel {

    // 4 个 tab 各自独立 Pager，常驻不重建：插画收藏 / 小说收藏 / 我的插画 / 我的小说
    private val bookmarkPager = Pager<IllustResponse, Illust>(client, IllustResponse::class.java)
    private val novelBookmarkPager = Pager<NovelResponse, Novel>(client, NovelResponse::class.java)
    private val createdIllustPager = Pager<IllustResponse, Illust>(client, IllustResponse::class.java)
    private val createdNovelPager = Pager<NovelResponse, Novel>(client, NovelResponse::class.java)

    private val _profileState = MutableStateFlow<UiState<SelfProfile>>(UiState.Loading)
    val profileState: StateFlow<UiState<SelfProfile>> = _profileState.asStateFlow()

    private val _profileDetailState = MutableStateFlow<UiState<ProfileBean>>(UiState.Loading)
    val profileDetailState: StateFlow<UiState<ProfileBean>> = _profileDetailState.asStateFlow()

    private val _bookmarksState = MutableStateFlow<UiState<List<Illust>>>(UiState.Loading)
    val bookmarksState: StateFlow<UiState<List<Illust>>> = _bookmarksState.asStateFlow()

    private val _novelBookmarksState = MutableStateFlow<UiState<List<Novel>>>(UiState.Loading)
    val novelBookmarksState: StateFlow<UiState<List<Novel>>> = _novelBookmarksState.asStateFlow()

    private val _createdIllustsState = MutableStateFlow<UiState<List<Illust>>>(UiState.Loading)
    val createdIllustsState: StateFlow<UiState<List<Illust>>> = _createdIllustsState.asStateFlow()

    private val _createdNovelsState = MutableStateFlow<UiState<List<Novel>>>(UiState.Loading)
    val createdNovelsState: StateFlow<UiState<List<Novel>>> = _createdNovelsState.asStateFlow()

    private val _history = MutableStateFlow<List<Illust>>(emptyList())
    val history: StateFlow<List<Illust>> = _history.asStateFlow()

    /** 浏览历史原始数据（不过滤），R18 开关变化时重新过滤发布，与 tab 发布方式一致 */
    private var historyRaw: List<Illust> = emptyList()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val loadingMoreBookmarks = AtomicBoolean(false)
    private val loadingMoreNovelBookmarks = AtomicBoolean(false)
    private val loadingMoreCreatedIllusts = AtomicBoolean(false)
    private val loadingMoreCreatedNovels = AtomicBoolean(false)
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
            state = _bookmarksState,
            pager = bookmarkPager,
            hasVisible = ::hasVisibleBookmarks,
            onLoaded = ::publishBookmarks,
            errorMessage = "Failed to load bookmarks",
        ) {
            bookmarkPager.refresh(client.appApi.getUserBookmarkedIllusts(userId, "public"))
        }
    }

    private fun loadNovelBookmarks(userId: Long) {
        loadChannel(
            state = _novelBookmarksState,
            pager = novelBookmarkPager,
            hasVisible = ::hasVisibleNovelBookmarks,
            onLoaded = ::publishNovelBookmarks,
            errorMessage = "Failed to load novel bookmarks",
        ) {
            novelBookmarkPager.refresh(client.appApi.getUserBookmarkedNovels(userId, "public"))
        }
    }

    private fun loadCreatedWorks(userId: Long) {
        loadChannel(
            state = _createdIllustsState,
            pager = createdIllustPager,
            hasVisible = ::hasVisibleCreatedIllusts,
            onLoaded = ::publishCreatedIllusts,
            errorMessage = "Failed to load works",
        ) {
            createdIllustPager.refresh(client.appApi.getUserCreatedIllusts(userId, "illust"))
        }
        loadChannel(
            state = _createdNovelsState,
            pager = createdNovelPager,
            hasVisible = ::hasVisibleCreatedNovels,
            onLoaded = ::publishCreatedNovels,
            errorMessage = "Failed to load works",
        ) {
            createdNovelPager.refresh(client.appApi.getUserCreatedNovels(userId))
        }
    }

    private fun <T : Any> loadChannel(
        state: MutableStateFlow<UiState<List<T>>>,
        pager: Pager<*, T>,
        hasVisible: () -> Boolean,
        onLoaded: () -> Unit,
        errorMessage: String,
        refreshPager: suspend () -> Unit,
    ) {
        screenModelScope.launch {
            state.value = UiState.Loading
            try {
                refreshPager()
                onLoaded()
                // 加载的页被 R18 过滤后整页为空：继续翻页直到出现可见内容或没有更多页
                pager.loadMoreUntil(hasVisible, onLoaded)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.value = UiState.Error(e.message ?: errorMessage)
            }
        }
    }

    fun loadMoreBookmarks() {
        loadMoreWith(loadingMoreBookmarks, bookmarkPager, ::hasVisibleBookmarks) {
            publishBookmarks()
        }
    }

    fun loadMoreNovelBookmarks() {
        loadMoreWith(loadingMoreNovelBookmarks, novelBookmarkPager, ::hasVisibleNovelBookmarks) {
            publishNovelBookmarks()
        }
    }

    fun loadMoreCreatedIllusts() {
        loadMoreWith(loadingMoreCreatedIllusts, createdIllustPager, ::hasVisibleCreatedIllusts) {
            publishCreatedIllusts()
        }
    }

    fun loadMoreCreatedNovels() {
        loadMoreWith(loadingMoreCreatedNovels, createdNovelPager, ::hasVisibleCreatedNovels) {
            publishCreatedNovels()
        }
    }

    private fun loadMoreWith(
        guard: AtomicBoolean,
        pager: Pager<*, *>,
        hasVisible: () -> Boolean,
        onLoaded: () -> Unit,
    ) {
        if (!pager.hasNext.value || !guard.compareAndSet(false, true)) return
        screenModelScope.launch {
            try {
                pager.loadMore()
                onLoaded()
                // 加载的页被 R18 过滤后整页为空：继续翻页直到出现可见内容或没有更多页
                pager.loadMoreUntil(hasVisible, onLoaded)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 加载失败保留已有数据
            } finally {
                guard.set(false)
            }
        }
    }

    private fun publishBookmarks() {
        publish(bookmarkPager, _bookmarksState) { visibleItems(it, settingsStore.isShowR18) }
    }

    private fun hasVisibleBookmarks() = hasVisible(_bookmarksState)

    private fun publishNovelBookmarks() {
        publish(novelBookmarkPager, _novelBookmarksState) { visibleNovels(it, settingsStore.isShowR18) }
    }

    private fun hasVisibleNovelBookmarks() = hasVisible(_novelBookmarksState)

    private fun publishCreatedIllusts() {
        publish(createdIllustPager, _createdIllustsState) { visibleItems(it, settingsStore.isShowR18) }
    }

    private fun hasVisibleCreatedIllusts() = hasVisible(_createdIllustsState)

    private fun publishCreatedNovels() {
        publish(createdNovelPager, _createdNovelsState) { visibleNovels(it, settingsStore.isShowR18) }
    }

    private fun hasVisibleCreatedNovels() = hasVisible(_createdNovelsState)

    private fun <T : Any> publish(
        pager: Pager<*, T>,
        state: MutableStateFlow<UiState<List<T>>>,
        filter: (List<T>) -> List<T>,
    ) {
        state.value = UiState.Success(filter(pager.items.value))
    }

    private fun <T> hasVisible(state: StateFlow<UiState<List<T>>>): Boolean =
        state.value.hasVisibleContent()

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
        novelBookmarkPager.updateItems { items ->
            items.map { item ->
                if (item.id == novelId) item.copy(is_bookmarked = isBookmarked) else item
            }
        }
        publishNovelBookmarks()
        // 同一本小说可能同时出现在「我的小说」tab，同步更新避免书签图标不一致。
        // 只在已加载完成时重新发布：Pager 初始为空，提前发布会把仍在加载的
        // 「我的小说」列表闪成 Success(empty)
        if (_createdNovelsState.value is UiState.Success) {
            createdNovelPager.updateItems { items ->
                items.map { item ->
                    if (item.id == novelId) item.copy(is_bookmarked = isBookmarked) else item
                }
            }
            publishCreatedNovels()
        }
    }

    /** R18 开关变化时重新发布已加载的 tab，已过滤的列表保持过滤（Pager 数据完整，重新过滤即可）。 */
    private fun republishIfLoaded() {
        if (_bookmarksState.value is UiState.Success) publishBookmarks()
        if (_novelBookmarksState.value is UiState.Success) publishNovelBookmarks()
        if (_createdIllustsState.value is UiState.Success) publishCreatedIllusts()
        if (_createdNovelsState.value is UiState.Success) publishCreatedNovels()
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
            val illusts = rows.mapNotNull { row ->
                try {
                    com.google.gson.Gson().fromJson(row.payloadJson, Illust::class.java)
                } catch (_: Exception) { null }
            }
            historyRaw = illusts
            _history.value = visibleItems(illusts, settingsStore.isShowR18)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // History is best-effort
        }
    }
}
