//! ECH (Encrypted Client Hello) transport for Pixiv API hosts.
//!
//! Ported from PixEz (Notsfsssf/pixez-flutter) rhttp module:
//! - ECH config is bootstrapped from `cloudflare-ech.com` via AliDNS
//!   (https://223.5.5.5/resolve, reachable from mainland China).
//! - TLS 1.3 ECH hides the SNI from the GFW so plain TCP works again.
//! - Targets are pinned to static Cloudflare anycast IPs.
//!
//! Design notes:
//! - The client is built lazily once per process (OnceLock + Mutex), with
//!   failures NOT cached so a transient AliDNS outage retries next request.
//! - JNI entry points in lib.rs wrap calls in `catch_unwind`: unwinding across
//!   the JNI boundary is UB, so a panic becomes an error result and the Kotlin
//!   side falls back to QUIC.
//! - Successfully-built clients keep their reqwest connection pool alive.

use base64::Engine;
use rustls::client::{EchConfig, EchMode};
use rustls::crypto::aws_lc_rs::hpke::ALL_SUPPORTED_SUITES;
use rustls::pki_types::EchConfigListBytes;
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;

/// AliDNS DoH endpoint (domestic, no GFW issue).
const ALIDNS_RESOLVE_ENDPOINT: &str = "https://223.5.5.5/resolve";
/// Cloudflare's ECH bootstrap host: its HTTPS record carries the ECHConfigList.
const ECH_BOOTSTRAP_HOST: &str = "cloudflare-ech.com";

/// Static Cloudflare anycast IPs (same set as PixEz).
const ECH_IPS: [&str; 2] = ["104.18.10.118", "104.18.11.118"];

/// Hosts routed through the ECH client.
const PIXIV_HOSTS: [&str; 4] = [
    "app-api.pixiv.net",
    "oauth.secure.pixiv.net",
    "www.pixiv.net",
    "comic.pixiv.net",
];

/// Response of one ECH request.
pub struct EchResponse {
    pub status: u16,
    pub headers: Vec<(String, String)>,
    pub body: Vec<u8>,
}

pub(crate) struct EchClient {
    inner: reqwest::Client,
}

static RUNTIME: OnceLock<tokio::runtime::Runtime> = OnceLock::new();
static CLIENT: OnceLock<Mutex<Option<Arc<EchClient>>>> = OnceLock::new();

fn runtime() -> &'static tokio::runtime::Runtime {
    RUNTIME.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .build()
            .expect("tokio runtime build must not fail")
    })
}

fn plain_http() -> reqwest::Client {
    reqwest::Client::builder()
        .connect_timeout(Duration::from_secs(8))
        .tls_certs_only(
            webpki_root_certs::TLS_SERVER_ROOT_CERTS
                .iter()
                .map(|c| reqwest::Certificate::from_der(c.as_ref()).expect("der cert"))
                .collect::<Vec<_>>(),
        )
        .build()
        .expect("plain client build must not fail")
}

/// Query AliDNS for the ECHConfigList of [ECH_BOOTSTRAP_HOST].
async fn lookup_ech_config(http: &reqwest::Client) -> Result<Vec<u8>, String> {
    let resp = http
        .get(ALIDNS_RESOLVE_ENDPOINT)
        .query(&[("name", ECH_BOOTSTRAP_HOST), ("type", "HTTPS")])
        .header("accept", "application/json")
        .send()
        .await
        .map_err(|e| format!("AliDNS request failed: {e}"))?;
    let body: serde_json::Value = resp
        .json()
        .await
        .map_err(|e| format!("AliDNS json failed: {e}"))?;
    let answers = body["Answer"]
        .as_array()
        .ok_or_else(|| "no Answer in AliDNS response".to_string())?;
    for answer in answers {
        let data = answer["data"].as_str().unwrap_or("");
        if let Some(ech) = extract_https_svc_param(data, "ech") {
            return base64::engine::general_purpose::STANDARD
                .decode(ech.trim())
                .map_err(|e| format!("ECH config base64 decode: {e}"));
        }
    }
    Err("no ech= param found in HTTPS records".into())
}

