//! 暴露给界面的设置与历史记录接口。

use crate::history;
use crate::settings;

/// 读取一项设置。未设置过时返回该键的默认值，键未知时返回空。
pub fn setting(key: String) -> Result<String, String> {
    Ok(settings::get(&key)?.unwrap_or_default())
}

pub fn set_setting(key: String, value: String) -> Result<(), String> {
    settings::put(&key, &value)
}

/// 删除一项设置，让它回到默认值。
pub fn reset_setting(key: String) -> Result<(), String> {
    settings::reset(&key)
}

pub struct BrowseRecord {
    pub content_type: String,
    pub target_id: i64,
    pub payload_json: String,
    pub viewed_at: i64,
}

/// 记录一次浏览。界面层按设置决定是否调用。
pub fn record_browse(
    content_type: String,
    target_id: i64,
    payload_json: String,
) -> Result<(), String> {
    history::record_browse(&content_type, target_id, &payload_json)
}

pub fn list_browse(
    content_type: String,
    limit: i64,
    offset: i64,
) -> Result<Vec<BrowseRecord>, String> {
    Ok(history::list_browse(&content_type, limit, offset)?
        .into_iter()
        .map(|entry| BrowseRecord {
            content_type: entry.content_type,
            target_id: entry.target_id,
            payload_json: entry.payload_json,
            viewed_at: entry.viewed_at,
        })
        .collect())
}

pub fn delete_browse(content_type: String, target_id: i64) -> Result<(), String> {
    history::delete_browse(&content_type, target_id)
}

pub fn clear_browse() -> Result<(), String> {
    history::clear_browse()
}

pub struct SearchRecord {
    pub id: i64,
    pub keyword: String,
    pub search_time: i64,
    pub search_type: i64,
    pub pinned: bool,
}

/// 记录一次搜索。同一个关键词只会留下最新一条。
pub fn record_search(keyword: String, search_type: i64) -> Result<(), String> {
    history::record_search(&keyword, search_type)
}

pub fn list_searches(recent_limit: i64) -> Result<Vec<SearchRecord>, String> {
    Ok(history::list_searches(recent_limit)?
        .into_iter()
        .map(|entry| SearchRecord {
            id: entry.id,
            keyword: entry.keyword,
            search_time: entry.search_time,
            search_type: entry.search_type,
            pinned: entry.pinned,
        })
        .collect())
}

pub fn set_search_pinned(id: i64, pinned: bool) -> Result<(), String> {
    history::set_search_pinned(id, pinned)
}

pub fn delete_search(id: i64) -> Result<(), String> {
    history::delete_search(id)
}

pub fn clear_searches() -> Result<(), String> {
    history::clear_searches()
}
