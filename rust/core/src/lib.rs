//! Pixiv 客户端后端核心。
//!
//! 这个库承载原 Kotlin 版本里 `:net`、`:store`、`:models` 与下载逻辑的职责，
//! 通过 Flutter 的构建钩子编译成静态库链进应用。

pub mod api;
pub mod api_client;
pub mod auth;
pub mod db;
pub mod download;
pub mod ech;
pub mod history;
pub mod image;
pub mod keychain;
pub mod quic;
pub mod session;
pub mod settings;
pub mod tls;

mod frb_generated;
