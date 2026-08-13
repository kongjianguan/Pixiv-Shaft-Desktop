package ceui.pixiv.ui.screen.profile

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import ceui.loxia.IllustResponse
import ceui.loxia.KUserState
import ceui.loxia.Novel
import ceui.loxia.NovelResponse
import ceui.loxia.SelfProfile
import ceui.loxia.User
import ceui.loxia.UserDetailResponse
import ceui.pixiv.store.Database
import ceui.pixiv.store.ShaftDatabase
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
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileScreenModelTest {

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
    fun `novel bookmark tab filters out invisible novels`(@TempDir directory: Path) = runBlocking {
        val api = fakeApi { name, args ->
            when (name) {
                "getSelfProfile" -> resumeSuspend(args, SelfProfile(User(id = 7L, name = "Me"), KUserState()))
                "getUserDetail" -> resumeSuspend(args, UserDetailResponse())
                "getUserBookmarkedIllusts" -> resumeSuspend(args, IllustResponse())
                "getUserCreatedIllusts" -> resumeSuspend(args, IllustResponse())
                "getUserCreatedNovels" -> resumeSuspend(args, NovelResponse())
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
        val model = ProfileScreenModel(
            client = fakeClient(api),
            db = createDatabase(directory),
            settingsStore = fakeSettingsStore(),
        )

        awaitUntil { model.novelBookmarksState.value is UiState.Success }

        val data = (model.novelBookmarksState.value as UiState.Success).data
        assertEquals(listOf(2L), data.map { it.id })
    }

    @Test
    fun `r18 filtered empty first novel bookmark page auto pages until visible content`(@TempDir directory: Path) = runBlocking {
        val api = fakeApi { name, args ->
            when (name) {
                "getSelfProfile" -> resumeSuspend(args, SelfProfile(User(id = 7L, name = "Me"), KUserState()))
                "getUserDetail" -> resumeSuspend(args, UserDetailResponse())
                "getUserBookmarkedIllusts" -> resumeSuspend(args, IllustResponse())
                "getUserCreatedIllusts" -> resumeSuspend(args, IllustResponse())
                "getUserCreatedNovels" -> resumeSuspend(args, NovelResponse())
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
        val model = ProfileScreenModel(
            client = fakeClient(api),
            db = createDatabase(directory),
            settingsStore = fakeSettingsStore(),
        )

        awaitUntil { model.novelBookmarksState.value is UiState.Success }

        val data = (model.novelBookmarksState.value as UiState.Success).data
        assertEquals(listOf(2L), data.map { it.id })
    }

    private fun createDatabase(directory: Path): Database {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${directory.resolve("profile.db")}")
        ShaftDatabase.Schema.create(driver)
        return Database(driver)
    }

    private suspend fun awaitUntil(condition: () -> Boolean) {
        withTimeout(5_000L) {
            while (!condition()) delay(10L)
        }
    }
}
