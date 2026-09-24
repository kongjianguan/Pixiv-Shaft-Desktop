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
    let (status, body) = request(path, Some(access_token)).await?;

    if !(200..300).contains(&status) && is_token_error(&body) {
        if let Some(refreshed) = crate::session::refresh_access_token(access_token).await {
            let (retry_status, retry_body) = request(path, Some(&refreshed)).await?;
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
    request(path, None).await
}

/// 接口请求一律走 ECH 传输：国内网络下这些域名走普通 HTTPS 不通。
async fn request(path: &str, access_token: Option<&str>) -> Result<(u16, String), String> {
    crate::ech::get(path, api_headers(access_token)).await
}
