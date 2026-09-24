//! 端到端验证授权与接口签名这两处容易出错的地方。
//!
//! 1. PKCE：verifier 与 challenge 是否满足 S256 定义
//! 2. 请求签名头：不带凭据调用接口，服务端应当返回 401。
//!    若签名头有问题（`x-client-time` 与 `x-client-hash` 不匹配或时间格式不对），
//!    会得到别的错误，因此 401 是「签名被接受、只是没有凭据」的判据。
//!
//! 运行方式：
//! ```text
//! cargo run --bin api_probe
//! ```

use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use sha2::{Digest, Sha256};

#[tokio::main]
async fn main() {
    let mut failed = false;

    println!("===== PKCE =====");
    let pkce = pixiv_core::auth::generate_pkce();
    println!("verifier 长度: {}（32 字节 base64url 无填充应为 43）", pkce.verifier.len());
    let expected = URL_SAFE_NO_PAD.encode(Sha256::digest(pkce.verifier.as_bytes()));
    println!("challenge 是否符合 S256 定义: {}", expected == pkce.challenge);
    if pkce.verifier.len() != 43 || expected != pkce.challenge {
        eprintln!("PKCE 生成不正确");
        failed = true;
    }

    println!();
    println!("===== 授权地址 =====");
    let url = pixiv_core::auth::build_auth_url(&pkce.challenge);
    println!("{url}");
    for required in [
        "client_id=",
        "response_type=code",
        "code_challenge=",
        "code_challenge_method=S256",
    ] {
        if !url.contains(required) {
            eprintln!("授权地址缺少 {required}");
            failed = true;
        }
    }

    println!();
    println!("===== 接口签名头 =====");
    match pixiv_core::api_client::get_public(
        "/v1/illust/recommended?include_ranking_illusts=false&filter=for_ios",
    )
    .await
    {
        Ok((status, body)) => {
            let snippet: String = body.chars().take(200).collect();
            println!("状态码: {status}");
            println!("响应片段: {snippet}");
            // 签名头被接受时，缺凭据表现为 Pixiv 的 OAuth 报错（400 或 401）。
            // 签名本身有问题会得到别的错误，因此用这条已知文案作为判据。
            let missing_credentials = body.contains("OAuth process") || status == 401;
            if missing_credentials {
                println!("签名头被接受，服务端只是在提示缺少凭据");
            } else {
                eprintln!("签名头可能有问题：既非 401，也没有出现缺少凭据的提示");
                failed = true;
            }
        }
        Err(error) => {
            eprintln!("请求失败: {error}");
            failed = true;
        }
    }

    if failed {
        std::process::exit(1);
    }
}
