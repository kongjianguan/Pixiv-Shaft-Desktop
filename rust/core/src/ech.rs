//! ECH（加密客户端问候）传输。
//!
//! 国内网络下 `app-api.pixiv.net` 等域名走普通 HTTPS 不通。ECH 把 SNI 加密后
//! 藏在 ClientHello 里，明文部分只剩一个无关域名，因此可以直连。
//!
//! 做法沿用现有版本 `rust/ech` 的实现（移植自 PixEz），去掉了 JNI 那层：
//! - ECH 配置从 `cloudflare-ech.com` 的 HTTPS 记录里取，解析走 AliDNS
//! - 目标地址固定到 Cloudflare 的任播 IP
//! - 客户端按需构建一次；构建失败不缓存，下次请求会重试

use std::net::SocketAddr;
use std::sync::{Arc, OnceLock};
use std::time::Duration;

use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use reqwest::header::HeaderMap;
use rustls::client::{EchConfig, EchMode};
use rustls::crypto::aws_lc_rs::hpke::ALL_SUPPORTED_SUITES;
use rustls::pki_types::EchConfigListBytes;
use tokio::sync::Mutex;

/// AliDNS 的解析接口，国内可达。
const ALIDNS_RESOLVE_ENDPOINT: &str = "https://223.5.5.5/resolve";
/// 它的 HTTPS 记录里带有 ECHConfigList。
const ECH_BOOTSTRAP_HOST: &str = "cloudflare-ech.com";
/// Cloudflare 任播地址，与现有版本一致。
const ECH_IPS: [&str; 2] = ["104.18.10.118", "104.18.11.118"];

/// 走 ECH 的域名。
const PIXIV_HOSTS: [&str; 4] = [
    "app-api.pixiv.net",
    "oauth.secure.pixiv.net",
    "www.pixiv.net",
    "comic.pixiv.net",
];

static CLIENT: OnceLock<Mutex<Option<Arc<reqwest::Client>>>> = OnceLock::new();

/// 取回共享的 ECH 客户端。构建失败时不缓存，便于下次重试。
pub async fn client() -> Result<Arc<reqwest::Client>, String> {
    let cell = CLIENT.get_or_init(|| Mutex::new(None));
    let mut guard = cell.lock().await;
    if let Some(existing) = guard.as_ref() {
        return Ok(existing.clone());
    }
    let built = Arc::new(build_client(&lookup_ech_config().await?).await?);
    *guard = Some(built.clone());
    Ok(built)
}

/// 丢掉缓存的客户端，让下次取用重新拉一份 ECH 配置。
///
/// ECH 配置会轮换，缓存的配置在服务端滚动之后就会被拒；此时重新取一份通常
/// 就能继续用，不需要整个进程重启。
async fn invalidate_client() {
    let cell = CLIENT.get_or_init(|| Mutex::new(None));
    *cell.lock().await = None;
}

async fn lookup_ech_config() -> Result<Vec<u8>, String> {
    let response = reqwest::Client::builder()
        .connect_timeout(Duration::from_secs(8))
        .build()
        .map_err(|e| format!("构建解析用客户端失败：{e}"))?
        .get(ALIDNS_RESOLVE_ENDPOINT)
        .query(&[("name", ECH_BOOTSTRAP_HOST), ("type", "HTTPS")])
        .header("accept", "application/json")
        .send()
        .await
        .map_err(|e| format!("AliDNS 请求失败：{e}"))?;

    let body: serde_json::Value = response
        .json()
        .await
        .map_err(|e| format!("解析 AliDNS 响应失败：{e}"))?;
    let answers = body["Answer"]
        .as_array()
        .ok_or_else(|| "AliDNS 响应里没有 Answer".to_string())?;

    for answer in answers {
        let data = answer["data"].as_str().unwrap_or("");
        if let Some(ech) = extract_https_svc_param(data, "ech") {
            return STANDARD
                .decode(ech.trim())
                .map_err(|e| format!("ECH 配置 base64 解码失败：{e}"));
        }
    }
    Err("HTTPS 记录里没有 ech= 参数".to_string())
}

fn extract_https_svc_param<'a>(data: &'a str, key: &str) -> Option<&'a str> {
    let prefix = format!("{key}=\"");
    let start = data.find(&prefix)? + prefix.len();
    let tail = &data[start..];
    let end = tail.find('"')?;
    Some(&tail[..end])
}

