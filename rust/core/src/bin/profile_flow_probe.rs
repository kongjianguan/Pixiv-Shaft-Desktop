//! 用当前登录态验证个人资料与两类收藏的真实接口。

#[tokio::main]
async fn main() {
    let restored = pixiv_core::session::restore()
        .await
        .expect("读取当前登录态");
    assert!(restored, "钥匙串中没有当前客户端的登录态");

    let user_id = pixiv_core::api::user::self_user_id()
        .await
        .expect("读取当前用户编号");
    let profile = pixiv_core::api::user::fetch_user_detail(user_id)
        .await
        .expect("读取个人资料");
    assert_eq!(profile.id, user_id, "个人资料的用户编号不一致");
    let avatar = pixiv_core::image::fetch_image(&profile.avatar_url)
        .await
        .expect("读取个人头像");
    assert!(!avatar.is_empty(), "个人头像内容为空");

    let illusts = pixiv_core::api::illust::fetch_bookmarked_illusts()
        .await
        .expect("读取插画收藏");
    let novels = pixiv_core::api::novel::fetch_bookmarked_novels()
        .await
        .expect("读取小说收藏");

    let report = format!(
        "用户编号 {user_id}\n个人头像 {} 字节\n插画收藏 {}\n小说收藏 {}\n",
        avatar.len(),
        illusts.len(),
        novels.len()
    );
    print!("{report}");
    std::fs::write("target/profile-flow-probe.txt", report)
        .expect("写入个人页面验证记录");
}
