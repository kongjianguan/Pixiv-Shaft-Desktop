package ceui.pixiv.store

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.nio.file.Path

class StoreTest {
    @Test fun `illust history roundtrip`(@TempDir dir: Path) {
        val driver = app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver("jdbc:sqlite:${dir.resolve("t.db")}")
        ShaftDatabase.Schema.create(driver)
        val db = Database(driver)
        db.queries.illustHistoryQueries.insertIllust(48723512, """{"id":48723512}""", 100L, 0)
        val rows = db.queries.illustHistoryQueries.selectRecentIllusts(10).executeAsList()
        assertEquals(1, rows.size)
        assertEquals(48723512, rows[0].illustID)
        db.queries.illustHistoryQueries.deleteIllust(48723512)
        assertTrue(db.queries.illustHistoryQueries.selectRecentIllusts(10).executeAsList().isEmpty())
    }

    @Test
    fun `download queue migration keeps legacy rows`(@TempDir dir: Path) {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${dir.resolve("legacy.db")}")
        driver.execute(null, """
            CREATE TABLE download_queue (
                id TEXT NOT NULL PRIMARY KEY,
                illustId INTEGER NOT NULL,
                pageIndex INTEGER NOT NULL,
                pageCount INTEGER NOT NULL,
                title TEXT NOT NULL,
                authorName TEXT NOT NULL,
                sourceUrl TEXT NOT NULL,
                outputPath TEXT NOT NULL,
                tempPath TEXT NOT NULL,
                status TEXT NOT NULL,
                bytesDownloaded INTEGER NOT NULL DEFAULT 0,
                totalBytes INTEGER NOT NULL DEFAULT 0,
                errorMessage TEXT,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL
            )
        """.trimIndent(), 0)
        driver.execute(
            null,
            "INSERT INTO download_queue VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            15,
        ) {
            bindString(0, "legacy")
            bindLong(1, 1L)
            bindLong(2, 0L)
            bindLong(3, 1L)
            bindString(4, "title")
            bindString(5, "author")
            bindString(6, "https://example.invalid/image.jpg")
            bindString(7, "/tmp/image.jpg")
            bindString(8, "/tmp/image.jpg.part")
            bindString(9, "COMPLETED")
            bindLong(10, 10L)
            bindLong(11, 10L)
            bindString(12, null)
            bindLong(13, 1L)
            bindLong(14, 1L)
        }

        ShaftDatabase.Schema.create(driver)
        migrateDownloadQueue(driver)

        val row = Database(driver).queries.downloadQueueQueries.selectAll().executeAsList().single()
        assertEquals("IMAGE", row.kind)
        assertNull(row.metadataJson)
        assertEquals("legacy", row.id)
    }
}
