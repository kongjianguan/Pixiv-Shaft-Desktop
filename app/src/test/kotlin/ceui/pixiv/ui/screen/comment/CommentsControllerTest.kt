package ceui.pixiv.ui.screen.comment

import ceui.loxia.Comment
import ceui.loxia.CommentResponse
import ceui.loxia.KUserState
import ceui.loxia.PostCommentResponse
import ceui.loxia.SelfProfile
import ceui.loxia.Stamp
import ceui.loxia.StampsResponse
import ceui.loxia.User
import ceui.pixiv.net.abstractions.LanguageProvider
import ceui.pixiv.net.abstractions.Settings
import ceui.pixiv.net.abstractions.TokenRefresher
import ceui.pixiv.net.abstractions.TokenStore
import ceui.pixiv.net.api.API
import ceui.pixiv.net.api.Client
import ceui.pixiv.net.impl.StdoutLogger
import ceui.pixiv.ui.state.UiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

class CommentsControllerTest {

    @Test
    fun `loadInitial loads comments and resolves self user id`() = runBlocking {
        val data = FakeApiData(initialComments = listOf(comment(1), comment(2)))
        val harness = createController(data)
        val controller = harness.controller

        try {
            controller.loadInitial()
            awaitUntil { controller.commentsState.value is UiState.Success }

            val state = controller.commentsState.value as UiState.Success
            assertEquals(listOf(1L, 2L), state.data.map { it.id })
            assertEquals(7L, controller.selfUserId.value)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `submit top-level prepends comment and clears draft`() = runBlocking {
        val data = FakeApiData(initialComments = listOf(comment(1)))
        val harness = createController(data)
        val controller = harness.controller

        try {
            controller.loadInitial()
            awaitUntil { controller.commentsState.value is UiState.Success }

            controller.updateDraft("hello")
            controller.submit()
            awaitUntil { controller.commentsState.value is UiState.Success }

            val state = controller.commentsState.value as UiState.Success
            assertEquals(listOf(10L, 1L), state.data.map { it.id })
            assertEquals("", controller.draft.value)
            assertNull(controller.replyingTo.value)
            assertEquals(1, data.posted.size)
            assertNull(data.posted.single().parentId)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `submit reply attaches to parent thread and expands it`() = runBlocking {
        val data = FakeApiData(initialComments = listOf(comment(1)))
        val harness = createController(data)
        val controller = harness.controller

        try {
            controller.loadInitial()
            awaitUntil { controller.commentsState.value is UiState.Success }

            val topLevel = (controller.commentsState.value as UiState.Success).data.single()
            controller.startReply(topLevel, topLevel.id)
            controller.updateDraft("reply")
            controller.submit()
            // 线程从未展开过：发表成功后应补拉服务端回复，
            // 本地新回复（10）与服务端已有回复（30）都在线程里
            awaitUntil { controller.replies.value[1L].orEmpty().map { it.id } == listOf(10L, 30L) }

            assertEquals(listOf(10L, 30L), controller.replies.value[1L].orEmpty().map { it.id })
            assertNull(controller.replyingTo.value)
            assertTrue(1L in controller.expandedReplies.value)
            assertEquals(1L, data.posted.single().parentId)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `delete top-level comment removes it and cleans reply cache`() = runBlocking {
        val data = FakeApiData(initialComments = listOf(comment(1), comment(2)))
        val harness = createController(data)
        val controller = harness.controller

        try {
            controller.loadInitial()
            awaitUntil { controller.commentsState.value is UiState.Success }

            controller.deleteComment(comment(1))
            awaitUntil {
                val state = controller.commentsState.value
                state is UiState.Success && state.data.map { it.id } == listOf(2L)
            }

            assertEquals(listOf("illust" to 1L), data.deleted)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `sendStamp posts empty comment with stamp id`() = runBlocking {
        val data = FakeApiData(initialComments = listOf(comment(1)))
        val harness = createController(data)
        val controller = harness.controller

        try {
            controller.loadInitial()
            awaitUntil { controller.commentsState.value is UiState.Success }

            controller.sendStamp(Stamp(stamp_id = 5L, stamp_url = "https://example.invalid/5.png"))
            awaitUntil { controller.commentsState.value is UiState.Success }

            val state = controller.commentsState.value as UiState.Success
            assertEquals(listOf(10L, 1L), state.data.map { it.id })
            assertEquals(5L, data.posted.single().stampId)
            assertEquals("", data.posted.single().comment)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `loadMore appends next page and advances cursor`() = runBlocking {
        val data = FakeApiData(
            initialComments = listOf(comment(1)),
            nextUrl = "https://example.invalid/comments/page2",
        )
        val harness = createController(data)
        val controller = harness.controller

        try {
            controller.loadInitial()
            awaitUntil { controller.commentsState.value is UiState.Success }
            assertTrue(controller.hasMore.value)

            controller.loadMore()
            awaitUntil {
                val state = controller.commentsState.value
                state is UiState.Success && state.data.size == 2
            }

            val state = controller.commentsState.value as UiState.Success
            assertEquals(listOf(1L, 3L), state.data.map { it.id })
            assertEquals(false, controller.hasMore.value)
        } finally {
            harness.close()
        }
    }

    @Test
    fun `delete reports error when self profile returns zero and retries after recovery`() = runBlocking {
        val data = FakeApiData(initialComments = listOf(comment(1)), selfUserId = 0L)
        val harness = createController(data)
        val controller = harness.controller

        try {
            controller.loadInitial()
            awaitUntil { controller.commentsState.value is UiState.Success }

            controller.deleteComment(comment(1))
            awaitUntil { controller.error.value != null }
            assertTrue(controller.error.value.orEmpty().contains("无法确认"))
            assertNull(controller.selfUserId.value)
            assertEquals(0, data.deleted.size)

            // 无效 id 不得缓存：服务端恢复返回有效 id 后，重试可解析成功
            data.selfUserId = 7L
            controller.retrySelfUserId()
            awaitUntil { controller.selfUserId.value == 7L }
        } finally {
            harness.close()
        }
    }

    private fun createController(data: FakeApiData): TestHarness {
        // 解析器是进程级单例，测试间需要隔离
        ceui.pixiv.ui.util.SelfUserIdResolver.clear()
        val api = fakeApi(data)
        val client = Client(
            settings = object : Settings {
                override val isDirectConnect: Boolean get() = false
                override val isUseSecureDns: Boolean get() = false
                override val imageHostMode: Int get() = 0
                override val customImageHost: String get() = ""
            },
            tokenStore = object : TokenStore {
                override val isLoggedIn: Boolean get() = false
                override fun getAccessToken(): String? = null
                override fun getBearerToken(): String? = null
                override fun getRefreshToken(): String? = null
                override fun saveTokens(accessToken: String?, refreshToken: String?, userJson: String?) {}
                override fun clear() {}
            },
            refresher = object : TokenRefresher {
                override suspend fun refreshAccessToken(currentAccessToken: String?): String? = null
            },
            lang = object : LanguageProvider {
                override fun acceptLanguage(): String = "en"
                override fun appAcceptLanguage(): String = "en"
            },
            logger = StdoutLogger,
            api = api,
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = CommentsController(
            client = client,
            workType = "illust",
            workId = 99L,
            scope = scope,
        )
        return TestHarness(controller, client, scope)
    }

    private fun fakeApi(data: FakeApiData): API {
        val loader = API::class.java.classLoader
        return Proxy.newProxyInstance(loader, arrayOf(API::class.java)) { _, method, args ->
            when (method.name) {
                "getSelfProfile" -> resumeSuspend(args, SelfProfile(User(id = data.selfUserId, name = "Me"), KUserState()))
                "getIllustComments", "getNovelComments" ->
                    resumeSuspend(args, CommentResponse(data.initialComments, data.nextUrl))
                "getIllustReplyComments" ->
                    resumeSuspend(args, CommentResponse(listOf(comment(30, parentId = 1L)), null))
                "postIllustComment", "postNovelComment" -> {
                    val continuationIndex = args.indexOfFirst { it is Continuation<*> }
                    val params = args.take(continuationIndex)
                    val posted = PostedComment(
                        comment = params[1] as String,
                        parentId = params.getOrNull(2) as? Long,
                        stampId = params.getOrNull(3) as? Long,
                    )
                    data.posted += posted
                    val responseComment = comment(
                        id = data.nextCommentId.getAndIncrement(),
                        comment = posted.comment,
                        parentId = posted.parentId,
                    )
                    resumeSuspend(args, PostCommentResponse(comment = responseComment))
                }
                "deleteComment" -> {
                    data.deleted += (args[0] as String) to (args[1] as Long)
                    resumeSuspend(args, Unit)
                }
                "getStamps" -> resumeSuspend(args, StampsResponse(stamps = listOf(Stamp(5L, "https://example.invalid/5.png"))))
                "generalGet" -> resumeSuspend(
                    args,
                    """{"comments":[{"id":3,"user":{"id":7,"name":"Me"}}],"next_url":null}"""
                        .toResponseBody("application/json".toMediaType()),
                )
                else -> throw UnsupportedOperationException("unexpected api call: ${method.name}")
            }
        } as API
    }

    private fun comment(id: Long, comment: String = "body", parentId: Long? = null): Comment =
        Comment(
            id = id,
            comment = comment,
            has_replies = parentId == null,
            user = User(id = 7L, name = "Me"),
        )

    /** 让动态代理支持 suspend 方法：resume continuation 并返回 COROUTINE_SUSPENDED */
    @Suppress("UNCHECKED_CAST")
    private fun resumeSuspend(args: Array<out Any?>, value: Any?): Any {
        val continuation = args.last() as Continuation<Any?>
        continuation.resumeWith(Result.success(value))
        return COROUTINE_SUSPENDED
    }

    private suspend fun awaitUntil(condition: () -> Boolean) {
        withTimeout(5_000L) {
            while (!condition()) delay(10L)
        }
    }

    private class FakeApiData(
        val initialComments: List<Comment>,
        val nextUrl: String? = null,
        var selfUserId: Long = 7L,
    ) {
        val posted = mutableListOf<PostedComment>()
        val deleted = mutableListOf<Pair<String, Long>>()
        val nextCommentId = AtomicLong(10L)
    }

    private data class PostedComment(
        val comment: String,
        val parentId: Long?,
        val stampId: Long?,
    )

    private class TestHarness(
        val controller: CommentsController,
        private val client: Client,
        private val scope: CoroutineScope,
    ) {
        fun close() {
            scope.cancel()
            client.close()
        }
    }
}
