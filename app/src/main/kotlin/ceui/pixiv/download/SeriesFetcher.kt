package ceui.pixiv.download

import ceui.loxia.Novel
import ceui.loxia.NovelSeriesDetail
import ceui.loxia.NovelSeriesResp
import ceui.pixiv.net.api.API
import kotlinx.coroutines.delay

/** 分页拉全系列章节的结果。 */
data class FetchedSeries(
    val detail: NovelSeriesDetail?,
    val chapters: List<Novel>,
)

/**
 * 分页拉全系列章节：last_order 传当前已拉篇数，next_url 为空即结束。
 * 下载器与系列页 ScreenModel 共用同一实现；[pageDelayMs] 防止连续翻页触发 429。
 * [pageLimit] 只是防御死循环的 safety net（API 每页约 30 章，200 页 ≈ 6000 章），
 * 正常结束由 next_url / 空页驱动，不受此上限影响。
 * [executePage] 可替换单页请求的执行方式：下载器传入 Call 版实现（注册
 * runningCalls + runInterruptible），让 pause/cancel 能立即中断阻塞读；
 * 默认直接调用 suspend API，系列页行为不变。
 */
suspend fun fetchSeriesChapters(
    appApi: API,
    seriesId: Long,
    pageDelayMs: Long,
    pageLimit: Int = 200,
    executePage: suspend (lastOrder: Int?) -> NovelSeriesResp =
        { appApi.getNovelSeries(seriesId, it) },
): FetchedSeries {
    val all = mutableListOf<Novel>()
    var detail: NovelSeriesDetail? = null
    var lastOrder: Int? = null
    var safety = 0
    while (safety < pageLimit) {
        safety++
        val resp = executePage(lastOrder)
        detail = resp.novel_series_detail ?: detail
        val page = resp.novels.orEmpty()
        if (page.isEmpty()) break
        val existing = all.mapTo(HashSet()) { it.id }
        val fresh = page.filter { it.id !in existing }
        if (fresh.isEmpty()) break
        all.addAll(fresh)
        if (resp.next_url.isNullOrEmpty()) break
        lastOrder = all.size
        if (pageDelayMs > 0) delay(pageDelayMs)
    }
    return FetchedSeries(detail, all)
}
