//! 暴露给界面层的图片接口。

/// 取回一张图片的原始字节。界面层拿到字节后自行解码显示。
pub async fn fetch_image(url: String) -> Result<Vec<u8>, String> {
    crate::image::fetch_image(&url).await
}
