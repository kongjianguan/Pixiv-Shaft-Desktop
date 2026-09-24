//! 浏览记录与搜索记录。
//!
//! 表结构与现有版本一致。搜索记录沿用现有版本的写法：先按关键词删掉旧条目再插入，
//! 否则同一个关键词会在列表里出现多条。

use std::time::{SystemTime, UNIX_EPOCH};

use crate::db;

/// 内容类型，与现有版本一致。
pub const TYPE_ILLUST: &str = "illust";
pub const TYPE_NOVEL: &str = "novel";

pub struct BrowseEntry {
    pub content_type: String,
    pub target_id: i64,
    pub payload_json: String,
    pub viewed_at: i64,
}

pub struct SearchEntry {
    pub id: i64,
    pub keyword: String,
    pub search_time: i64,
    pub search_type: i64,
    pub pinned: bool,
}

fn now() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

/// 记录一次浏览。同一目标重复浏览只更新时间。
pub fn record_browse(
    content_type: &str,
    target_id: i64,
    payload_json: &str,
) -> Result<(), String> {
    db::with_connection(|connection| {
        connection
            .execute(
                "INSERT OR REPLACE INTO browse_history(contentType, targetId, payloadJson, viewedAt)
                 VALUES (?1, ?2, ?3, ?4)",
                rusqlite::params![content_type, target_id, payload_json, now()],
            )
            .map_err(|e| format!("记录浏览失败：{e}"))?;
        Ok(())
    })
}

/// 按类型列出浏览记录，最近的在前。
pub fn list_browse(content_type: &str, limit: i64, offset: i64) -> Result<Vec<BrowseEntry>, String> {
    db::with_connection(|connection| {
        let mut statement = connection
            .prepare(
                "SELECT contentType, targetId, payloadJson, viewedAt FROM browse_history
                 WHERE contentType = ?1 ORDER BY viewedAt DESC LIMIT ?2 OFFSET ?3",
            )
            .map_err(|e| format!("读取浏览记录失败：{e}"))?;
        let rows = statement
            .query_map(rusqlite::params![content_type, limit, offset], |row| {
                Ok(BrowseEntry {
                    content_type: row.get(0)?,
                    target_id: row.get(1)?,
                    payload_json: row.get(2)?,
                    viewed_at: row.get(3)?,
                })
            })
            .map_err(|e| format!("遍历浏览记录失败：{e}"))?;
        rows.collect::<Result<Vec<_>, _>>()
            .map_err(|e| format!("读取浏览记录行失败：{e}"))
    })
}

pub fn delete_browse(content_type: &str, target_id: i64) -> Result<(), String> {
    db::with_connection(|connection| {
        connection
            .execute(
                "DELETE FROM browse_history WHERE contentType = ?1 AND targetId = ?2",
                rusqlite::params![content_type, target_id],
            )
            .map_err(|e| format!("删除浏览记录失败：{e}"))?;
        Ok(())
    })
}

pub fn clear_browse() -> Result<(), String> {
    db::with_connection(|connection| {
        connection
            .execute("DELETE FROM browse_history", [])
            .map_err(|e| format!("清空浏览记录失败：{e}"))?;
        Ok(())
    })
}

/// 记录一次搜索。同一关键词的旧条目先删掉，避免重复。
pub fn record_search(keyword: &str, search_type: i64) -> Result<(), String> {
    db::with_connection(|connection| {
        connection
            .execute(
                "DELETE FROM search_table WHERE keyword = ?1",
                rusqlite::params![keyword],
            )
            .map_err(|e| format!("清理旧搜索记录失败：{e}"))?;
        connection
            .execute(
                "INSERT INTO search_table(keyword, searchTime, searchType, pinned)
                 VALUES (?1, ?2, ?3, 0)",
                rusqlite::params![keyword, now(), search_type],
            )
            .map_err(|e| format!("记录搜索失败：{e}"))?;
        Ok(())
    })
}

/// 列出搜索记录：置顶的在前，其余按时间倒序。置顶不占最近条目的名额。
pub fn list_searches(recent_limit: i64) -> Result<Vec<SearchEntry>, String> {
    db::with_connection(|connection| {
        let mut statement = connection
            .prepare(
                "SELECT id, keyword, searchTime, searchType, pinned FROM search_table
                 WHERE pinned = 1
                 UNION ALL
                 SELECT id, keyword, searchTime, searchType, pinned FROM (
                     SELECT * FROM search_table WHERE pinned = 0 ORDER BY searchTime DESC LIMIT ?1
                 )
                 ORDER BY pinned DESC, searchTime DESC",
            )
            .map_err(|e| format!("读取搜索记录失败：{e}"))?;
        let rows = statement
            .query_map(rusqlite::params![recent_limit], |row| {
                Ok(SearchEntry {
                    id: row.get(0)?,
                    keyword: row.get(1)?,
                    search_time: row.get(2)?,
                    search_type: row.get(3)?,
                    pinned: row.get::<_, i64>(4)? != 0,
                })
            })
            .map_err(|e| format!("遍历搜索记录失败：{e}"))?;
        rows.collect::<Result<Vec<_>, _>>()
            .map_err(|e| format!("读取搜索记录行失败：{e}"))
    })
}

pub fn set_search_pinned(id: i64, pinned: bool) -> Result<(), String> {
    db::with_connection(|connection| {
        connection
            .execute(
                "UPDATE search_table SET pinned = ?1 WHERE id = ?2",
                rusqlite::params![if pinned { 1 } else { 0 }, id],
            )
            .map_err(|e| format!("更新搜索记录置顶状态失败：{e}"))?;
        Ok(())
    })
}

pub fn delete_search(id: i64) -> Result<(), String> {
    db::with_connection(|connection| {
        connection
            .execute("DELETE FROM search_table WHERE id = ?1", rusqlite::params![id])
            .map_err(|e| format!("删除搜索记录失败：{e}"))?;
        Ok(())
    })
}

pub fn clear_searches() -> Result<(), String> {
    db::with_connection(|connection| {
        connection
            .execute("DELETE FROM search_table", [])
            .map_err(|e| format!("清空搜索记录失败：{e}"))?;
        Ok(())
    })
}
