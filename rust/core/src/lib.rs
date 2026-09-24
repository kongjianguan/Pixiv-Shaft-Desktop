//! Pixiv 客户端后端核心。
//!
//! 这个库承载原 Kotlin 版本里 `:net`、`:store`、`:models` 与下载逻辑的职责，
//! 通过 Flutter 的构建钩子编译成静态库链进应用。

pub mod image;
pub mod tls;

/// 取回一张图片的原始字节。界面层拿到字节后自行解码显示。
pub async fn fetch_image(url: String) -> Result<Vec<u8>, String> {
    image::fetch_image(&url).await
}
