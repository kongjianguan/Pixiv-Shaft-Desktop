//! 暴露给界面的小说接口。
//!
//! 正文不在这里取：`/webview/v2/novel` 返回的是 HTML，正文嵌在其中的一段
//! JavaScript 对象里，解析方案单独处理。

use serde::Deserialize;

#[derive(Deserialize)]
struct NovelListResponse {
    novels: Option<Vec<RawNovel>>,
    next_url: Option<String>,
}

#[derive(Deserialize)]
struct SingleNovelResponse {
    novel: Option<RawNovel>,
}

#[derive(Deserialize)]
struct NovelSeriesResponse {
    novel_series_detail: Option<RawSeriesDetail>,
    novels: Option<Vec<RawNovel>>,
    next_url: Option<String>,
}

#[derive(Deserialize)]
struct RawSeriesDetail {
    id: Option<i64>,
    title: Option<String>,
    caption: Option<String>,
    is_concluded: Option<bool>,
    watchlist_added: Option<bool>,
}

#[derive(Deserialize)]
struct RawNovel {
    id: i64,
    title: Option<String>,
    caption: Option<String>,
    create_date: Option<String>,
    text_length: Option<i64>,
    total_bookmarks: Option<i64>,
    total_view: Option<i64>,
    is_bookmarked: Option<bool>,
    tags: Option<Vec<RawTag>>,
    user: Option<RawUser>,
    image_urls: Option<RawImageUrls>,
    series: Option<RawSeries>,
}

#[derive(Deserialize)]
struct RawTag {
    name: Option<String>,
    translated_name: Option<String>,
}

#[derive(Deserialize)]
struct RawUser {
    id: i64,
    name: Option<String>,
    account: Option<String>,
}

#[derive(Deserialize)]
struct RawImageUrls {
    medium: Option<String>,
    large: Option<String>,
}

#[derive(Deserialize)]
struct RawSeries {
    id: Option<i64>,
    title: Option<String>,
}

/// 带游标的小说列表。
pub struct NovelPage {
    pub novels: Vec<NovelSummary>,
    /// 下一页游标，为空表示没有更多。
    pub next_url: String,
}

/// 小说在列表与详情里所需的信息。
pub struct NovelSummary {
    pub id: i64,
    pub title: String,
    pub caption: String,
    pub create_date: String,
    pub text_length: i64,
    pub total_bookmarks: i64,
    pub total_view: i64,
    pub is_bookmarked: bool,
    pub cover_url: String,
    pub author_id: i64,
    pub author_name: String,
    pub series_id: i64,
    pub series_title: String,
    pub tags: Vec<String>,
}

pub struct NovelSeries {
    pub id: i64,
    pub title: String,
    pub caption: String,
    pub is_concluded: bool,
    pub is_watched: bool,
    pub chapters: Vec<NovelSummary>,
    /// 下一页游标，为空表示没有更多。
    pub next_url: String,
}

/// 取回单篇小说的详情。
pub async fn fetch_novel_detail(novel_id: i64) -> Result<NovelSummary, String> {
    let text =
        crate::api_client::get_authed(&format!("/v2/novel/detail?novel_id={novel_id}")).await?;
    let parsed: SingleNovelResponse =
        serde_json::from_str(&text).map_err(|e| format!("解析小说详情失败：{e}"))?;
    parsed
        .novel
        .map(convert)
        .ok_or_else(|| format!("小说 {novel_id} 的详情为空"))
}

/// 推荐小说。
pub async fn fetch_recommended_novels() -> Result<Vec<NovelSummary>, String> {
    fetch_list("/v1/novel/recommended?include_privacy_policy=true&filter=for_ios").await
}

/// 关注动态里的小说。
pub async fn fetch_follow_novels(restrict: String) -> Result<NovelPage, String> {
    fetch_novel_page(&format!(
        "/v1/novel/follow?restrict={}",
        crate::api_client::encode_component(&restrict)
    ))
    .await
}

/// 按游标取下一页小说列表。
pub async fn fetch_next_novel_page(next_url: String) -> Result<NovelPage, String> {
    fetch_novel_page(&crate::api::comment::path_of(&next_url)).await
}

/// 带游标的小说列表。
pub async fn fetch_novel_page(path: &str) -> Result<NovelPage, String> {
    let text = crate::api_client::get_authed(path).await?;
    let parsed: NovelListResponse =
        serde_json::from_str(&text).map_err(|e| format!("解析小说列表失败：{e}"))?;
    Ok(NovelPage {
        novels: parsed
            .novels
            .unwrap_or_default()
            .into_iter()
            .map(convert)
            .collect(),
        next_url: parsed.next_url.unwrap_or_default(),
    })
}

