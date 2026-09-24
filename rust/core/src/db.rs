//! SQLite 持久化。
//!
//! 数据库文件与现有版本同址（`~/Library/Application Support/PixivShaft/shaft.db`），
//! 表结构也沿用现有版本，因此现有版本的下载队列与浏览记录在新版本里直接可用。

use std::path::PathBuf;

use rusqlite::Connection;

/// 相对于用户主目录的数据库路径。
const DB_RELATIVE_PATH: &str = "Library/Application Support/PixivShaft/shaft.db";

pub fn database_path() -> Result<PathBuf, String> {
    let home = std::env::var("HOME").map_err(|_| "取不到 HOME 环境变量".to_string())?;
    Ok(PathBuf::from(home).join(DB_RELATIVE_PATH))
}

/// 打开数据库并建好所需的表。
pub fn open() -> Result<Connection, String> {
    let path = database_path()?;
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent).map_err(|e| format!("创建 {} 失败：{e}", parent.display()))?;
    }
    let connection =
        Connection::open(&path).map_err(|e| format!("打开 {} 失败：{e}", path.display()))?;
    create_schema(&connection)?;
    Ok(connection)
}

fn create_schema(connection: &Connection) -> Result<(), String> {
    connection
        .execute_batch(
            r#"
            CREATE TABLE IF NOT EXISTS download_queue (
                id TEXT NOT NULL PRIMARY KEY,
                illustId INTEGER NOT NULL,
                pageIndex INTEGER NOT NULL,
                pageCount INTEGER NOT NULL,
                kind TEXT NOT NULL DEFAULT 'IMAGE',
                title TEXT NOT NULL,
                authorName TEXT NOT NULL,
                sourceUrl TEXT NOT NULL,
                metadataJson TEXT,
                outputPath TEXT NOT NULL,
                tempPath TEXT NOT NULL,
                status TEXT NOT NULL,
                bytesDownloaded INTEGER NOT NULL DEFAULT 0,
                totalBytes INTEGER NOT NULL DEFAULT 0,
                errorMessage TEXT,
                reDownload INTEGER NOT NULL DEFAULT 0,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL
            );
            "#,
        )
        .map_err(|e| format!("建立表结构失败：{e}"))
}