async fn build_client(ech_config: &[u8]) -> Result<reqwest::Client, String> {
    let ech_config = EchConfig::new(
        EchConfigListBytes::from(ech_config.to_vec()),
        ALL_SUPPORTED_SUITES,
    )
    .map_err(|e| format!("ECH 配置无效：{e:?}"))?;

    let mut roots = rustls::RootCertStore::empty();
    roots.add_parsable_certificates(webpki_root_certs::TLS_SERVER_ROOT_CERTS.to_vec());

    let tls = rustls::ClientConfig::builder_with_provider(Arc::new(
        rustls::crypto::aws_lc_rs::default_provider(),
    ))
    .with_ech(EchMode::from(ech_config))
    .map_err(|e| format!("ECH 装配失败：{e:?}"))?
    .with_root_certificates(roots)
    .with_no_client_auth();

    let mut builder = reqwest::Client::builder()
        .connect_timeout(Duration::from_secs(10))
        .timeout(Duration::from_secs(20))
        .tls_backend_preconfigured(tls);
    for host in PIXIV_HOSTS {
        for ip in ECH_IPS {
            let address: SocketAddr = format!("{ip}:443")
                .parse()
                .map_err(|e| format!("任播地址 {ip} 不合法：{e}"))?;
            builder = builder.resolve(host, address);
        }
    }
    builder
        .build()
        .map_err(|e| format!("构建 ECH 客户端失败：{e}"))
}

/// 发起一次 GET。
pub async fn get(path: &str, headers: HeaderMap) -> Result<(u16, String), String> {
    request("GET", path, headers, None).await
}

/// 发起一次请求。
///
/// ECH 被服务端拒绝属于正常情况：配置会轮换，缓存的配置在服务端滚动之后就失效。
/// 因此先试 ECH；被拒时丢掉缓存、重新取一份配置再试；仍然被拒就交给调用方
/// 换别的通路。
pub async fn request(
    method: &str,
    path: &str,
    headers: HeaderMap,
    body: Option<String>,
) -> Result<(u16, String), String> {
    let url = format!("https://app-api.pixiv.net{path}");

    let first = client().await?;
    match send(&first, method, &url, headers.clone(), body.clone()).await {
        Ok(result) => return Ok(result),
        Err(failure) if !failure.ech_rejected => {
            return Err(format!("ECH 请求 {url} 失败：{}", failure.message));
        }
        Err(_) => {}
    }

    eprintln!("ECH 被服务端拒绝，重新取一份配置再试");
    invalidate_client().await;
    let refreshed = client().await?;
    match send(&refreshed, method, &url, headers, body).await {
        Ok(result) => Ok(result),
        Err(failure) => Err(format!("ECH 请求 {url} 失败：{}", failure.message)),
    }
}

struct Failure {
    message: String,
    ech_rejected: bool,
}

async fn send(
    client: &reqwest::Client,
    method: &str,
    url: &str,
    headers: HeaderMap,
    body: Option<String>,
) -> Result<(u16, String), Failure> {
    let mut builder = client.request(
        reqwest::Method::from_bytes(method.as_bytes()).map_err(|e| Failure {
            ech_rejected: false,
            message: format!("请求方法 {method} 不合法：{e}"),
        })?,
        url,
    );
    builder = builder.headers(headers);
    if let Some(payload) = body {
        builder = builder.body(payload);
    }

    let response = builder.send().await.map_err(|e| Failure {
        ech_rejected: is_ech_rejection(&e),
        message: describe(&e),
    })?;

    let status = response.status().as_u16();
    let text = response.text().await.map_err(|e| Failure {
        ech_rejected: false,
        message: describe(&e),
    })?;
    Ok((status, text))
}

/// 判断失败是否来自「服务端拒绝了 ECH」。
fn is_ech_rejection(error: &reqwest::Error) -> bool {
    let mut source: Option<&(dyn std::error::Error + 'static)> = Some(error);
    while let Some(current) = source {
        if current.to_string().contains("ServerRejectedEncryptedClientHello") {
            return true;
        }
        source = current.source();
    }
    false
}

/// 把错误连同底层原因一起展开。只看最外层信息时无法判断是握手被拒、
/// 配置不匹配还是网络不通，定位问题需要看到具体那一层。
fn describe(error: &reqwest::Error) -> String {
    let mut parts = vec![error.to_string()];
    let mut source: Option<&(dyn std::error::Error + 'static)> = std::error::Error::source(error);
    while let Some(current) = source {
        parts.push(current.to_string());
        source = current.source();
    }
    parts.join(" → ")
}
