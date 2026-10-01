//! 反墙传输通路的选择。
//!
//! 接口端点与授权端点都在 Cloudflare 后面，先走 ECH（加密 SNI 的 TCP 直连），
//! 失败再走 QUIC（UDP）。两条都不通时把各自的失败原因一并报出，便于判断是
//! 哪一层的问题。

use reqwest::header::HeaderMap;

/// 接口端点。
pub const APP_API_HOST: &str = "app-api.pixiv.net";
/// 授权令牌端点。
pub const OAUTH_HOST: &str = "oauth.secure.pixiv.net";

pub async fn request(
    host: &str,
    method: &str,
    path: &str,
    headers: HeaderMap,
    body: Option<String>,
) -> Result<(u16, String), String> {
    match crate::ech::request(host, method, path, headers.clone(), body.clone()).await {
        Ok(result) => Ok(result),
        Err(ech_error) => crate::quic::request(host, method, path, headers, body)
            .await
            .map_err(|quic_error| format!("{ech_error}；QUIC 同样失败：{quic_error}")),
    }
}
