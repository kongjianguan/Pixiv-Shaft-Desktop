//! 验证发现页使用的真实接口与响应结构。

#[tokio::main]
async fn main() {
    let restored = pixiv_core::session::restore()
        .await
        .expect("读取当前登录态");
    assert!(restored, "钥匙串中没有当前客户端的登录态");

    let tags = pixiv_core::api::discovery::fetch_trending_tags()
        .await
        .expect("读取热门标签");
    assert!(!tags.is_empty(), "热门标签为空");
    let tag_image = pixiv_core::image::fetch_image(&tags[0].thumbnail_url)
        .await
        .expect("读取热门标签图片");
    assert!(!tag_image.is_empty(), "热门标签图片为空");

    let illusts = pixiv_core::api::illust::fetch_ranking_page("day".into(), None)
        .await
        .expect("读取插画排行");
    assert!(!illusts.illusts.is_empty(), "插画排行为空");

    let novels = pixiv_core::api::novel::fetch_novel_ranking_page("day".into(), None)
        .await
        .expect("读取小说排行");
    assert!(!novels.novels.is_empty(), "小说排行为空");

    let articles = pixiv_core::api::discovery::fetch_spotlight_page("illust".into())
        .await
        .expect("读取 Pixivision 文章");
    assert!(!articles.articles.is_empty(), "Pixivision 文章为空");
    let article_image = pixiv_core::image::fetch_image(&articles.articles[0].thumbnail_url)
        .await
        .expect("读取 Pixivision 封面");
    assert!(!article_image.is_empty(), "Pixivision 封面为空");

    let mut visibility_report = String::new();
    if !pixiv_core::settings::get_bool("isShowR18", false).expect("读取 R18 设置") {
        let raw =
            pixiv_core::api_client::get_authed("/v1/illust/ranking?mode=day_r18&filter=for_ios")
                .await
                .expect("读取 R18 排行原始响应");
        let parsed: serde_json::Value = serde_json::from_str(&raw).expect("解析 R18 排行响应");
        let raw_illusts = parsed["illusts"].as_array().expect("R18 排行缺少作品列表");
        let expected_visible = raw_illusts
            .iter()
            .filter(|illust| illust["x_restrict"].as_i64().unwrap_or(0) <= 0)
            .count();
        let filtered = pixiv_core::api::illust::fetch_ranking_page("day_r18".into(), None)
            .await
            .expect("读取 R18 排行可见内容");
        assert_eq!(
            filtered.illusts.len(),
            expected_visible,
            "R18 过滤结果不一致"
        );
        visibility_report = format!(
            "关闭 R18：原始排行 {} 条，可见 {} 条\n",
            raw_illusts.len(),
            filtered.illusts.len()
        );
    }

    let report = format!(
        "热门标签 {} 条，首张图片 {} 字节\n插画排行 {} 条\n小说排行 {} 条\nPixivision {} 条，首张封面 {} 字节\n{}",
        tags.len(),
        tag_image.len(),
        illusts.illusts.len(),
        novels.novels.len(),
        articles.articles.len(),
        article_image.len(),
        visibility_report,
    );

    print!("{report}");
    std::fs::write("rust/core/target/discovery-probe.txt", report).expect("写入发现页验证记录");
}
