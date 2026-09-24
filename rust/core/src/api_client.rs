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

/// 带签名头发起一次 GET，返回原始响应文本。
pub async fn get_with_auth(path: &str, access_token: &str) -> Result<String, String> {
    let (_, body) = request(path, Some(access_token)).await?;
    Ok(body)
}

/// 不带凭据发起一次 GET，返回状态码与响应体。
///
/// 用来验证签名头本身是否被服务端接受：签名正确时缺少凭据应得到 401，
/// 签名有问题则会得到别的错误，两者可以区分。
pub async fn get_public(path: &str) -> Result<(u16, String), String> {
    request(path, None).await
}

async fn request(path: &str, access_token: Option<&str>) -> Result<(u16, String), String> {
    let url = format!("{APP_API_HOST}{path}");
    let response = reqwest::Client::new()
        .get(&url)
        .headers(api_headers(access_token))
        .send()
        .await
        .map_err(|e| format!("请求 {url} 失败：{e}"))?;

    let status = response.status().as_u16();
    let body = response
        .text()
        .await
        .map_err(|e| format!("读取 {url} 响应失败：{e}"))?;
    Ok((status, body))
}
