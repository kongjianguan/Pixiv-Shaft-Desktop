//! 暴露给界面的作品接口。

use serde::Deserialize;

#[derive(Deserialize)]
struct RecommendResponse {
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
    let session = crate::session::get()
        .await
        .ok_or_else(|| "尚未登录，请先完成授权".to_string())?;

    let text = crate::api_client::get_with_auth(
        "/v1/illust/recommended?include_ranking_illusts=false&include_privacy_policy=true&filter=for_ios",
        &session.access_token,
    )
    .await?;

    let parsed: RecommendResponse =
        serde_json::from_str(&text).map_err(|e| format!("解析推荐作品失败：{e}"))?;

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
