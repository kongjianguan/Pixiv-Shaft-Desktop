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
    println!("===== ECH 传输、签名头与接口路径 =====");
    // 这一步同时验证三件事：ECH 握手是否成功（失败会走到请求失败分支）、
    // 签名头是否被接受、以及接口路径是否存在。
    // 路径写错时 Pixiv 返回 404，与「缺少凭据」的文案可以区分。
    for (label, path) in [
        (
            "推荐",
            "/v1/illust/recommended?include_ranking_illusts=false&include_privacy_policy=true&filter=for_ios",
        ),
        (
            "搜索",
            "/v1/search/illust?word=%E5%88%9D%E9%9F%B3&sort=date_desc&search_target=partial_match_for_tags&merge_plain_keyword_results=true&include_translated_tag_results=true&search_ai_type=0&filter=for_ios",
        ),
        (
            "收藏",
            "/v1/user/bookmarks/illust?filter=for_ios&restrict=public",
        ),
    ] {
        match pixiv_core::api_client::get_public(path).await {
            Ok((status, body)) => {
                let snippet: String = body.chars().take(120).collect();
                let missing_credentials = body.contains("OAuth process") || status == 401;
                let not_found = status == 404;
                println!("{label}: 状态码 {status}");
                if not_found {
                    eprintln!("  {label} 返回 404，接口路径可能写错");
                    failed = true;
                } else if missing_credentials {
                    println!("  路径有效、签名被接受，服务端只是在提示缺少凭据");
                } else {
                    eprintln!("  既非 404 也没有出现缺少凭据的提示：{snippet}");
                    failed = true;
                }
            }
            Err(error) => {
                eprintln!("{label}: 请求失败 {error}");
                failed = true;
            }
        }
    }

    println!();
    println!("===== QUIC（HTTP/3）传输 =====");
    // QUIC 是 ECH 之外的另一条通路，必须单独验证：只验证 ECH 的话，
    // QUIC 即使完全不工作也看不出来。用同一个接口，判据与上面一致。
    let path = "/v1/illust/recommended?include_ranking_illusts=false&filter=for_ios";
    match pixiv_core::quic::get(path, pixiv_core::api_client::api_headers(None)).await {
        Ok((status, body)) => {
            let snippet: String = body.chars().take(120).collect();
            println!("状态码 {status}");
            if body.contains("OAuth process") || status == 401 {
                println!("QUIC 通路可用，服务端同样只是在提示缺少凭据");
            } else {
                eprintln!("响应里没有出现缺少凭据的提示：{snippet}");
                failed = true;
            }
        }
        Err(error) => {
            eprintln!("QUIC 请求失败：{error}");
            failed = true;
        }
    }

    if failed {
        std::process::exit(1);
    }
}
