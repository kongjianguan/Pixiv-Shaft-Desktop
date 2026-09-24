//! 设置项的持久化。
//!
//! 现有版本用 `java.util.prefs` 存这些键值，这里放在同一个 SQLite 文件里。
//! 键名与默认值沿用现有版本，界面层不必为两套命名做映射。

use crate::db;

/// 已知设置项及其默认值。键名与默认值沿用现有版本。
fn default_of(key: &str) -> Option<String> {
    let value = match key {
        "isDirectConnect" => "true",
        "isUseSecureDns" => "false",
        "imageHostMode" => "0",
        "customImageHost" => "",
        "saveBrowseHistory" => "true",
        "isShowR18" => "false",
        "themeMode" => "system",
        "themeColorIndex" => "0",
        "workFeedMaxColumnWidthDp" => "360",
        "workFeedMaxColumns" => "4",
        "workFeedMinColumnWidthDp" => "280",
        "workTitleMaxLines" => "1",
        "novelFeedMaxColumnWidthDp" => "360",
        "novelFeedMaxColumns" => "4",
        "novelFeedMinColumnWidthDp" => "280",
        "novelTitleMaxLines" => "2",
        "readerFontSizeSp" => "18",
        "readerLineSpacing" => "1.5",
        "readerParagraphSpacingDp" => "12",
        "readerTheme" => "light",
        "illustFileNameTemplate" => "{illustId}_p{pageIndex}",
        "ugoiraFileNameTemplate" => "{illustId}",
        "novelFileNameTemplate" => "{novelId}_{novelTitle}",
        "downloadRootPath" => return Some(download_root_default()),
        _ => return None,
    };
    Some(value.to_string())
}

/// 默认下载目录。
fn download_root_default() -> String {
    match std::env::var("HOME") {
        Ok(home) => format!("{home}/Pictures/PixivShaft"),
        Err(_) => "PixivShaft".to_string(),
    }
}

/// 取出一个设置项，未设置过时返回默认值，未知键返回 None。
pub fn get(key: &str) -> Result<Option<String>, String> {
    let stored = db::with_connection(|connection| {
        connection
            .query_row(
                "SELECT value FROM settings WHERE key = ?1",
                rusqlite::params![key],
                |row| row.get::<_, String>(0),
            )
            .or_else(|error| match error {
                rusqlite::Error::QueryReturnedNoRows => Ok(String::new()),
                other => Err(format!("读取设置 {key} 失败：{other}")),
            })
    })?;
    if !stored.is_empty() {
        return Ok(Some(stored));
    }
    // 空字符串也可能是真实值，因此先看这个键是否已知。
    match default_of(key) {
        Some(default) => Ok(Some(default)),
        None => Ok(None),
    }
}

pub fn get_bool(key: &str, default: bool) -> Result<bool, String> {
    Ok(get(key)?.map(|v| v == "true").unwrap_or(default))
}

pub fn get_int(key: &str, default: i64) -> Result<i64, String> {
    match get(key)? {
        Some(raw) => raw
            .parse()
            .map_err(|e| format!("设置 {key} 的值 {raw} 不是整数：{e}")),
        None => Ok(default),
    }
}

pub fn put(key: &str, value: &str) -> Result<(), String> {
    db::with_connection(|connection| {
        connection
            .execute(
                "INSERT OR REPLACE INTO settings(key, value) VALUES (?1, ?2)",
                rusqlite::params![key, value],
            )
            .map_err(|e| format!("写入设置 {key} 失败：{e}"))?;
        Ok(())
    })
}

pub fn put_bool(key: &str, value: bool) -> Result<(), String> {
    put(key, if value { "true" } else { "false" })
}

pub fn put_int(key: &str, value: i64) -> Result<(), String> {
    put(key, &value.to_string())
}

/// 删除一项设置，让它回到默认值。
pub fn reset(key: &str) -> Result<(), String> {
    db::with_connection(|connection| {
        connection
            .execute("DELETE FROM settings WHERE key = ?1", rusqlite::params![key])
            .map_err(|e| format!("重置设置 {key} 失败：{e}"))?;
        Ok(())
    })
}

