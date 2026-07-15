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
    private val gson = Gson()

    fun refresh(response: T) {
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
        val body = client.appApi.generalGet(url)
        val json = body.string()
        val response = gson.fromJson(json, responseType)
        nextUrl = response.nextPageUrl
        _items.value = _items.value + response.displayList
        _hasNext.value = !nextUrl.isNullOrEmpty()
    }
}
