//! 使用当前登录状态验证搜索页面的真实接口。

#[tokio::main]
async fn main() {
    assert!(
        pixiv_core::session::restore().await.expect("读取登录状态"),
        "当前没有登录状态"
    );

    let word = "初音ミク";
    let keyword = pixiv_core::api_client::encode_component(word);
    let suggestions = pixiv_core::api::search::fetch_search_suggestions(word.into())
        .await
        .expect("读取搜索建议");
    let options =
        pixiv_core::api::search::fetch_search_options(word.into(), "partial_match_for_tags".into())
            .await
            .expect("读取搜索选项");
    let illusts = pixiv_core::api::illust::fetch_illust_page(&format!(
        "/v1/search/illust?word={keyword}&sort=date_desc&search_target=partial_match_for_tags&merge_plain_keyword_results=true&include_translated_tag_results=true&search_ai_type=0"
    ))
    .await
    .expect("搜索插画");
    assert!(!illusts.illusts.is_empty(), "插画搜索没有返回作品");
    let novels = pixiv_core::api::novel::fetch_novel_page(&format!(
        "/v1/search/novel?word={keyword}&sort=date_desc&search_target=partial_match_for_tags&merge_plain_keyword_results=true&include_translated_tag_results=true&search_ai_type=0"
    ))
    .await
    .expect("搜索小说");
    assert!(!novels.novels.is_empty(), "小说搜索没有返回作品");
    let users = pixiv_core::api::user::search_users(word.into())
        .await
        .expect("搜索用户");

    let report = format!(
        "关键词 {word}\n搜索建议 {} 条\n语言选项 插画 {} 条，小说 {} 条\n制图工具 {} 条，小说类型 {} 条\n插画 {} 条，小说 {} 条，用户 {} 条\n",
        suggestions.len(),
        options.illust_languages.len(),
        options.novel_languages.len(),
        options.tools.len(),
        options.genres.len(),
        illusts.illusts.len(),
        novels.novels.len(),
        users.users.len(),
    );
    print!("{report}");
    std::fs::write("rust/core/target/search-probe.txt", report).expect("写入搜索验证记录");
}
