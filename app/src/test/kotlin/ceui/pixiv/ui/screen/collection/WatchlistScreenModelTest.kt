package ceui.pixiv.ui.screen.collection

import ceui.lisa.models.WatchlistMangaItem
import ceui.lisa.models.WatchlistNovelItem
import ceui.loxia.WatchlistMangaResponse
import ceui.loxia.WatchlistNovelResponse
import ceui.pixiv.testutil.fakeApi
import ceui.pixiv.testutil.fakeClient
import ceui.pixiv.testutil.fakeSettingsStore
import ceui.pixiv.testutil.resumeSuspend
import ceui.pixiv.testutil.resumeSuspendError
import ceui.pixiv.ui.state.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class WatchlistScreenModelTest {

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
    fun `manga tab failure does not poison novel tab`() = runBlocking {
        val api = fakeApi { name, args ->
            when (name) {
                "getWatchlistMangas" -> resumeSuspendError(args, IOException("boom"))
                "getWatchlistNovels" -> resumeSuspend(
                    args,
                    WatchlistNovelResponse(
                        listOf(WatchlistNovelItem().apply { id = 1 }),
                    ),
                )
                else -> throw UnsupportedOperationException("unexpected api call: $name")
            }
        }
        val model = WatchlistScreenModel(
            client = fakeClient(api),
            settingsStore = fakeSettingsStore(),
        )

        awaitUntil { model.novelState.value is UiState.Success }
        awaitUntil { model.mangaState.value is UiState.Error }

        assertEquals(listOf(1), (model.novelState.value as UiState.Success).data.map { it.id })
    }

    @Test
    fun `r18 filtered empty first manga page auto pages until visible content`() = runBlocking {
        val api = fakeApi { name, args ->
            when (name) {
                "getWatchlistMangas" -> resumeSuspend(
                    args,
                    WatchlistMangaResponse(
                        listOf(WatchlistMangaItem().apply { id = 1; x_restrict = 1 }),
                        next_url = "https://example.invalid/watchlist/page2",
                    ),
                )
                "getWatchlistNovels" -> resumeSuspend(args, WatchlistNovelResponse())
                "generalGet" -> resumeSuspend(
                    args,
                    """{"series":[{"id":2,"x_restrict":0}],"next_url":null}"""
                        .toResponseBody("application/json".toMediaType()),
                )
                else -> throw UnsupportedOperationException("unexpected api call: $name")
            }
        }
        val model = WatchlistScreenModel(
            client = fakeClient(api),
            settingsStore = fakeSettingsStore(),
        )

        awaitUntil { model.mangaState.value is UiState.Success }

        val data = (model.mangaState.value as UiState.Success).data
        assertEquals(listOf(2), data.map { it.id })
    }

    @Test
    fun `loadMore appends next novel page`() = runBlocking {
        val api = fakeApi { name, args ->
            when (name) {
                "getWatchlistMangas" -> resumeSuspend(args, WatchlistMangaResponse())
                "getWatchlistNovels" -> resumeSuspend(
                    args,
                    WatchlistNovelResponse(
                        listOf(WatchlistNovelItem().apply { id = 1 }),
                        next_url = "https://example.invalid/watchlist/page2",
                    ),
                )
                "generalGet" -> resumeSuspend(
                    args,
                    """{"series":[{"id":2,"x_restrict":0}],"next_url":null}"""
                        .toResponseBody("application/json".toMediaType()),
                )
                else -> throw UnsupportedOperationException("unexpected api call: $name")
            }
        }
        val model = WatchlistScreenModel(
            client = fakeClient(api),
            settingsStore = fakeSettingsStore(),
        )

        awaitUntil { model.novelState.value is UiState.Success }
        model.loadMore(isManga = false)
        awaitUntil {
            (model.novelState.value as? UiState.Success)?.data?.size == 2
        }

        val data = (model.novelState.value as UiState.Success).data
        assertEquals(listOf(1, 2), data.map { it.id })
    }

    private suspend fun awaitUntil(condition: () -> Boolean) {
        withTimeout(5_000L) {
            while (!condition()) delay(10L)
        }
    }
}
