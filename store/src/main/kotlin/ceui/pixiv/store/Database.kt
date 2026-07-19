package ceui.pixiv.store

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.nio.file.Path

class Database(val driver: SqlDriver) {
    val queries: ShaftDatabase by lazy { ShaftDatabase(driver) }
    val browseHistory: BrowseHistoryStore by lazy { BrowseHistoryStore(queries.browseHistoryQueries) }
}

fun createDatabase(): Database {
    val dbPath = Path.of(System.getProperty("user.home"),
        "Library/Application Support/PixivShaft/shaft.db")
    dbPath.parent.toFile().mkdirs()
    val driver = JdbcSqliteDriver("jdbc:sqlite:${dbPath}")
    ShaftDatabase.Schema.create(driver)
    migrateDownloadQueue(driver)
    return Database(driver)
}

internal fun migrateDownloadQueue(driver: SqlDriver) {
    listOf(
        "ALTER TABLE download_queue ADD COLUMN kind TEXT NOT NULL DEFAULT 'IMAGE'",
        "ALTER TABLE download_queue ADD COLUMN metadataJson TEXT",
    ).forEach { statement ->
        try {
            driver.execute(null, statement, 0)
        } catch (error: Exception) {
            val message = error.message.orEmpty()
            if (!message.contains("duplicate column name", ignoreCase = true)) {
                throw error
            }
        }
    }
}
