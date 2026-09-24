//! 下载队列。
//!
//! 队列状态与现有版本一致（`QUEUED`/`DOWNLOADING`/`PAUSED`/`COMPLETED`/`FAILED`），
//! 每个状态都写回 SQLite，因此进程重启后队列与进度都还在。
//!
//! 进程启动时会把上次留下的 `DOWNLOADING` 改回 `QUEUED`：那些任务在重启前
//! 被中断，留在 `DOWNLOADING` 会让它们永远不再被处理。

use std::path::PathBuf;
use std::time::{SystemTime, UNIX_EPOCH};

use rusqlite::Connection;

pub const STATUS_QUEUED: &str = "QUEUED";
pub const STATUS_DOWNLOADING: &str = "DOWNLOADING";
pub const STATUS_PAUSED: &str = "PAUSED";
pub const STATUS_COMPLETED: &str = "COMPLETED";
pub const STATUS_FAILED: &str = "FAILED";

#[derive(Clone, Debug)]
pub struct DownloadTask {
    pub id: String,
    pub illust_id: i64,
    pub page_index: i64,
    pub page_count: i64,
    pub title: String,
    pub author_name: String,
    pub source_url: String,
    pub output_path: String,
    pub temp_path: String,
    pub status: String,
    pub bytes_downloaded: i64,
    pub total_bytes: i64,
    pub error_message: Option<String>,
}

fn with_db<T>(action: impl FnOnce(&Connection) -> Result<T, String>) -> Result<T, String> {
    crate::db::with_connection(action)
}

fn now() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

/// 把一个插画页加入队列。
pub fn enqueue_image(
    id: &str,
    illust_id: i64,
    page_index: i64,
    page_count: i64,
    title: &str,
    author_name: &str,
    source_url: &str,
    output_dir: &str,
) -> Result<(), String> {
    let file_name = format!("{illust_id}_p{page_index}.img");
    let output_path = PathBuf::from(output_dir).join(&file_name);
    let temp_path = PathBuf::from(output_dir).join(format!("{file_name}.part"));
    let output_path = output_path.to_string_lossy().to_string();
    let temp_path = temp_path.to_string_lossy().to_string();
    let timestamp = now();

    with_db(|connection| {
        connection
            .execute(
                "INSERT OR REPLACE INTO download_queue(
                    id, illustId, pageIndex, pageCount, kind, title, authorName, sourceUrl,
                    outputPath, tempPath, status, bytesDownloaded, totalBytes, reDownload,
                    createdAt, updatedAt
                 ) VALUES (?1, ?2, ?3, ?4, 'IMAGE', ?5, ?6, ?7, ?8, ?9, ?10, 0, 0, 0, ?11, ?11)",
                rusqlite::params![
                    id,
                    illust_id,
                    page_index,
                    page_count,
                    title,
                    author_name,
                    source_url,
                    output_path,
                    temp_path,
                    STATUS_QUEUED,
                    timestamp,
                ],
            )
            .map_err(|e| format!("写入下载队列失败：{e}"))?;
        Ok(())
    })
}

/// 列出队列中的全部任务，按加入时间倒序。
pub fn list() -> Result<Vec<DownloadTask>, String> {
    with_db(|connection| {
        let mut statement = connection
            .prepare(
                "SELECT id, illustId, pageIndex, pageCount, title, authorName, sourceUrl,
                        outputPath, tempPath, status, bytesDownloaded, totalBytes, errorMessage
                 FROM download_queue ORDER BY createdAt DESC",
            )
            .map_err(|e| format!("读取下载队列失败：{e}"))?;
        let rows = statement
            .query_map([], |row| {
                Ok(DownloadTask {
                    id: row.get(0)?,
                    illust_id: row.get(1)?,
                    page_index: row.get(2)?,
                    page_count: row.get(3)?,
                    title: row.get(4)?,
                    author_name: row.get(5)?,
                    source_url: row.get(6)?,
                    output_path: row.get(7)?,
                    temp_path: row.get(8)?,
                    status: row.get(9)?,
                    bytes_downloaded: row.get(10)?,
                    total_bytes: row.get(11)?,
                    error_message: row.get(12)?,
                })
            })
            .map_err(|e| format!("遍历下载队列失败：{e}"))?;
        rows.collect::<Result<Vec<_>, _>>()
            .map_err(|e| format!("读取下载队列行失败：{e}"))
    })
}