fn extract_https_svc_param<'a>(data: &'a str, key: &str) -> Option<&'a str> {
    let prefix = format!("{key}=\"");
    let start = data.find(&prefix)? + prefix.len();
    let tail = &data[start..];
    let end = tail.find('"')?;
    Some(&tail[..end])
}

fn build_client(ech_config: &[u8]) -> Result<reqwest::Client, String> {
    let provider = Arc::new(rustls::crypto::aws_lc_rs::default_provider());
    let ech_config = EchConfig::new(
        EchConfigListBytes::from(ech_config.to_vec()),
        ALL_SUPPORTED_SUITES,
    )
    .map_err(|e| format!("Invalid ECH config: {e:?}"))?;
    let mut roots = rustls::RootCertStore::empty();
    roots.add_parsable_certificates(webpki_root_certs::TLS_SERVER_ROOT_CERTS.to_vec());
    let tls = rustls::ClientConfig::builder_with_provider(provider)
        .with_ech(EchMode::from(ech_config))
        .map_err(|e| format!("Invalid ECH setup: {e:?}"))?
        .with_root_certificates(roots)
        .with_no_client_auth();

    let mut builder = reqwest::Client::builder()
        .connect_timeout(Duration::from_secs(10))
        .timeout(Duration::from_secs(20))
        .tls_backend_preconfigured(tls);
    for host in PIXIV_HOSTS {
        for ip in ECH_IPS {
            builder = builder.resolve(host, std::net::SocketAddr::new(ip.parse().unwrap(), 443));
        }
    }
    builder.build().map_err(|e| format!("client build failed: {e}"))
}

/// Lazily build the shared ECH client. Failures are NOT cached, so a transient
/// AliDNS/network outage is retried on the next call.
pub fn ensure_client() -> Result<Arc<EchClient>, String> {
    let cell = CLIENT.get_or_init(|| Mutex::new(None));
    let mut guard = cell.lock().map_err(|_| "client mutex poisoned".to_string())?;
    if guard.is_none() {
        let http = plain_http();
        let config = runtime()
            .block_on(lookup_ech_config(&http))
            .map_err(|e| format!("ECH config lookup failed: {e}"))?;
        let inner = build_client(&config)?;
        *guard = Some(Arc::new(EchClient { inner }));
    }
    Ok(guard.as_ref().expect("just set").clone())
}

/// Send one request over the ECH client. `headers` is a flat list of
/// "name\u{1}value" pairs produced by the Kotlin side.
pub fn request(
    method: &str,
    url: &str,
    headers: &[(String, String)],
    body: Option<Vec<u8>>,
) -> Result<EchResponse, String> {
    let client = ensure_client()?;

    let resp = runtime()
        .block_on(async {
            let method = reqwest::Method::from_bytes(method.as_bytes())
                .map_err(|e| format!("bad method {method}: {e}"))?;
            let mut req = client.inner.request(method, url);
            // Skip hop-by-hop / auto-managed headers; reqwest sets its own.
            for (name, value) in headers {
                let lower = name.to_ascii_lowercase();
                if matches!(
                    lower.as_str(),
                    "host" | "connection" | "content-length" | "transfer-encoding" | "accept-encoding"
                ) {
                    continue;
                }
                req = req.header(name, value);
            }
            if let Some(b) = body {
                req = req.body(b);
            }
            req.send()
                .await
                .map_err(|e| format!("ECH request failed: {e}"))
        })?;

    let status = resp.status().as_u16();
    let mut out_headers: Vec<(String, String)> = Vec::new();
    for (name, value) in resp.headers() {
        if let Ok(v) = value.to_str() {
            out_headers.push((name.to_string(), v.to_string()));
        }
    }
    let bytes = runtime()
        .block_on(resp.bytes())
        .map_err(|e| format!("ECH response body read failed: {e}"))?;
    Ok(EchResponse {
        status,
        headers: out_headers,
        body: bytes.to_vec(),
    })
}
