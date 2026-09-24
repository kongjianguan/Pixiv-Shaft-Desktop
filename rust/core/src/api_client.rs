//! 面向 app-api.pixiv.net 的接口客户端。
//!
//! 请求头照搬现有版本对 iOS 官方客户端的抓包结果，其中 `x-client-time` 与
//! `x-client-hash` 是 Pixiv 用来校验请求来源的一对值：hash 是
//! `md5(时间戳 + 固定密钥)`，时间戳格式必须是 `yyyy-MM-dd'T'HH:mm:ssZZZZZ`
//! 这种不带冒号的形式（`+0800`），与 hash 的计算保持逐字节一致。

use chrono::Local;
use md5::{Digest, Md5};
use reqwest::header::{HeaderMap, HeaderValue, ACCEPT_LANGUAGE, AUTHORIZATION, USER_AGENT};

pub const APP_API_HOST: &str = "https://app-api.pixiv.net";
pub const APP_VERSION: &str = "8.6.10";
pub const APP_OS_VERSION: &str = "26.5";
pub const DEVICE_MODEL: &str = "iPhone16,2";
pub const USER_AGENT_VALUE: &str = "PixivIOSApp/8.6.10 (iOS 26.5; iPhone16,2)";

const HASH_SECRET: &str = "28c1fdd170a5204386cb1313c7077b34f83e4aaf4aa829ce78c231e05b0bae2c";

/// 生成 `x-client-time` 与 `x-client-hash` 这一对值。
pub fn client_nonce() -> (String, String) {
    let time = Local::now().format("%Y-%m-%dT%H:%M:%S%z").to_string();
    let hash = md5_hex(&format!("{time}{HASH_SECRET}"));
    (time, hash)
}

fn md5_hex(input: &str) -> String {
    let digest = Md5::digest(input.as_bytes());
    digest.iter().map(|b| format!("{b:02x}")).collect()
}

/// 组装接口请求头。`access_token` 为空时不带 `authorization`，
/// 让服务端返回 401，由上层决定是否刷新。
pub fn api_headers(access_token: Option<&str>) -> HeaderMap {
    let mut headers = HeaderMap::new();
    let (time, hash) = client_nonce();

    if let Some(token) = access_token {
        if !token.is_empty() {
            let value = format!("Bearer {token}");
            if let Ok(header) = HeaderValue::from_str(&value) {
                headers.insert(AUTHORIZATION, header);
            }
        }
    }

    // 全部用字符串形式的头名，避免把 HeaderName 与 &str 混在同一个数组里。
    let accept_language = ACCEPT_LANGUAGE.as_str();
    for (name, value) in [
        (accept_language, "zh-CN,zh;q=0.9"),
        ("app-accept-language", "zh-CN"),
        ("app-os", "ios"),
        ("app-os-version", APP_OS_VERSION),
        ("app-version", APP_VERSION),
        ("x-client-time", time.as_str()),
        ("x-client-hash", hash.as_str()),
    ] {
        if let Ok(header) = HeaderValue::from_str(value) {
            headers.insert(name, header);
        }
    }
    headers.insert(USER_AGENT, HeaderValue::from_static(USER_AGENT_VALUE));
    headers
}

/// 令牌失效时服务端返回的提示文案。与现有版本 `PixivConstants` 里的两条一致。
const TOKEN_ERROR_1: &str = "Error occurred at the OAuth process";
const TOKEN_ERROR_2: &str = "Invalid refresh token";

fn is_token_error(body: &str) -> bool {
    body.contains(TOKEN_ERROR_1) || body.contains(TOKEN_ERROR_2)
}

/// 带签名头发起一次 GET。令牌失效时刷新一次并重试。
pub async fn get_with_auth(path: &str, access_token: &str) -> Result<String, String> {
    let (status, body) = request("GET", path, Some(access_token), None).await?;

    if !(200..300).contains(&status) && is_token_error(&body) {
        if let Some(refreshed) = crate::session::refresh_access_token(access_token).await {
            let (retry_status, retry_body) = request("GET", path, Some(&refreshed), None).await?;
            if !(200..300).contains(&retry_status) {
                return Err(format!(
                    "刷新令牌后重试仍失败：{retry_status} {retry_body}"
                ));
            }
            return Ok(retry_body);
        }
    }

    if !(200..300).contains(&status) {
        return Err(format!("请求 {path} 返回 {status}：{body}"));
    }
    Ok(body)
}

/// 不带凭据发起一次 GET，返回状态码与响应体。
///
/// 用来验证签名头本身是否被服务端接受：签名正确时缺少凭据会得到带
/// token 报错文案的响应，签名有问题则会得到别的错误，两者可以区分。
pub async fn get_public(path: &str) -> Result<(u16, String), String> {
    request("GET", path, None, None).await
}

/// 用当前会话发起一次 GET。
pub async fn get_authed(path: &str) -> Result<String, String> {
    let session = crate::session::get()
        .await
        .ok_or_else(|| "尚未登录，请先完成授权".to_string())?;
    get_with_auth(path, &session.access_token).await
}

/// 带签名头发起一次表单 POST。令牌失效时刷新一次并重试。
///
/// 收藏、关注这类写操作都是表单 POST，服务端要求
/// `application/x-www-form-urlencoded`。
pub async fn post_with_auth(path: &str, fields: &[(&str, &str)]) -> Result<String, String> {
    let session = crate::session::get()
        .await
        .ok_or_else(|| "尚未登录，请先完成授权".to_string())?;
    let body = form_body(fields);

    let (status, response) = request(
        "POST",
        path,
        Some(&session.access_token),
        Some(body.clone()),
    )
    .await?;

    if !(200..300).contains(&status) && is_token_error(&response) {
        if let Some(refreshed) = crate::session::refresh_access_token(&session.access_token).await {
            let (retry_status, retry_body) =
                request("POST", path, Some(&refreshed), Some(body)).await?;
            if !(200..300).contains(&retry_status) {
                return Err(format!("刷新令牌后重试仍失败：{retry_status} {retry_body}"));
            }
            return Ok(retry_body);
        }
    }

    if !(200..300).contains(&status) {
        return Err(format!("请求 {path} 返回 {status}：{response}"));
    }
    Ok(response)
}

fn form_body(fields: &[(&str, &str)]) -> String {
    fields
        .iter()
        .map(|(key, value)| format!("{}={}", encode_component(key), encode_component(value)))
        .collect::<Vec<_>>()
        .join("&")
}

/// 只转义查询串与表单里必须转义的字符。
pub fn encode_component(value: &str) -> String {
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

/// 接口请求先走 ECH，失败再走 QUIC。
///
/// 两条通路的顺序与现有版本一致：ECH 是加密 SNI 的 TCP 直连，QUIC 走 UDP。
/// 两条都不通时把各自的失败原因一并报出，便于判断是哪一层的问题。
async fn request(
    method: &str,
    path: &str,
    access_token: Option<&str>,
    body: Option<String>,
) -> Result<(u16, String), String> {
    let mut headers = api_headers(access_token);
    if body.is_some() {
        headers.insert(
            reqwest::header::CONTENT_TYPE,
            reqwest::header::HeaderValue::from_static("application/x-www-form-urlencoded"),
        );
    }
    match crate::ech::request(method, path, headers.clone(), body.clone()).await {
        Ok(result) => Ok(result),
        Err(ech_error) => crate::quic::request(method, path, headers, body)
            .await
            .map_err(|quic_error| format!("{ech_error}；QUIC 同样失败：{quic_error}")),
    }
}