/// 自己收藏的小说。
pub async fn fetch_bookmarked_novels() -> Result<Vec<NovelSummary>, String> {
    fetch_list("/v1/user/bookmarks/novel?filter=for_ios&restrict=public").await
}

/// 取回系列及其章节。
pub async fn fetch_novel_series(series_id: i64) -> Result<NovelSeries, String> {
    fetch_series(&format!("/v2/novel/series?series_id={series_id}")).await
}

/// 按游标取系列下一页章节。
pub async fn fetch_next_series(next_url: String) -> Result<NovelSeries, String> {
    fetch_series(&crate::api::comment::path_of(&next_url)).await
}

/// 收藏小说。`restrict` 取 `public` 或 `private`。
pub async fn add_novel_bookmark(novel_id: i64, restrict: String) -> Result<(), String> {
    let id = novel_id.to_string();
    crate::api_client::post_with_auth(
        "/v2/novel/bookmark/add",
        &[("novel_id", id.as_str()), ("restrict", restrict.as_str())],
    )
    .await
    .map(|_| ())
}

pub async fn remove_novel_bookmark(novel_id: i64) -> Result<(), String> {
    let id = novel_id.to_string();
    crate::api_client::post_with_auth("/v1/novel/bookmark/delete", &[("novel_id", id.as_str())])
        .await
        .map(|_| ())
}

async fn fetch_list(path: &str) -> Result<Vec<NovelSummary>, String> {
    let text = crate::api_client::get_authed(path).await?;
    let parsed: NovelListResponse =
        serde_json::from_str(&text).map_err(|e| format!("解析小说列表失败：{e}"))?;
    Ok(parsed
        .novels
        .unwrap_or_default()
        .into_iter()
        .map(convert)
        .collect())
}

async fn fetch_series(path: &str) -> Result<NovelSeries, String> {
    let text = crate::api_client::get_authed(path).await?;
    let parsed: NovelSeriesResponse =
        serde_json::from_str(&text).map_err(|e| format!("解析小说系列失败：{e}"))?;
    let detail = parsed.novel_series_detail;

    Ok(NovelSeries {
        id: detail.as_ref().and_then(|d| d.id).unwrap_or(0),
        title: detail
            .as_ref()
            .and_then(|d| d.title.clone())
            .unwrap_or_default(),
        caption: detail
            .as_ref()
            .and_then(|d| d.caption.clone())
            .unwrap_or_default(),
        is_concluded: detail.as_ref().and_then(|d| d.is_concluded).unwrap_or(false),
        is_watched: detail
            .as_ref()
            .and_then(|d| d.watchlist_added)
            .unwrap_or(false),
        chapters: parsed
            .novels
            .unwrap_or_default()
            .into_iter()
            .map(convert)
            .collect(),
        next_url: parsed.next_url.unwrap_or_default(),
    })
}

fn convert(raw: RawNovel) -> NovelSummary {
    NovelSummary {
        id: raw.id,
        title: raw.title.unwrap_or_default(),
        caption: raw.caption.unwrap_or_default(),
        create_date: raw.create_date.unwrap_or_default(),
        text_length: raw.text_length.unwrap_or(0),
        total_bookmarks: raw.total_bookmarks.unwrap_or(0),
        total_view: raw.total_view.unwrap_or(0),
        is_bookmarked: raw.is_bookmarked.unwrap_or(false),
        cover_url: raw
            .image_urls
            .as_ref()
            .and_then(|urls| urls.large.clone().or(urls.medium.clone()))
            .unwrap_or_default(),
        author_id: raw.user.as_ref().map(|u| u.id).unwrap_or(0),
        author_name: raw
            .user
            .as_ref()
            .map(|u| u.name.clone().or(u.account.clone()).unwrap_or_default())
            .unwrap_or_default(),
        series_id: raw.series.as_ref().and_then(|s| s.id).unwrap_or(0),
        series_title: raw
            .series
            .as_ref()
            .and_then(|s| s.title.clone())
            .unwrap_or_default(),
        tags: raw
            .tags
            .unwrap_or_default()
            .into_iter()
            .filter_map(|tag| tag.name.or(tag.translated_name))
            .collect(),
    }
}
