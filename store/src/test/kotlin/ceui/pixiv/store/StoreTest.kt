package ceui.pixiv.store

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
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
}
