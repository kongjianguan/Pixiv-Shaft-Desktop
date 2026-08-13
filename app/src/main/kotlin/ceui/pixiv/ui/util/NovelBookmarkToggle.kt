package ceui.pixiv.ui.util

import ceui.loxia.Novel
import ceui.pixiv.net.api.Client
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * 小说收藏乐观切换公共逻辑（推荐/搜索/动态/收藏列表/系列页/我的页面共用）：
 * 先本地翻转 is_bookmarked，再调 API，失败或取消时回滚。
 * [inFlight] 防止同一作品的并发重复请求；[updateLocal] 由调用方把翻转后的
 * is_bookmarked 应用到自己的列表状态。
 */
fun toggleNovelBookmark(
    scope: CoroutineScope,
    client: Client,
    novel: Novel,
    inFlight: MutableSet<Long>,
    updateLocal: (novelId: Long, isBookmarked: Boolean) -> Unit,
) {
    if (!inFlight.add(novel.id)) return
    val wasBookmarked = novel.is_bookmarked == true
    updateLocal(novel.id, !wasBookmarked)
    scope.launch {
        try {
            if (wasBookmarked) {
                client.appApi.removeNovelBookmark(novel.id)
            } else {
                client.appApi.addNovelBookmark(novel.id, "public")
            }
        } catch (e: CancellationException) {
            updateLocal(novel.id, wasBookmarked)
            throw e
        } catch (_: Exception) {
            updateLocal(novel.id, wasBookmarked)
        } finally {
            inFlight.remove(novel.id)
        }
    }
}
