//! 暴露给界面的作品接口。
//!
//! 推荐、搜索、收藏三个接口返回的结构一致，共用同一套解析。

use serde::Deserialize;

#[derive(Deserialize)]
struct IllustListResponse {
    illusts: Vec<RawIllust>,
}

#[derive(Deserialize)]
struct RawIllust {
    id: i64,
    title: Option<String>,
    image_urls: Option<ImageUrls>,
}

#[derive(Deserialize)]
struct ImageUrls {
    medium: Option<String>,
    large: Option<String>,
}

pub struct IllustSummary {
    pub id: i64,
    pub title: String,
    pub image_url: String,
}

/// 取回推荐插画。图片字节由界面层另行调用 `fetch_image` 经反墙链路获取。
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

/// 只转义查询串里必须转义的字符。
fn encode_query(value: &str) -> String {
    let mut out = String::with_capacity(value.len());
    for byte in value.as_bytes() {
        match byte {
            b'a'..=b'z' | b'A'..=b'Z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(*byte as char)
            }
            b' ' => out.push_str("%20"),
            other => out.push_str(&format!("%{other:02X}")),
        }
    }
    out
}

async fn fetch_illusts(path: &str) -> Result<Vec<IllustSummary>, String> {
    let session = crate::session::get()
        .await
        .ok_or_else(|| "尚未登录，请先完成授权".to_string())?;

    let text = crate::api_client::get_with_auth(path, &session.access_token).await?;
    let parsed: IllustListResponse =
        serde_json::from_str(&text).map_err(|e| format!("解析作品列表失败：{e}"))?;

    Ok(parsed
        .illusts
        .into_iter()
        .filter_map(|illust| {
            let urls = illust.image_urls?;
            let image_url = urls.large.or(urls.medium)?;
            Some(IllustSummary {
                id: illust.id,
                title: illust.title.unwrap_or_default(),
                image_url,
            })
        })
        .collect())
}