pub fn set_status(id: &str, status: &str, error: Option<&str>) -> Result<(), String> {
    with_db(|connection| {
        connection
            .execute(
                "UPDATE download_queue SET status = ?1, errorMessage = ?2, updatedAt = ?3 WHERE id = ?4",
                rusqlite::params![status, error, now(), id],
            )
            .map_err(|e| format!("更新任务状态失败：{e}"))?;
        Ok(())
    })
}

pub fn set_progress(id: &str, bytes: i64, total: i64) -> Result<(), String> {
    with_db(|connection| {
        connection
            .execute(
                "UPDATE download_queue SET bytesDownloaded = ?1, totalBytes = ?2, updatedAt = ?3 WHERE id = ?4",
                rusqlite::params![bytes, total, now(), id],
            )
            .map_err(|e| format!("更新下载进度失败：{e}"))?;
        Ok(())
    })
}

pub fn remove(id: &str) -> Result<(), String> {
    with_db(|connection| {
        connection
            .execute("DELETE FROM download_queue WHERE id = ?1", rusqlite::params![id])
            .map_err(|e| format!("删除任务失败：{e}"))?;
        Ok(())
    })
}

pub fn status_of(id: &str) -> Result<Option<String>, String> {
    with_db(|connection| {
        connection
            .query_row(
                "SELECT status FROM download_queue WHERE id = ?1",
                rusqlite::params![id],
                |row| row.get::<_, String>(0),
            )
            .map(Some)
            .or_else(|e| match e {
                rusqlite::Error::QueryReturnedNoRows => Ok(None),
                other => Err(format!("读取任务状态失败：{other}")),
            })
    })
}

/// 把上次中断的任务改回待处理。进程启动时调用一次。
pub fn requeue_interrupted() -> Result<usize, String> {
    with_db(|connection| {
        connection
            .execute(
                "UPDATE download_queue SET status = ?1, errorMessage = NULL WHERE status = ?2",
                rusqlite::params![STATUS_QUEUED, STATUS_DOWNLOADING],
            )
            .map_err(|e| format!("重置中断任务失败：{e}"))
    })
}

/// 处理一个任务：取回图片字节并写盘。成功与失败都写回数据库。
pub async fn process(task: &DownloadTask) -> Result<(), String> {
    set_status(&task.id, STATUS_DOWNLOADING, None)?;

    let bytes = match crate::image::fetch_image(&task.source_url).await {
        Ok(bytes) => bytes,
        Err(error) => {
            set_status(&task.id, STATUS_FAILED, Some(&error))?;
            return Err(error);
        }
    };
    set_progress(&task.id, bytes.len() as i64, bytes.len() as i64)?;

    // 传输过程中可能已被暂停，暂停时保留已下内容，把状态留在暂停上。
    if status_of(&task.id)?.as_deref() == Some(STATUS_PAUSED) {
        std::fs::write(&task.temp_path, &bytes)
            .map_err(|e| format!("写入临时文件 {} 失败：{e}", task.temp_path))?;
        return Ok(());
    }

    std::fs::write(&task.temp_path, &bytes)
        .map_err(|e| format!("写入临时文件 {} 失败：{e}", task.temp_path))?;
    std::fs::rename(&task.temp_path, &task.output_path)
        .map_err(|e| format!("移动到 {} 失败：{e}", task.output_path))?;

    set_status(&task.id, STATUS_COMPLETED, None)
}

/// 处理队列里所有待处理的任务，返回每个任务的结果。
pub async fn process_queue() -> Result<Vec<(String, Result<(), String>)>, String> {
    let pending: Vec<DownloadTask> = list()?
        .into_iter()
        .filter(|task| task.status == STATUS_QUEUED)
        .collect();

    let mut results = Vec::with_capacity(pending.len());
    for task in pending {
        let outcome = process(&task).await;
        results.push((task.id.clone(), outcome));
    }
    Ok(results)
}
