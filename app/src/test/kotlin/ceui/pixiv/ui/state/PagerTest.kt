package ceui.pixiv.ui.state

import ceui.loxia.KListShow
import ceui.pixiv.testutil.fakeClient
import ceui.pixiv.testutil.fakeApi
import ceui.pixiv.testutil.resumeSuspend
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

data class FakeItem(val id: Long) : Serializable
data class FakeResponse(
    val items: List<FakeItem> = listOf(),
    val next_url: String? = null,
) : Serializable, KListShow<FakeItem> {
    override val displayList: List<FakeItem> get() = items
    override val nextPageUrl: String? get() = next_url
}

class PagerTest {

    private fun httpBackedClient(): ceui.pixiv.net.api.Client = fakeClient(
        fakeApi { methodName, args ->
            if (methodName != "generalGet") {
                throw UnsupportedOperationException("unexpected api call: $methodName")
            }
            val responseBody = pagerHttpClient.newCall(
                Request.Builder()
                    .url(args[0] as String)
                    .build(),
            ).execute().use { response ->
                response.body?.bytes()
                    ?: error("pagination test response has no body")
            }
            resumeSuspend(args, responseBody.toResponseBody("application/json".toMediaType()))
        },
    )

    @Test
    fun `refresh sets items and hasNext`() {
        val pager = Pager<FakeResponse, FakeItem>(fakeClient(), FakeResponse::class.java)
        pager.refresh(FakeResponse(listOf(FakeItem(1), FakeItem(2)), "https://next"))
        assertEquals(2, pager.items.value.size)
        assertTrue(pager.hasNext.value)
    }

    @Test
    fun `loadMore fetches next url appends items and advances cursor`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"items":[{"id":3}],"next_url":null}"""),
        )
        server.start()
        val client = httpBackedClient()
        try {
            val pager = Pager<FakeResponse, FakeItem>(client, FakeResponse::class.java)
            pager.refresh(FakeResponse(listOf(FakeItem(1)), server.url("/page2").toString()))
            assertTrue(pager.hasNext.value)

            pager.loadMore()

            assertEquals(listOf(1L, 3L), pager.items.value.map { it.id })
            assertFalse(pager.hasNext.value)
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
            client.close()
        }
    }

    @Test
    fun `loadMore chains pages until next url runs out`() = runBlocking {
        val server = MockWebServer()
        server.start()
        val page3Url = server.url("/page3").toString()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"items":[{"id":2}],"next_url":"$page3Url"}"""),
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"items":[{"id":3}],"next_url":null}"""),
        )
        val client = httpBackedClient()
        try {
            val pager = Pager<FakeResponse, FakeItem>(client, FakeResponse::class.java)
            pager.refresh(FakeResponse(listOf(FakeItem(1)), server.url("/page2").toString()))

            pager.loadMore()
            pager.loadMore()

            assertEquals(listOf(1L, 2L, 3L), pager.items.value.map { it.id })
            assertFalse(pager.hasNext.value)
        } finally {
            server.shutdown()
            client.close()
        }
    }

    @Test
    fun `loadMore without next url is a no-op`() = runBlocking {
        val client = httpBackedClient()
        try {
            val pager = Pager<FakeResponse, FakeItem>(client, FakeResponse::class.java)
            pager.refresh(FakeResponse(listOf(FakeItem(1)), null))

            pager.loadMore()

            assertEquals(listOf(1L), pager.items.value.map { it.id })
            assertFalse(pager.hasNext.value)
        } finally {
            client.close()
        }
    }

    @Test
    fun `loadMoreUntil keeps paging while visible content is empty`() = runBlocking {
        val server = MockWebServer()
        server.start()
        val page3Url = server.url("/page3").toString()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"items":[{"id":1}],"next_url":"$page3Url"}"""),
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"items":[{"id":2}],"next_url":null}"""),
        )
        val client = httpBackedClient()
        try {
            val pager = Pager<FakeResponse, FakeItem>(client, FakeResponse::class.java)
            pager.refresh(FakeResponse(emptyList(), server.url("/page2").toString()))

            var published = 0
            pager.loadMoreUntil(
                hasVisibleContent = { pager.items.value.isNotEmpty() },
                onPageLoaded = { published++ },
            )

            // 首屏为空时自动翻页，出现内容的第一页即停，游标保留给后续手动加载
            assertEquals(listOf(1L), pager.items.value.map { it.id })
            assertTrue(pager.hasNext.value)
            assertEquals(1, published)
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
            client.close()
        }
    }

    @Test
    fun `loadMoreUntil stops immediately when content already visible`() = runBlocking {
        val client = fakeClient()
        try {
            val pager = Pager<FakeResponse, FakeItem>(client, FakeResponse::class.java)
            pager.refresh(FakeResponse(listOf(FakeItem(1)), "https://next"))

            var published = 0
            pager.loadMoreUntil(
                hasVisibleContent = { pager.items.value.isNotEmpty() },
                onPageLoaded = { published++ },
            )

            assertEquals(listOf(1L), pager.items.value.map { it.id })
            assertTrue(pager.hasNext.value)
            assertEquals(0, published)
        } finally {
            client.close()
        }
    }

    @Test
    fun `loadMore 挂起期间 refresh 则丢弃旧分页响应且不覆盖新游标`() = runBlocking {
        val server = MockWebServer()
        // 旧分页响应延迟 500ms 发送 body，模拟挂起中的在途请求
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBodyDelay(500, TimeUnit.MILLISECONDS)
                .setBody("""{"items":[{"id":2}],"next_url":null}"""),
        )
        server.start()
        val client = httpBackedClient()
        try {
            val pager = Pager<FakeResponse, FakeItem>(client, FakeResponse::class.java)
            pager.refresh(FakeResponse(listOf(FakeItem(1)), server.url("/page2").toString()))
            val newNextUrl = server.url("/page2-new").toString()

            val loadMoreJob = launch(Dispatchers.IO) { pager.loadMore() }
            // 等旧请求到达服务器：此时 loadMore 已捕获旧 url 并挂起等待 body
            assertEquals("/page2", server.takeRequest().path)
            // 挂起期间整体刷新：items 整体替换、游标重置、代数递增
            pager.refresh(FakeResponse(listOf(FakeItem(10)), newNextUrl))
            loadMoreJob.join()

            // 旧响应被丢弃：items 保持新 refresh 的结果
            assertEquals(listOf(10L), pager.items.value.map { it.id })
            assertTrue(pager.hasNext.value)

            // 游标没被旧响应覆盖：下一次 loadMore 请求的是新 url 并正确追加
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"items":[{"id":20}],"next_url":null}"""),
            )
            pager.loadMore()

            assertEquals(listOf(10L, 20L), pager.items.value.map { it.id })
            assertFalse(pager.hasNext.value)
            assertEquals(2, server.requestCount)
            assertEquals("/page2-new", server.takeRequest().path)
        } finally {
            server.shutdown()
            client.close()
        }
    }

    companion object {
        private val pagerHttpClient = OkHttpClient()
    }
}
