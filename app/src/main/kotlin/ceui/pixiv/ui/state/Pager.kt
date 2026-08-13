package ceui.pixiv.ui.state

import ceui.loxia.KListShow
import ceui.pixiv.net.api.Client
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Generic next_url-based pager for pixiv list APIs.
 *
 * Usage: ScreenModel calls the initial API, passes the response to [refresh].
 * For subsequent pages, [loadMore] fetches next_url via generalGet + Gson.
 */
class Pager<T : KListShow<Item>, Item : Any>(
    private val client: Client,
    private val responseType: Class<T>,
) {
    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items.asStateFlow()

    private val _hasNext = MutableStateFlow(false)
    val hasNext: StateFlow<Boolean> = _hasNext.asStateFlow()

    private var nextUrl: String? = null
    // 代数计数器：refresh 时递增，用于丢弃挂起期间过期的分页响应
    @Volatile
    private var generation = 0
    private val gson = Gson()

    fun refresh(response: T) {
        generation++
        nextUrl = response.nextPageUrl
        _items.value = response.displayList
        _hasNext.value = !nextUrl.isNullOrEmpty()
    }

    /** Updates already-loaded rows without losing the next-page cursor. */
    fun updateItems(transform: (List<Item>) -> List<Item>) {
        _items.value = transform(_items.value)
    }

    suspend fun loadMore() {
        val url = nextUrl ?: return
        val capturedGeneration = generation
        val body = client.appApi.generalGet(url)
        val json = body.string()
        val response = gson.fromJson(json, responseType)
        // 挂起期间发生了 refresh（代数已变）：丢弃这页旧数据，不追加、不覆盖新游标
        if (generation != capturedGeneration) return
        nextUrl = response.nextPageUrl
        _items.value = _items.value + response.displayList
        _hasNext.value = !nextUrl.isNullOrEmpty()
    }

    /**
     * 持续翻页直到出现可见内容或没有更多页：R18 过滤可能把整页内容全部隐藏，
     * 列表会卡在「空列表 + hasNext=true」且 UI 触发不了下一次加载。
     * 与 BrowseHistory 的「跳过全空页」一致；[hasVisibleContent] 与 [onPageLoaded]
     * 由调用方在每页加载后评估/发布（Pager 不感知过滤规则）。
     */
    suspend fun loadMoreUntil(
        hasVisibleContent: () -> Boolean,
        onPageLoaded: () -> Unit = {},
    ) {
        while (hasNext.value && !hasVisibleContent()) {
            loadMore()
            onPageLoaded()
        }
    }
}
