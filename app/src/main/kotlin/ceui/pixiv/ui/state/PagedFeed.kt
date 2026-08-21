package ceui.pixiv.ui.state

import ceui.loxia.KListShow
import ceui.pixiv.net.api.Client
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Type-safe state holder for a next_url-based feed.
 *
 * The response and item types remain owned by each screen model. This class only
 * centralizes the repeated pager, filtered-state, and load-more coordination.
 */
class PagedFeed<Response : KListShow<Item>, Item : Any>(
    client: Client,
    responseType: Class<Response>,
    private val filter: (List<Item>) -> List<Item> = { it },
) {
    val pager = Pager<Response, Item>(client, responseType)

    private val _state = MutableStateFlow<UiState<List<Item>>>(UiState.Loading)
    val state: StateFlow<UiState<List<Item>>> = _state.asStateFlow()

    private val loadingMore = AtomicBoolean(false)

    fun refresh(response: Response) {
        pager.refresh(response)
        publish()
    }

    suspend fun refreshUntilVisible(response: Response) {
        refresh(response)
        pager.loadMoreUntil(::hasVisibleContent, ::publish)
    }

    fun publish() {
        _state.value = UiState.Success(filter(pager.items.value))
    }

    fun republishIfLoaded() {
        if (_state.value is UiState.Success) publish()
    }

    fun setError(message: String) {
        _state.value = UiState.Error(message)
    }

    fun hasVisibleContent(): Boolean = _state.value.hasVisibleContent()

    fun isSuccess(): Boolean = _state.value is UiState.Success

    fun tryBeginLoadMore(): Boolean =
        pager.hasNext.value && loadingMore.compareAndSet(false, true)

    fun endLoadMore() {
        loadingMore.set(false)
    }

    /** Loads one page, publishes it, then skips filtered-empty pages when needed. */
    suspend fun loadMoreAndPublish() {
        pager.loadMore()
        publish()
        pager.loadMoreUntil(::hasVisibleContent, ::publish)
    }
}
