//! OAuth PKCE 授权登录。
//!
//! 流程沿用现有版本的真实做法：打开系统浏览器到 Pixiv 授权页，用户登录后浏览器
//! 跳回 `redirect_uri` 并在地址里带上 `code`，用户把地址或 `code` 粘回应用，
//! 应用用 `code` 加 `code_verifier` 换 token。

use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use rand::RngCore;
use serde::Deserialize;
use sha2::{Digest, Sha256};

pub const CLIENT_ID: &str = "MOBrBDS8blbauoSck0ZfDbtuzpyT";
pub const CLIENT_SECRET: &str = "lsACyCD94FhDUtGTXi3QzcFE2uU1hqtDaKeqrdwj";
pub const REDIRECT_URI: &str = "https://app-api.pixiv.net/web/v1/users/auth/pixiv/callback";
pub const LOGIN_URL: &str = "https://app-api.pixiv.net/web/v1/login";
pub const TOKEN_ENDPOINT: &str = "https://oauth.secure.pixiv.net/auth/token";
pub const CLIENT_PARAM: &str = "pixiv-android";

/// 一次授权会话所需的 PKCE 对。
pub struct PkcePair {
    pub verifier: String,
    pub challenge: String,
}

pub fn generate_pkce() -> PkcePair {
    let mut random = [0u8; 32];
    rand::thread_rng().fill_bytes(&mut random);
    let verifier = URL_SAFE_NO_PAD.encode(random);
    let digest = Sha256::digest(verifier.as_bytes());
    PkcePair {
        verifier,
        challenge: URL_SAFE_NO_PAD.encode(digest),
    }
}

pub fn build_auth_url(challenge: &str) -> String {
    let params = [
        ("client_id", CLIENT_ID),
        ("redirect_uri", REDIRECT_URI),
        ("response_type", "code"),
        ("code_challenge", challenge),
        ("code_challenge_method", "S256"),
        ("client", CLIENT_PARAM),
    ];
    let query = params
        .iter()
        .map(|(k, v)| format!("{}={}", url_encode(k), url_encode(v)))
        .collect::<Vec<_>>()
        .join("&");
    format!("{LOGIN_URL}?{query}")
}

/// 只转义查询串里必须转义的字符，避免把 `:` `/` 等也编码掉。
fn url_encode(value: &str) -> String {
    value
        .chars()
        .flat_map(|c| match c {
            'a'..='z' | 'A'..='Z' | '0'..='9' | '-' | '_' | '.' | '~' => vec![c],
            ' ' => vec!['+'],
            other => {
                let mut buf = [0u8; 4];
                other.encode_utf8(&mut buf);
                buf[..other.len_utf8()]
                    .iter()
                    .flat_map(|b| format!("%{b:02X}").chars().collect::<Vec<_>>())
                    .collect()
            }
        })
        .collect()
}

#[derive(Debug, Deserialize)]
pub struct TokenUser {
    pub id: i64,
    pub name: Option<String>,
    pub account: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct TokenResponse {
    pub access_token: Option<String>,
    pub refresh_token: Option<String>,
    pub expires_in: Option<i64>,
    pub user: Option<TokenUser>,
}

/// 用授权码换 token。`redirect_uri` 必须与生成授权地址时用的完全一致。
pub async fn exchange_code(code: &str, verifier: &str) -> Result<TokenResponse, String> {
    post_token(&[
        ("grant_type", "authorization_code"),
        ("code", code),
        ("code_verifier", verifier),
        ("redirect_uri", REDIRECT_URI),
    ])
    .await
}

pub async fn refresh_token(refresh_token: &str) -> Result<TokenResponse, String> {
    post_token(&[
        ("grant_type", "refresh_token"),
        ("refresh_token", refresh_token),
    ])
    .await
}

async fn post_token(extra: &[(&str, &str)]) -> Result<TokenResponse, String> {
    let mut body: Vec<(&str, &str)> = vec![
        ("client_id", CLIENT_ID),
        ("client_secret", CLIENT_SECRET),
        ("include_policy", "true"),
    ];
    body.extend_from_slice(extra);

    let response = reqwest::Client::new()
        .post(TOKEN_ENDPOINT)
        .form(&body)
        .send()
        .await
        .map_err(|e| format!("请求 token 端点失败：{e}"))?;

    let status = response.status();
    let text = response
        .text()
        .await
        .map_err(|e| format!("读取 token 响应失败：{e}"))?;
    if !status.is_success() {
        return Err(format!("token 交换失败：{status} {text}"));
    }
    serde_json::from_str(&text).map_err(|e| format!("解析 token 响应失败：{e}"))
}

/// 从用户粘贴的内容里取出授权码。
pub fn extract_code(input: &str) -> Option<String> {
    let trimmed = input.trim();
    let lowered = trimmed.to_ascii_lowercase();
    if lowered.starts_with("http") {
        let idx = lowered.find("code=")? + "code=".len();
        let rest = &trimmed[idx..];
        let end = rest.find('&').unwrap_or(rest.len());
        let value = rest[..end].trim();
        if value.is_empty() {
            None
        } else {
            Some(value.to_string())
        }
    } else if trimmed.is_empty() {
        None
    } else {
        Some(trimmed.to_string())
    }
}
