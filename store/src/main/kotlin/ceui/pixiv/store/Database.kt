package ceui.pixiv.store

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.nio.file.Path

class Database(val driver: SqlDriver)

fun createDatabase(): Database {
    val dbPath = Path.of(System.getProperty("user.home"),
        "Library/Application Support/PixivShaft/shaft.db")
    dbPath.parent.toFile().mkdirs()
    val driver = JdbcSqliteDriver("jdbc:sqlite:${dbPath}")
    return Database(driver)
}
