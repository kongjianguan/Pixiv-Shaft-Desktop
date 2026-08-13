package ceui.pixiv.ui.history

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import ceui.pixiv.store.Database
import ceui.pixiv.store.InMemoryKvStore
import ceui.pixiv.store.SettingsStore
import ceui.pixiv.store.ShaftDatabase
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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

@OptIn(ExperimentalCoroutinesApi::class)
class BrowseHistoryScreenModelTest {

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
    fun `r18 filter skips full hidden pages and shows safe rows`(@TempDir directory: Path) = runBlocking {
        val database = createDatabase(directory)
        val settingsStore = SettingsStore(InMemoryKvStore()) // isShowR18 默认 false
        val store = database.browseHistory
        // 一整页 R18（更新的）+ 1 条全年龄（更旧的）：首屏整页都是 R18，
        // 必须跳过这一页才能看到全年龄条目，且不能停在空列表 + hasMore=true
        repeat(30) { index ->
            store.upsert("illust", 1000L + index, r18Json(1000L + index), viewedAt = 1000L + index)
        }
        store.upsert("illust", 2000L, safeJson(2000L), viewedAt = 1L)

        val model = BrowseHistoryScreenModel(database, settingsStore)
        awaitUntil { model.items.value.isNotEmpty() }

        assertEquals(listOf(2000L), model.items.value.map { it.illust?.id })
        assertEquals(false, model.hasMore.value)
        assertEquals(false, model.isLoading.value)
    }

    @Test
    fun `turning r18 switch on republishes hidden rows`(@TempDir directory: Path) = runBlocking {
        val database = createDatabase(directory)
        val settingsStore = SettingsStore(InMemoryKvStore())
        val store = database.browseHistory
        store.upsert("illust", 1L, safeJson(1L), viewedAt = 1L)
        store.upsert("illust", 2L, r18Json(2L), viewedAt = 2L)

        val model = BrowseHistoryScreenModel(database, settingsStore)
        awaitUntil { model.items.value.isNotEmpty() }
        assertEquals(listOf(1L), model.items.value.map { it.illust?.id })

        settingsStore.setIsShowR18(true)
        awaitUntil { model.items.value.size == 2 }
        assertTrue(model.items.value.any { it.illust?.id == 2L })
    }

    private fun createDatabase(directory: Path): Database {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${directory.resolve("history.db")}")
        ShaftDatabase.Schema.create(driver)
        return Database(driver)
    }

    private fun r18Json(id: Long): String = """{"id":$id,"x_restrict":1,"title":"r18"}"""

    private fun safeJson(id: Long): String = """{"id":$id,"x_restrict":0,"title":"safe"}"""

    private suspend fun awaitUntil(condition: () -> Boolean) {
        withTimeout(5_000L) {
            while (!condition()) delay(10L)
        }
    }
}
