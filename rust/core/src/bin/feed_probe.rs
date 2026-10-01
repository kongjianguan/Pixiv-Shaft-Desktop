//! 验证首页四个作品流的真实接口。
//!
//! 首页作品流是最先发出的带凭据请求，访问令牌过期时它会连带触发刷新，
//! 因此这条探针覆盖「刷新令牌 + 加载首页」这条完整链路。

#[tokio::main]
async fn main() {
    let restored = pixiv_core::session::restore()
        .await
        .expect("读取当前登录态");
    assert!(restored, "钥匙串中没有当前客户端的登录态");

    let illusts = pixiv_core::api::illust::fetch_home_page("illust".into())
        .await
        .expect("读取推荐插画");
    assert!(!illusts.illusts.is_empty(), "推荐插画为空");

    let manga = pixiv_core::api::illust::fetch_home_page("manga".into())
        .await
        .expect("读取推荐漫画");
    assert!(!manga.illusts.is_empty(), "推荐漫画为空");

    let latest = pixiv_core::api::illust::fetch_latest_page()
        .await
        .expect("读取最新插画");
    assert!(!latest.illusts.is_empty(), "最新插画为空");

    // 推荐小说会按 R18 设置过滤，服务端有内容而可见列表为空属于正常情况，
    // 因此判据取服务端返回的原始条数。
    let novel_text = pixiv_core::api_client::get_authed(
        "/v1/novel/recommended?include_privacy_policy=true&filter=for_ios",
    )
    .await
    .expect("读取推荐小说原始响应");
    let parsed: serde_json::Value = serde_json::from_str(&novel_text).expect("解析推荐小说响应");
    let raw_novels = parsed["novels"]
        .as_array()
        .expect("推荐小说响应缺少小说列表");
    assert!(!raw_novels.is_empty(), "推荐小说列表为空");

    let novels = pixiv_core::api::novel::fetch_recommended_novels()
        .await
        .expect("读取推荐小说");

    let report = format!(
        "推荐插画 {} 条\n推荐漫画 {} 条\n最新插画 {} 条\n推荐小说服务端 {} 条，可见 {} 条\n",
        illusts.illusts.len(),
        manga.illusts.len(),
        latest.illusts.len(),
        raw_novels.len(),
        novels.len(),
    );
    print!("{report}");
    std::fs::write("rust/core/target/feed-probe.txt", report).expect("写入首页验证记录");
}
