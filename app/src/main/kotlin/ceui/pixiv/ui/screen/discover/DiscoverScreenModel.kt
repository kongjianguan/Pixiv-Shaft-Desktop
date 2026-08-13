package ceui.pixiv.ui.screen.discover

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import ceui.loxia.Article
import ceui.loxia.TrendingTag
import ceui.pixiv.di.AppContainer
import ceui.pixiv.ui.state.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

class DiscoverScreenModel : ScreenModel {

    private val client = AppContainer.client

    private val _tagsState = MutableStateFlow<UiState<List<TrendingTag>>>(UiState.Loading)
    val tagsState: StateFlow<UiState<List<TrendingTag>>> = _tagsState.asStateFlow()

    // 插画排行状态；RankingFeed 内部按 (mode, date) 各自加载并维护内容
    private val _currentMode = MutableStateFlow("day")
    val currentMode: StateFlow<String> = _currentMode.asStateFlow()

    private val _currentDate = MutableStateFlow<String?>(null)
    val currentDate: StateFlow<String?> = _currentDate.asStateFlow()

    // 小说排行状态
    private val _novelMode = MutableStateFlow("day")
    val novelMode: StateFlow<String> = _novelMode.asStateFlow()

    private val _novelDate = MutableStateFlow<String?>(null)
    val novelDate: StateFlow<String?> = _novelDate.asStateFlow()

    // Pixivision 特辑预览（插画分类首页）
    private val _articlesState = MutableStateFlow<UiState<List<Article>>>(UiState.Loading)
    val articlesState: StateFlow<UiState<List<Article>>> = _articlesState.asStateFlow()

    /**
     * 排行刷新计数：外层页面回顶/刷新时递增，RankingFeed 据此回顶并刷新。
     * 用递增计数而非「事件后归零」：排行区滚出外层可视区后组合被销毁，归零会导致
     * 重新组合时读不到本次事件（排行区不回顶不刷新）。
     */
    private val _rankingRefreshTick = MutableStateFlow(0L)
    val rankingRefreshTick: StateFlow<Long> = _rankingRefreshTick.asStateFlow()

    init {
        screenModelScope.launch {
            fetchTags()
            fetchArticles()
        }
    }

    fun selectMode(mode: String) {
        _currentMode.value = mode
    }

    fun selectDate(date: String?) {
        _currentDate.value = date
    }

    fun selectNovelMode(mode: String) {
        _novelMode.value = mode
    }

    fun selectNovelDate(date: String?) {
        _novelDate.value = date
    }

    /** R18 开关关闭时把已选中的 r18 mode 重置回全年龄，避免界面残留 R18 状态 */
    fun syncR18Visibility(showR18: Boolean) {
        if (showR18) return
        if (_currentMode.value.contains("r18")) {
            _currentMode.value = "day"
        }
        if (_novelMode.value.contains("r18")) {
            _novelMode.value = "day"
        }
    }

    fun refresh() {
        // 页面级刷新（回顶快捷键、错误重试）同时触发排行榜刷新
        _rankingRefreshTick.value++
        screenModelScope.launch {
            fetchTags()
            fetchArticles()
        }
    }

    private suspend fun fetchTags() {
        try {
            val resp = client.appApi.trendingTags("illust")
            _tagsState.value = UiState.Success(resp.displayList)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _tagsState.value = UiState.Error(e.message ?: "Failed to load trending tags")
        }
    }

    private suspend fun fetchArticles() {
        try {
            val resp = client.appApi.pixivsionArticles("illust")
            _articlesState.value = UiState.Success(resp.displayList)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _articlesState.value = UiState.Error(e.message ?: "Failed to load pixivision articles")
        }
    }
}
