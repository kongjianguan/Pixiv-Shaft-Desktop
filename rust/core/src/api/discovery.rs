//! 发现页的热门标签和 Pixivision 文章。

use serde::Deserialize;

#[derive(Deserialize)]
struct TrendingResponse {
    trend_tags: Vec<RawTrendingTag>,
}

#[derive(Deserialize)]
struct RawTrendingTag {
    tag: String,
    translated_name: Option<String>,
    illust: Option<RawTagIllust>,
}

#[derive(Deserialize)]
struct RawTagIllust {
    image_urls: Option<RawImageUrls>,
}

#[derive(Deserialize)]
struct RawImageUrls {
    medium: Option<String>,
}

pub struct TrendingTag {
    pub tag: String,
    pub translated_name: String,
    pub thumbnail_url: String,
}

#[derive(Deserialize)]
struct SpotlightResponse {
    spotlight_articles: Vec<RawSpotlightArticle>,
    next_url: Option<String>,
}

#[derive(Deserialize)]
struct RawSpotlightArticle {
    id: i64,
    title: Option<String>,
    thumbnail: Option<String>,
    article_url: Option<String>,
    publish_date: Option<String>,
    subcategory_label: Option<String>,
}

pub struct SpotlightArticle {
    pub id: i64,
    pub title: String,
    pub thumbnail_url: String,
    pub article_url: String,
    pub publish_date: String,
    pub subcategory_label: String,
}

pub struct SpotlightPage {
    pub articles: Vec<SpotlightArticle>,
    pub next_url: String,
}

/// 取回插画热门标签。
pub async fn fetch_trending_tags() -> Result<Vec<TrendingTag>, String> {
    let text = crate::api_client::get_authed("/v1/trending-tags/illust?filter=for_ios").await?;
    let parsed: TrendingResponse =
        serde_json::from_str(&text).map_err(|e| format!("解析热门标签失败：{e}"))?;
    Ok(parsed
        .trend_tags
        .into_iter()
        .map(|raw| TrendingTag {
            tag: raw.tag,
            translated_name: raw.translated_name.unwrap_or_default(),
            thumbnail_url: raw
                .illust
                .and_then(|illust| illust.image_urls)
                .and_then(|urls| urls.medium)
                .unwrap_or_default(),
        })
        .collect())
}

/// 取回插画或漫画的 Pixivision 文章。
pub async fn fetch_spotlight_page(category: String) -> Result<SpotlightPage, String> {
    if category != "illust" && category != "manga" {
        return Err(format!("不支持的文章分类：{category}"));
    }
    fetch_articles(&format!(
        "/v1/spotlight/articles?filter=for_ios&category={category}"
    ))
    .await
}

/// 按服务端游标取回后续文章。
pub async fn fetch_next_spotlight_page(next_url: String) -> Result<SpotlightPage, String> {
    fetch_articles(&crate::api::comment::path_of(&next_url)).await
}

async fn fetch_articles(path: &str) -> Result<SpotlightPage, String> {
    let text = crate::api_client::get_authed(path).await?;
    let parsed: SpotlightResponse =
        serde_json::from_str(&text).map_err(|e| format!("解析 Pixivision 文章失败：{e}"))?;
    Ok(SpotlightPage {
        articles: parsed
            .spotlight_articles
            .into_iter()
            .map(|raw| SpotlightArticle {
                id: raw.id,
                title: raw.title.unwrap_or_default(),
                thumbnail_url: raw.thumbnail.unwrap_or_default(),
                article_url: raw.article_url.unwrap_or_default(),
                publish_date: raw.publish_date.unwrap_or_default(),
                subcategory_label: raw.subcategory_label.unwrap_or_default(),
            })
            .collect(),
        next_url: parsed.next_url.unwrap_or_default(),
    })
}
