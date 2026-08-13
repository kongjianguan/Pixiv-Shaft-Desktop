package ceui.pixiv.download

import ceui.loxia.Novel
import ceui.loxia.NovelSeriesDetail
import ceui.loxia.NovelSeriesResp
import ceui.loxia.User
import ceui.pixiv.net.api.API
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

class SeriesFetcherTest {

    @Test
    fun `fetches all pages until next url runs out`() = runBlocking {
        val api = fakeApi(
            page(1, next = "p2"),
            page(2, next = "p3"),
            page(3, next = null),
        )

        val result = fetchSeriesChapters(appApi = api, seriesId = 9L, pageDelayMs = 0)

        assertEquals(listOf(1L, 2L, 3L), result.chapters.map { it.id })
        assertEquals(9L, result.detail?.id)
        assertEquals("My Series", result.detail?.title)
    }

    @Test
    fun `deduplicates repeated ids across pages`() = runBlocking {
        // 第二页与第一页有重叠 id：重复章节必须被跳过，且不能因此误判为「没有新内容」提前终止
        val api = fakeApi(
            page(1, next = "p2"),
            page(2, next = "p3", extraIds = listOf(1L)),
            page(3, next = null),
        )

        val result = fetchSeriesChapters(appApi = api, seriesId = 9L, pageDelayMs = 0)

        assertEquals(listOf(1L, 2L, 3L), result.chapters.map { it.id })
        assertEquals(3, result.chapters.size)
    }

    @Test
    fun `stops on empty page even with next url`() = runBlocking {
        val api = fakeApi(
            page(1, next = "p2"),
            NovelSeriesResp(
                novel_series_detail = null,
                novels = emptyList(),
                next_url = "p3",
            ),
        )

        val result = fetchSeriesChapters(appApi = api, seriesId = 9L, pageDelayMs = 0)

        assertEquals(listOf(1L), result.chapters.map { it.id })
    }

    @Test
    fun `page limit stops runaway pagination`() = runBlocking {
        val api = fakeApi(
            page(1, next = "p2"),
            page(2, next = "p3"),
            page(3, next = "p4"),
            page(4, next = null),
        )

        val result = fetchSeriesChapters(appApi = api, seriesId = 9L, pageDelayMs = 0, pageLimit = 2)

        assertEquals(listOf(1L, 2L), result.chapters.map { it.id })
    }

    /** 按调用顺序依次返回 [pages]；lastOrder 只用于记录调用次数（分页数据预先固定） */
    private fun fakeApi(vararg pages: NovelSeriesResp): API {
        val counter = AtomicInteger(0)
        val loader = API::class.java.classLoader
        return Proxy.newProxyInstance(loader, arrayOf(API::class.java)) { _, method, args ->
            when (method.name) {
                "getNovelSeries" -> {
                    val index = counter.getAndIncrement().coerceAtMost(pages.lastIndex)
                    resumeSuspend(args, pages[index])
                }
                else -> throw UnsupportedOperationException("unexpected api call: ${method.name}")
            }
        } as API
    }

    private fun page(id: Long, next: String?, extraIds: List<Long> = emptyList()) = NovelSeriesResp(
        novel_series_detail = NovelSeriesDetail(
            id = 9L,
            title = "My Series",
            content_count = 3,
            user = User(id = 7L, name = "Artist"),
        ),
        novels = listOf(Novel(id = id, title = "Ch $id")) +
            extraIds.map { Novel(id = it, title = "Ch $it") },
        next_url = next,
    )

    /** 让动态代理支持 suspend 方法：resume continuation 并返回 COROUTINE_SUSPENDED */
    @Suppress("UNCHECKED_CAST")
    private fun resumeSuspend(args: Array<out Any?>, value: Any?): Any {
        val continuation = args.last() as Continuation<Any?>
        continuation.resumeWith(Result.success(value))
        return COROUTINE_SUSPENDED
    }
}
