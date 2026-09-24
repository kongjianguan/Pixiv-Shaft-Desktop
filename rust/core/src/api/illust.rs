//! 暴露给界面的作品接口。
//!
//! 列表类接口（推荐、搜索、收藏、排行、相关作品）返回结构一致，共用同一套解析；
//! 详情接口额外带上标签、简介与每一页的地址。

use serde::Deserialize;

#[derive(Deserialize)]
struct IllustListResponse {
    illusts: Vec<RawIllust>,
}

#[derive(Deserialize)]
struct SingleIllustResponse {
    illust: Option<RawIllust>,
}

#[derive(Deserialize)]
struct RawIllust {
    id: i64,
    title: Option<String>,
    caption: Option<String>,
    page_count: Option<i64>,
    width: Option<i64>,
    height: Option<i64>,
    total_bookmarks: Option<i64>,
    total_view: Option<i64>,
    is_bookmarked: Option<bool>,
    tags: Option<Vec<RawTag>>,
    user: Option<RawUser>,
    image_urls: Option<ImageUrls>,
    meta_pages: Option<Vec<RawMetaPage>>,
    meta_single_page: Option<RawMetaSinglePage>,
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
struct ImageUrls {
    medium: Option<String>,
    large: Option<String>,
    original: Option<String>,
}

#[derive(Deserialize)]
struct RawMetaPage {
    image_urls: Option<ImageUrls>,
}

#[derive(Deserialize)]
struct RawMetaSinglePage {
    original_image_url: Option<String>,
}

/// 列表里一张作品卡片所需的信息。
pub struct IllustSummary {
    pub id: i64,
    pub title: String,
    pub author_id: i64,
    pub author_name: String,
    pub page_count: i64,
    pub image_url: String,
}

/// 详情页所需的信息。
pub struct IllustDetail {
    pub id: i64,
    pub title: String,
    pub author_id: i64,
    pub author_name: String,
    pub caption: String,
    pub page_count: i64,
    pub width: i64,
    pub height: i64,
    pub total_bookmarks: i64,
    pub total_view: i64,
    pub is_bookmarked: bool,
    pub tags: Vec<String>,
    /// 每一页的图片地址，按页序排列。
    pub image_urls: Vec<String>,
}

/// 取回推荐插画。
pub async fn fetch_recommended_illusts() -> Result<Vec<IllustSummary>, String> {
    fetch_illusts(
        "/v1/illust/recommended?include_ranking_illusts=false&include_privacy_policy=true&filter=for_ios",
    )
    .await
}

/// 按关键词搜索插画。排序与匹配方式取现有版本的默认值。
pub async fn search_illusts(word: String) -> Result<Vec<IllustSummary>, String> {
    let keyword = encode_query(&word);
    fetch_illusts(&format!(
        "/v1/search/illust?word={keyword}&sort=date_desc&search_target=partial_match_for_tags\
         &merge_plain_keyword_results=true&include_translated_tag_results=true&search_ai_type=0&filter=for_ios"
    ))
    .await
}

/// 取回自己收藏的插画。
pub async fn fetch_bookmarked_illusts() -> Result<Vec<IllustSummary>, String> {
    fetch_illusts("/v1/user/bookmarks/illust?filter=for_ios&restrict=public").await
}

/// 取回排行。`mode` 取现有版本的取值，例如 `day`、`week`、`month`、`day_manga`。
pub async fn fetch_ranking(mode: String) -> Result<Vec<IllustSummary>, String> {
    let mode = encode_query(&mode);
    fetch_illusts(&format!(
        "/v1/illust/ranking?mode={mode}&filter=for_ios"
    ))
    .await
}

/// 取回相关作品。
pub async fn fetch_related_illusts(illust_id: i64) -> Result<Vec<IllustSummary>, String> {
    fetch_illusts(&format!("/v2/illust/related?illust_id={illust_id}")).await
}

/// 取回某个用户的作品。`illust_type` 取 `illust` 或 `manga`。
pub async fn fetch_user_illusts(
    user_id: i64,
    illust_type: String,
) -> Result<Vec<IllustSummary>, String> {
    fetch_illusts(&format!(
        "/v1/user/illusts?filter=for_ios&user_id={user_id}&type={}",
        encode_query(&illust_type)
    ))
    .await
}

/// 取回单个作品的详情。
pub async fn fetch_illust_detail(illust_id: i64) -> Result<IllustDetail, String> {
    let text =
        crate::api_client::get_authed(&format!("/v1/illust/detail?illust_id={illust_id}")).await?;

    let parsed: SingleIllustResponse =
        serde_json::from_str(&text).map_err(|e| format!("解析作品详情失败：{e}"))?;
    let raw = parsed
        .illust
        .ok_or_else(|| format!("作品 {illust_id} 的详情为空"))?;

    // 先取出依赖借用的字段，再移动其余字段。
    let image_urls = page_urls(&raw);
    let tags: Vec<String> = raw
        .tags
        .unwrap_or_default()
        .into_iter()
        .filter_map(|tag| tag.name.or(tag.translated_name))
        .collect();
    let author_id = raw.user.as_ref().map(|u| u.id).unwrap_or(0);
    let author_name = author_name(&raw.user);

    Ok(IllustDetail {
        id: raw.id,
        title: raw.title.unwrap_or_default(),
        author_id,
        author_name,
        caption: raw.caption.unwrap_or_default(),
        page_count: raw.page_count.unwrap_or(1),
        width: raw.width.unwrap_or(0),
        height: raw.height.unwrap_or(0),
        total_bookmarks: raw.total_bookmarks.unwrap_or(0),
        total_view: raw.total_view.unwrap_or(0),
        is_bookmarked: raw.is_bookmarked.unwrap_or(false),
        tags,
        image_urls,
    })
}

/// 收藏一个作品。`restrict` 取 `public` 或 `private`。
pub async fn add_bookmark(illust_id: i64, restrict: String) -> Result<(), String> {
    crate::api_client::post_with_auth(
        "/v2/illust/bookmark/add",
        &[
            ("illust_id", &illust_id.to_string()),
            ("restrict", &restrict),
        ],
    )
    .await
    .map(|_| ())
}

/// 取消收藏。
pub async fn remove_bookmark(illust_id: i64) -> Result<(), String> {
    crate::api_client::post_with_auth(
        "/v1/illust/bookmark/delete",
        &[("illust_id", &illust_id.to_string())],
    )
    .await
    .map(|_| ())
}

/// 每一页的地址。单页作品在 `meta_single_page` 里，多页作品在 `meta_pages` 里。
fn page_urls(raw: &RawIllust) -> Vec<String> {
    if let Some(pages) = raw.meta_pages.as_ref() {
        if !pages.is_empty() {
            return pages
                .iter()
                .filter_map(|page| page.image_urls.as_ref())
                .filter_map(pick_original)
                .collect();
        }
    }
    if let Some(single) = raw.meta_single_page.as_ref() {
        if let Some(url) = single.original_image_url.clone() {
            return vec![url];
        }
    }
    raw.image_urls
        .as_ref()
        .and_then(pick_original)
        .into_iter()
        .collect()
}

fn pick_original(urls: &ImageUrls) -> Option<String> {
    urls.original
        .clone()
        .or_else(|| urls.large.clone())
        .or_else(|| urls.medium.clone())
}

fn author_name(user: &Option<RawUser>) -> String {
    user.as_ref()
        .map(|u| {
            u.name
                .clone()
                .or(u.account.clone())
                .unwrap_or_default()
        })
        .unwrap_or_default()
}

/// 只转义查询串里必须转义的字符。
fn encode_query(value: &str) -> String {
    crate::api_client::encode_component(value)
}

async fn fetch_illusts(path: &str) -> Result<Vec<IllustSummary>, String> {
    let text = crate::api_client::get_authed(path).await?;
    let parsed: IllustListResponse =
        serde_json::from_str(&text).map_err(|e| format!("解析作品列表失败：{e}"))?;

    Ok(parsed.illusts.into_iter().map(summarize).collect())
}

fn summarize(raw: RawIllust) -> IllustSummary {
    let image_url = raw
        .image_urls
        .as_ref()
        .and_then(|urls| urls.large.clone().or(urls.medium.clone()))
        .unwrap_or_default();
    IllustSummary {
        id: raw.id,
        title: raw.title.unwrap_or_default(),
        author_id: raw.user.as_ref().map(|u| u.id).unwrap_or(0),
        author_name: author_name(&raw.user),
        page_count: raw.page_count.unwrap_or(1),
        image_url,
    }
}
