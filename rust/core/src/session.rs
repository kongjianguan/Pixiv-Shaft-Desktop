//! 进程内的登录态。
//!
//! 持久化（重启后仍保留登录态）由后续阶段处理，这里只负责本次运行期间的会话。

use std::sync::OnceLock;

use tokio::sync::RwLock;

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

pub async fn set(session: Session) {
    *store().write().await = Some(session);
}

pub async fn get() -> Option<Session> {
    store().read().await.clone()
}

pub async fn clear() {
    *store().write().await = None;
}
