//! 进程内的登录态。
//!
//! 持久化（重启后仍保留登录态）由后续阶段处理，这里只负责本次运行期间的会话。

use std::sync::OnceLock;

use tokio::sync::{Mutex, RwLock};

#[derive(Clone, Debug)]
pub struct Session {
    pub access_token: String,
    pub refresh_token: String,
    pub user_id: i64,
    pub user_name: String,
}

fn store() -> &'static RwLock<Option<Session>> {
    static SESSION: OnceLock<RwLock<Option<Session>>> = OnceLock::new();
    SESSION.get_or_init(|| RwLock::new(None))
}

fn refresh_lock() -> &'static Mutex<()> {
    static LOCK: OnceLock<Mutex<()>> = OnceLock::new();
    LOCK.get_or_init(|| Mutex::new(()))
}

pub async fn set(session: Session) {
    *store().write().await = Some(session);
}

pub async fn get() -> Option<Session> {
    store().read().await.clone()
}

pub async fn clear() {
    *store().write().await = None;
}

/// 把当前登录态写入钥匙串。
pub async fn persist() -> Result<(), String> {
    let Some(current) = get().await else {
        return Ok(());
    };
    crate::keychain::put(crate::keychain::KEY_ACCESS, &current.access_token)?;
    crate::keychain::put(crate::keychain::KEY_REFRESH, &current.refresh_token)
}

/// 从钥匙串恢复登录态。进程启动后第一次用到登录态时调用。
///
/// 用户名与 id 不落盘：需要时从接口取，避免把非 ASCII 的值放进钥匙串。
pub async fn restore() -> Result<bool, String> {
    let access = crate::keychain::get(crate::keychain::KEY_ACCESS)?;
    let refresh = crate::keychain::get(crate::keychain::KEY_REFRESH)?;

    match (access, refresh) {
        (Some(access_token), Some(refresh_token)) if !access_token.is_empty() => {
            set(Session {
                access_token,
                refresh_token,
                user_id: 0,
                user_name: String::new(),
            })
            .await;
            Ok(true)
        }
        _ => Ok(false),
    }
}

/// 退出登录：清掉内存里的会话与钥匙串里的凭据。
pub async fn logout() -> Result<(), String> {
    clear().await;
    crate::keychain::remove(crate::keychain::KEY_ACCESS)?;
    crate::keychain::remove(crate::keychain::KEY_REFRESH)
}

/// 刷新访问令牌。`stale_token` 是这次请求实际用过的令牌。
///
/// 多个请求同时遇到令牌失效时，只有第一个真正去刷新，其余在拿到锁之后发现
/// 令牌已经换过就直接复用，避免重复刷新互相覆盖。
pub async fn refresh_access_token(stale_token: &str) -> Option<String> {
    let _guard = refresh_lock().lock().await;

    let current = get().await?;
    if current.access_token != stale_token {
        return Some(current.access_token);
    }
    if current.refresh_token.is_empty() {
        return None;
    }

    let response = crate::auth::refresh_token(&current.refresh_token)
        .await
        .ok()?;
    let access_token = response.access_token.clone()?;
    let refresh_token = response
        .refresh_token
        .clone()
        .unwrap_or_else(|| current.refresh_token.clone());

    set(Session {
        access_token: access_token.clone(),
        refresh_token,
        user_id: current.user_id,
        user_name: current.user_name,
    })
    .await;

    // 刷新后的令牌要写回钥匙串，否则下次启动仍用旧的访问令牌。
    if let Err(error) = persist().await {
        eprintln!("刷新后的令牌写回钥匙串失败：{error}");
    }

    Some(access_token)
}
