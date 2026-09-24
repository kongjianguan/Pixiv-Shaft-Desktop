//! 暴露给界面的授权接口。

use crate::auth;
use crate::session;

/// 一次授权会话：浏览器要打开的授权地址，以及换取 token 时要用到的 verifier。
pub struct AuthSession {
    pub auth_url: String,
    pub code_verifier: String,
}

pub struct LoginResult {
    pub user_id: i64,
    pub user_name: String,
}

/// 生成授权地址。界面层负责用系统浏览器打开它。
pub fn start_login() -> AuthSession {
    let pkce = auth::generate_pkce();
    AuthSession {
        auth_url: auth::build_auth_url(&pkce.challenge),
        code_verifier: pkce.verifier,
    }
}

/// 用用户粘贴的回调地址（或裸授权码）换取 token 并建立会话。
pub async fn complete_login(pasted: String, code_verifier: String) -> Result<LoginResult, String> {
    let code = auth::extract_code(&pasted)
        .ok_or_else(|| "无法从粘贴的内容里取出授权码，请粘贴完整的回调地址".to_string())?;

    let response = auth::exchange_code(&code, &code_verifier).await?;
    let access_token = response
        .access_token
        .clone()
        .ok_or_else(|| "token 交换未返回 access_token".to_string())?;

    let user = response.user.unwrap_or(auth::TokenUser {
        id: 0,
        name: None,
        account: None,
    });
    let user_name = user.name.clone().or(user.account.clone()).unwrap_or_default();

    session::set(session::Session {
        access_token,
        refresh_token: response.refresh_token.clone().unwrap_or_default(),
        user_id: user.id,
        user_name: user_name.clone(),
    })
    .await;

    Ok(LoginResult {
        user_id: user.id,
        user_name,
    })
}

/// 当前是否已登录。
pub async fn is_logged_in() -> bool {
    session::get()
        .await
        .map(|s| !s.access_token.is_empty())
        .unwrap_or(false)
}
