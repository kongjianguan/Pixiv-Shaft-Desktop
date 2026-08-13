package ceui.pixiv.ui.screen.collection

import ceui.lisa.models.MarkedNovelItem
import ceui.lisa.models.NovelBean
import ceui.loxia.NovelMarkersResponse
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
class NovelMarkersScreenModelTest {

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
    fun `markers auto page past unavailable novel`() = runBlocking {
        val api = fakeApi { name, args ->
            when (name) {
                "getNovelMarkers" -> resumeSuspend(
                    args,
                    NovelMarkersResponse(
                        marked_novels = listOf(marker(id = 1, visible = false)),
                        next_url = "https://example.invalid/markers/page2",
                    ),
                )
                "generalGet" -> resumeSuspend(
                    args,
                    """{"marked_novels":[{"novel":{"id":2,"visible":true,"x_restrict":0},"novel_marker":{"page":2}}],"next_url":null}"""
                        .toResponseBody("application/json".toMediaType()),
                )
                else -> throw UnsupportedOperationException("unexpected api call: $name")
            }
        }
        val model = NovelMarkersScreenModel(
            client = fakeClient(api),
            settingsStore = fakeSettingsStore(),
        )

        awaitUntil { model.markerIds() == listOf(2) }
    }

    private fun marker(id: Int, visible: Boolean): MarkedNovelItem = MarkedNovelItem().apply {
        novel = NovelBean().apply {
            setId(id)
            setVisible(visible)
        }
        novel_marker = MarkedNovelItem.NovelMarker()
    }

    private fun NovelMarkersScreenModel.markerIds(): List<Int> =
        (state.value as? UiState.Success)?.data?.map { it.novel.id }.orEmpty()

    private suspend fun awaitUntil(condition: () -> Boolean) {
        withTimeout(5_000L) {
            while (!condition()) delay(10L)
        }
    }
}
