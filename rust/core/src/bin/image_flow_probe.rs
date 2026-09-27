//! 用当前钥匙串里的登录态验证推荐列表到图片字节的真实链路。

#[tokio::main]
async fn main() {
    let restored = pixiv_core::session::restore()
        .await
        .expect("读取当前登录态");
    assert!(restored, "钥匙串中没有当前客户端的登录态");

    let illusts = pixiv_core::api::illust::fetch_home_page("illust".to_string())
        .await
        .expect("读取推荐作品")
        .illusts;
    assert!(!illusts.is_empty(), "推荐作品列表为空");

    let mut requests = tokio::task::JoinSet::new();
    for illust in illusts.into_iter().take(20) {
        requests.spawn(async move {
            let bytes = pixiv_core::image::fetch_image(&illust.image_url).await?;
            Ok::<_, String>((illust.id, bytes.len()))
        });
    }

    let mut verified = Vec::new();
    while let Some(result) = requests.join_next().await {
        let (id, bytes) = result.expect("图片请求执行完成").expect("读取作品图片");
        assert!(bytes > 0, "作品图片内容为空");
        println!("作品 {id} 图片字节数 {bytes}");
        verified.push(format!("{id} {bytes}"));
    }
    verified.sort();
    std::fs::write("target/image-flow-probe.txt", format!("{}\n", verified.join("\n")))
        .expect("写入图片链路验证记录");
}
