package ceui.pixiv.store

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class DownloadQueueStoreTest {
    @Test
    fun `insert and update preserve every task field`(@TempDir directory: Path) {
        val store = createStore(directory)
        store.insert(task(id = "first", createdAt = 10L))

        store.updateProgress("first", bytesDownloaded = 40L, totalBytes = 100L, updatedAt = 20L)
        store.updateState("first", status = "FAILED", errorMessage = "network", updatedAt = 30L)

        val saved = store.all().single()
        assertEquals("first", saved.id)
        assertEquals(99L, saved.illustId)
        assertEquals(1L, saved.pageIndex)
        assertEquals(3L, saved.pageCount)
        assertEquals("UGOIRA", saved.kind)
        assertEquals("metadata", saved.metadataJson)
        assertEquals(40L, saved.bytesDownloaded)
        assertEquals(100L, saved.totalBytes)
        assertEquals("FAILED", saved.status)
        assertEquals("network", saved.errorMessage)
        assertEquals(30L, saved.updatedAt)
    }

    @Test
    fun `all returns newest tasks first`(@TempDir directory: Path) {
        val store = createStore(directory)
        store.insert(task(id = "old", createdAt = 10L))
        store.insert(task(id = "new", createdAt = 20L))

        assertEquals(listOf("new", "old"), store.all().map { it.id })
    }

    @Test
    fun `reset downloading requeues only active tasks`(@TempDir directory: Path) {
        val store = createStore(directory)
        store.insert(task(id = "active", status = "DOWNLOADING", errorMessage = "stale"))
        store.insert(task(id = "paused", status = "PAUSED", errorMessage = "keep"))

        store.resetDownloading(updatedAt = 50L)

        val tasks = store.all().associateBy { it.id }
        assertEquals("QUEUED", tasks.getValue("active").status)
        assertNull(tasks.getValue("active").errorMessage)
        assertEquals(50L, tasks.getValue("active").updatedAt)
        assertEquals("PAUSED", tasks.getValue("paused").status)
        assertEquals("keep", tasks.getValue("paused").errorMessage)
    }

    @Test
    fun `clear completed and delete leave other tasks intact`(@TempDir directory: Path) {
        val store = createStore(directory)
        store.insert(task(id = "done", status = "COMPLETED"))
        store.insert(task(id = "failed", status = "FAILED"))
        store.insert(task(id = "queued", status = "QUEUED"))

        store.clearCompleted()
        store.delete("failed")

        assertEquals(listOf("queued"), store.all().map { it.id })
    }

    private fun createStore(directory: Path): DownloadQueueStore {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${directory.resolve("queue.db")}")
        ShaftDatabase.Schema.create(driver)
        return DownloadQueueStore(Database(driver).queries.downloadQueueQueries)
    }

    private fun task(
        id: String,
        status: String = "QUEUED",
        errorMessage: String? = null,
        createdAt: Long = 1L,
    ) = DownloadTaskRecord(
        id = id,
        illustId = 99L,
        pageIndex = 1L,
        pageCount = 3L,
        kind = "UGOIRA",
        title = "title",
        authorName = "author",
        sourceUrl = "https://example.invalid/source.zip",
        metadataJson = "metadata",
        outputPath = "/tmp/output.gif",
        tempPath = "/tmp/output.gif.part",
        status = status,
        bytesDownloaded = 0L,
        totalBytes = 0L,
        errorMessage = errorMessage,
        createdAt = createdAt,
        updatedAt = createdAt,
    )
}
