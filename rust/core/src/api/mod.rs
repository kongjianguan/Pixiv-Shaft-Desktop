pub mod auth;
pub mod comment;
pub mod illust;
pub mod image;
pub mod novel;
pub mod store;
pub mod user;

/// 桥接层初始化。框架要求提供一个带 `init` 标记的函数来装配默认工具。
#[flutter_rust_bridge::frb(init)]
pub fn init_app() {
    flutter_rust_bridge::setup_default_user_utils();
}
