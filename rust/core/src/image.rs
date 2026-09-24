//! 图片 CDN 的取回路径。
//!
//! pximg.net 需要三件事同时成立才能在国内网络下取到图片：
//! 1. 不发 SNI 的 TLS（见 [`crate::tls`]）
//! 2. 绕过本地 DNS 污染，直接连已知地址
//! 3. 带 Referer 与伪装 User-Agent，否则 CDN 返回 403
//!
//! 地址不从公共解析服务取。实测 AliDNS 把 `i.pximg.net` 解析到 `118.184.26.113`，
//! 该地址取不到图片（带 SNI 与不带 SNI 都失败），本地 DNS 的结果同样不可信，
//! 因此沿用已知可用的 CDN 地址，与现有版本的 HttpDns 兜底列表一致。

use std::net::SocketAddr;
use std::time::Duration;

use reqwest::header::{HeaderMap, HeaderValue, REFERER, USER_AGENT};

/// 图片 CDN 域名。
pub const IMAGE_HOST: &str = "i.pximg.net";

/// 已知可用的 CDN 地址。
const IMAGE_HOST_IPS: &[&str] = &[
    "210.140.139.134",
    "210.140.139.133",
    "210.140.139.131",
];

const PIXIV_REFERER: &str = "https://app-api.pixiv.net/";
const PIXIV_USER_AGENT: &str = "PixivIOSApp/8.6.10 (iOS 26.5; iPhone16,2)";

fn image_host_addrs() -> Vec<SocketAddr> {
    IMAGE_HOST_IPS
        .iter()
        .map(|ip| {
            format!("{ip}:443")
                .parse()
                .unwrap_or_else(|_| panic!("配置的 CDN 地址不是合法 IP: {ip}"))
        })
        .collect()
}

/// 构造指向图片 CDN 的客户端。`enable_sni` 仅用于对照实验，
/// 正常取图必须传 false。
pub fn image_client(enable_sni: bool) -> reqwest::Client {
    let mut headers = HeaderMap::new();
    headers.insert(REFERER, HeaderValue::from_static(PIXIV_REFERER));
    headers.insert(USER_AGENT, HeaderValue::from_static(PIXIV_USER_AGENT));

    reqwest::Client::builder()
        .use_preconfigured_tls((*crate::tls::client_config(enable_sni)).clone())
        .default_headers(headers)
        .http1_only()
        .resolve_to_addrs(IMAGE_HOST, &image_host_addrs())
        .connect_timeout(Duration::from_secs(15))
        .timeout(Duration::from_secs(30))
        .build()
        .expect("构建图片客户端")
}

/// 取回一张图片的原始字节。
pub async fn fetch_image(url: &str) -> Result<Vec<u8>, String> {
    let response = image_client(false)
        .get(url)
        .send()
        .await
        .map_err(|e| format!("请求 {url} 失败: {e}"))?;

    let status = response.status();
    if !status.is_success() {
        return Err(format!("请求 {url} 返回状态码 {status}"));
    }

    let bytes = response
        .bytes()
        .await
        .map_err(|e| format!("读取 {url} 的响应体失败: {e}"))?;
    Ok(bytes.to_vec())
}
