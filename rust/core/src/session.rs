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

    Some(access_token)
}
