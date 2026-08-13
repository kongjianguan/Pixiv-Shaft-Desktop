package ceui.pixiv.ui.screen.novel

import ceui.loxia.Novel
import ceui.loxia.NovelSeriesDetail
import ceui.loxia.NovelSeriesResp
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NovelSeriesScreenModelTest {

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
    fun `series hides unavailable and r18 chapters including latest novel`() = runBlocking {
        val api = fakeApi { name, args ->
            when (name) {
                "getNovelSeries" -> resumeSuspend(
                    args,
                    NovelSeriesResp(
                        novel_series_detail = NovelSeriesDetail(id = 8L),
                        novel_series_latest_novel = Novel(id = 3L, visible = true, x_restrict = 1),
                        novels = listOf(
                            Novel(id = 1L, visible = true, x_restrict = 0),
                            Novel(id = 2L, visible = false, x_restrict = 0),
                            Novel(id = 3L, visible = true, x_restrict = 1),
                        ),
                    ),
                )
                else -> throw UnsupportedOperationException("unexpected api call: $name")
            }
        }
        val settingsStore = fakeSettingsStore()
        val model = NovelSeriesScreenModel(
            seriesId = 8L,
            client = fakeClient(api),
            settingsStore = settingsStore,
        )

        awaitUntil { model.chapterIds() == listOf(1L) }
        assertNull(model.latestNovel.value)

        settingsStore.setIsShowR18(true)

        awaitUntil { model.chapterIds() == listOf(1L, 3L) }
        assertEquals(3L, model.latestNovel.value?.id)
    }

    @Test
    fun `series auto pages past an r18-only first page`() = runBlocking {
        val api = fakeApi { name, args ->
            when (name) {
                "getNovelSeries" -> resumeSuspend(
                    args,
                    NovelSeriesResp(
                        novel_series_detail = NovelSeriesDetail(id = 9L),
                        novels = listOf(Novel(id = 1L, visible = true, x_restrict = 1)),
                        next_url = "https://example.invalid/series/page2",
                    ),
                )
                "generalGet" -> resumeSuspend(
                    args,
                    """{"novels":[{"id":2,"visible":true,"x_restrict":0}],"next_url":null}"""
                        .toResponseBody("application/json".toMediaType()),
                )
                else -> throw UnsupportedOperationException("unexpected api call: $name")
            }
        }
        val model = NovelSeriesScreenModel(
            seriesId = 9L,
            client = fakeClient(api),
            settingsStore = fakeSettingsStore(),
        )

        awaitUntil { model.chapterIds() == listOf(2L) }
    }

    private fun NovelSeriesScreenModel.chapterIds(): List<Long> =
        (novelsState.value as? UiState.Success)?.data?.map { it.id }.orEmpty()

    private suspend fun awaitUntil(condition: () -> Boolean) {
        withTimeout(5_000L) {
            while (!condition()) delay(10L)
        }
    }
}
