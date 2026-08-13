package ceui.pixiv.ui.screen.collection

import ceui.loxia.Novel
import ceui.loxia.NovelResponse
import ceui.pixiv.testutil.fakeApi
import ceui.pixiv.testutil.fakeClient
import ceui.pixiv.testutil.fakeSettingsStore
import ceui.pixiv.testutil.resumeSuspend
import ceui.pixiv.ui.state.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookmarkedListScreenModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `novel bookmarks filter out invisible novels`() = runBlocking {
        val api = fakeApi { name, args ->
            when (name) {
                "getUserBookmarkedNovels" -> resumeSuspend(
                    args,
                    NovelResponse(
                        listOf(
                            Novel(id = 1L, visible = false, x_restrict = 0),
                            Novel(id = 2L, visible = true, x_restrict = 0),
                        ),
                    ),
                )
                else -> throw UnsupportedOperationException("unexpected api call: $name")
            }
        }
        val model = BookmarkedListScreenModel(
            userId = 7L,
            type = "novel",
            tag = null,
            client = fakeClient(api),
            settingsStore = fakeSettingsStore(),
        )

        awaitUntil { model.novelState.value is UiState.Success }

        val data = (model.novelState.value as UiState.Success).data
        assertEquals(listOf(2L), data.map { it.id })
    }

    @Test
    fun `novel bookmarks keep invisible novels hidden when r18 enabled`() = runBlocking {
        val api = fakeApi { name, args ->
            when (name) {
                "getUserBookmarkedNovels" -> resumeSuspend(
                    args,
                    NovelResponse(
                        listOf(
                            Novel(id = 1L, visible = false, x_restrict = 0),
                            Novel(id = 2L, visible = true, x_restrict = 1),
                        ),
                    ),
                )
                else -> throw UnsupportedOperationException("unexpected api call: $name")
            }
        }
        val settingsStore = fakeSettingsStore().apply { setIsShowR18(true) }
        val model = BookmarkedListScreenModel(
            userId = 7L,
            type = "novel",
            tag = null,
            client = fakeClient(api),
            settingsStore = settingsStore,
        )

        awaitUntil { model.novelState.value is UiState.Success }

        val data = (model.novelState.value as UiState.Success).data
        // R18 开关不影响 visible=false 过滤：隐藏作品始终不出现
        assertEquals(listOf(2L), data.map { it.id })
    }

    @Test
    fun `r18 filtered empty first page auto pages until visible content`() = runBlocking {
        val api = fakeApi { name, args ->
            when (name) {
                "getUserBookmarkedNovels" -> resumeSuspend(
                    args,
                    NovelResponse(
                        listOf(Novel(id = 1L, visible = true, x_restrict = 1)),
                        next_url = "https://example.invalid/page2",
                    ),
                )
                "generalGet" -> resumeSuspend(
                    args,
                    """{"novels":[{"id":2,"x_restrict":0,"visible":true}],"next_url":null}"""
                        .toResponseBody("application/json".toMediaType()),
                )
                else -> throw UnsupportedOperationException("unexpected api call: $name")
            }
        }
        val model = BookmarkedListScreenModel(
            userId = 7L,
            type = "novel",
            tag = null,
            client = fakeClient(api),
            settingsStore = fakeSettingsStore(),
        )

        awaitUntil { model.novelState.value is UiState.Success }

        val data = (model.novelState.value as UiState.Success).data
        assertEquals(listOf(2L), data.map { it.id })
    }

    private suspend fun awaitUntil(condition: () -> Boolean) {
        withTimeout(5_000L) {
            while (!condition()) delay(10L)
        }
    }
}
