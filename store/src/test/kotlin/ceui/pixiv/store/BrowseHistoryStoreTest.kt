package ceui.pixiv.store

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class BrowseHistoryStoreTest {
    @Test
    fun `upsert rejects invalid entries and replaces duplicate targets`(@TempDir directory: Path) {
        val store = createStore(directory)

        store.upsert("illust", 0L, "ignored", viewedAt = 1L)
        store.upsert("illust", 1L, "   ", viewedAt = 2L)
        store.upsert("illust", 10L, "{\"title\":\"old\"}", viewedAt = 3L)
        store.upsert("illust", 10L, "{\"title\":\"new\"}", viewedAt = 4L)

        val saved = store.all().single()
        assertEquals(10L, saved.targetId)
        assertEquals("{\"title\":\"new\"}", saved.payloadJson)
        assertEquals(4L, saved.viewedAt)
    }

    @Test
    fun `list filters by type searches payload and paginates newest first`(@TempDir directory: Path) {
        val store = createStore(directory)
        store.upsert("illust", 1L, "{\"title\":\"Blue Sky\"}", viewedAt = 10L)
        store.upsert("novel", 2L, "{\"title\":\"Blue Story\"}", viewedAt = 40L)
        store.upsert("illust", 3L, "{\"title\":\"Blue Ocean\"}", viewedAt = 30L)
        store.upsert("illust", 4L, "{\"title\":\"Red Flower\"}", viewedAt = 20L)

        assertEquals(listOf(3L, 4L), store.list("illust", limit = 2L).map { it.targetId })
        assertEquals(listOf(4L, 1L), store.list("illust", limit = 2L, offset = 1L).map { it.targetId })
        assertEquals(listOf(3L, 1L), store.list("illust", query = "  Blue  ").map { it.targetId })
        assertEquals(listOf(2L), store.list("novel").map { it.targetId })
    }

    @Test
    fun `delete operations can remove one type or everything`(@TempDir directory: Path) {
        val store = createStore(directory)
        store.upsert("illust", 1L, "one", viewedAt = 1L)
        store.upsert("illust", 2L, "two", viewedAt = 2L)
        store.upsert("novel", 3L, "three", viewedAt = 3L)

        store.delete("illust", 1L)
        assertEquals(listOf(3L, 2L), store.all().map { it.targetId })

        store.deleteType("illust")
        assertEquals(listOf(3L), store.all().map { it.targetId })

        store.clear()
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `initialization migrates legacy illust and novel history once`(@TempDir directory: Path) {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${directory.resolve("history.db")}")
        ShaftDatabase.Schema.create(driver)
        val database = Database(driver)
        database.queries.illustHistoryQueries.insertIllust(11L, "illust-json", 100L, 0L)
        database.queries.illustHistoryQueries.insertIllust(22L, "novel-json", 200L, 1L)

        val firstStore = BrowseHistoryStore(database.queries.browseHistoryQueries)
        val secondStore = BrowseHistoryStore(database.queries.browseHistoryQueries)

        assertEquals(listOf(22L, 11L), firstStore.all().map { it.targetId })
        assertEquals(listOf("novel", "illust"), secondStore.all().map { it.contentType })
        assertEquals(2, secondStore.all().size)
    }

    private fun createStore(directory: Path): BrowseHistoryStore {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${directory.resolve("history.db")}")
        ShaftDatabase.Schema.create(driver)
        return BrowseHistoryStore(Database(driver).queries.browseHistoryQueries)
    }
}
