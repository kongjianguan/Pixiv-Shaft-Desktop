//! 暴露给界面的用户接口。

use serde::Deserialize;

#[derive(Deserialize)]
struct UserDetailResponse {
    user: Option<RawUser>,
    profile: Option<RawProfile>,
}

#[derive(Deserialize)]
struct RawUser {
    id: i64,
    name: Option<String>,
    account: Option<String>,
    profile_image_urls: Option<RawProfileImage>,
    is_followed: Option<bool>,
}

#[derive(Deserialize)]
struct RawProfileImage {
    px_170x170: Option<String>,
    medium: Option<String>,
}

#[derive(Deserialize)]
struct RawProfile {
    webpage: Option<String>,
    total_illusts: Option<i64>,
    total_manga: Option<i64>,
    total_novels: Option<i64>,
}

pub struct UserProfile {
    pub id: i64,
    pub name: String,
    pub account: String,
    pub avatar_url: String,
    pub webpage: String,
    pub total_illusts: i64,
    pub total_manga: i64,
    pub total_novels: i64,
    pub is_followed: bool,
}

/// 取回用户资料。
pub async fn fetch_user_detail(user_id: i64) -> Result<UserProfile, String> {
    let text = crate::api_client::get_authed(&format!(
        "/v1/user/detail?user_id={user_id}&filter=for_ios"
    ))
    .await?;
    let parsed: UserDetailResponse =
        serde_json::from_str(&text).map_err(|e| format!("解析用户资料失败：{e}"))?;

    let raw = parsed
        .user
        .ok_or_else(|| format!("用户 {user_id} 的资料为空"))?;
    let profile = parsed.profile;

    Ok(UserProfile {
        id: raw.id,
        name: raw.name.clone().or(raw.account.clone()).unwrap_or_default(),
        account: raw.account.clone().unwrap_or_default(),
        avatar_url: raw
            .profile_image_urls
            .as_ref()
            .and_then(|image| image.px_170x170.clone().or(image.medium.clone()))
            .unwrap_or_default(),
        webpage: profile
            .as_ref()
            .and_then(|p| p.webpage.clone())
            .unwrap_or_default(),
        total_illusts: profile.as_ref().and_then(|p| p.total_illusts).unwrap_or(0),
        total_manga: profile.as_ref().and_then(|p| p.total_manga).unwrap_or(0),
        total_novels: profile.as_ref().and_then(|p| p.total_novels).unwrap_or(0),
        is_followed: raw.is_followed.unwrap_or(false),
    })
}

/// 取回当前登录用户的 id，用于判断哪些内容属于自己。
///
/// 登录时已经拿到过，进程内缓存；从钥匙串恢复的会话没有这个值，
/// 因此这里按需取一次并写回会话。
pub async fn self_user_id() -> Result<i64, String> {
    if let Some(session) = crate::session::get().await {
        if session.user_id != 0 {
            return Ok(session.user_id);
        }
    }

    let text = crate::api_client::get_authed("/v1/user/me/state").await?;
    let parsed: SelfStateResponse =
        serde_json::from_str(&text).map_err(|e| format!("解析自己的资料失败：{e}"))?;
    let id = parsed.profile.map(|user| user.id).unwrap_or(0);

    if id != 0 {
        if let Some(mut session) = crate::session::get().await {
            session.user_id = id;
            crate::session::set(session).await;
        }
    }
    Ok(id)
}

#[derive(Deserialize)]
struct SelfStateResponse {
    profile: Option<RawSelfUser>,
}

#[derive(Deserialize)]
struct RawSelfUser {
    id: i64,
}

/// 关注一个用户。`restrict` 取 `public` 或 `private`。
pub async fn follow_user(user_id: i64, restrict: String) -> Result<(), String> {
    crate::api_client::post_with_auth(
        "/v1/user/follow/add",
        &[("user_id", &user_id.to_string()), ("restrict", &restrict)],
    )
    .await
    .map(|_| ())
}

pub async fn unfollow_user(user_id: i64) -> Result<(), String> {
    crate::api_client::post_with_auth(
        "/v1/user/follow/delete",
        &[("user_id", &user_id.to_string())],
    )
    .await
    .map(|_| ())
}

#[derive(Deserialize)]
struct UserPreviewResponse {
    user_previews: Vec<RawUserPreview>,
    next_url: Option<String>,
}

#[derive(Deserialize)]
struct RawUserPreview {
    user: Option<RawListUser>,
    illusts: Option<Vec<RawPreviewIllust>>,
}

#[derive(Deserialize)]
struct RawListUser {
    id: i64,
    name: Option<String>,
    account: Option<String>,
    profile_image_urls: Option<RawProfileImage>,
    is_followed: Option<bool>,
}

#[derive(Deserialize)]
struct RawPreviewIllust {
    image_urls: Option<RawPreviewImageUrls>,
}

#[derive(Deserialize)]
struct RawPreviewImageUrls {
    medium: Option<String>,
    square_medium: Option<String>,
}

/// 用户列表里的一项。
pub struct UserPreview {
    pub id: i64,
    pub name: String,
    pub account: String,
    pub avatar_url: String,
    pub is_followed: bool,
    /// 该用户最近一张作品的缩略图，没有则为空。
    pub latest_illust_url: String,
}

pub struct UserListPage {
    pub users: Vec<UserPreview>,
    /// 下一页游标，为空表示没有更多。
    pub next_url: String,
}

/// 关注中。`restrict` 取 `public` 或 `private`。
pub async fn fetch_following_users(
    user_id: i64,
    restrict: String,
) -> Result<UserListPage, String> {
    fetch_user_list(&format!(
        "/v1/user/following?user_id={user_id}&restrict={}",
        crate::api_client::encode_component(&restrict)
    ))
    .await
}

/// 粉丝。
pub async fn fetch_follower_users(user_id: i64) -> Result<UserListPage, String> {
    fetch_user_list(&format!("/v1/user/follower?filter=for_ios&user_id={user_id}")).await
}

/// 好P友。
pub async fn fetch_mypixiv_users(user_id: i64) -> Result<UserListPage, String> {
    fetch_user_list(&format!("/v1/user/mypixiv?user_id={user_id}")).await
}

/// 按游标取下一页用户列表。
pub async fn fetch_next_users(next_url: String) -> Result<UserListPage, String> {
    fetch_user_list(&crate::api::comment::path_of(&next_url)).await
}

async fn fetch_user_list(path: &str) -> Result<UserListPage, String> {
    let text = crate::api_client::get_authed(path).await?;
    let parsed: UserPreviewResponse =
        serde_json::from_str(&text).map_err(|e| format!("解析用户列表失败：{e}"))?;

    Ok(UserListPage {
        users: parsed
            .user_previews
            .into_iter()
            .filter_map(|preview| {
                let user = preview.user?;
                let latest = preview
                    .illusts
                    .unwrap_or_default()
                    .into_iter()
                    .next()
                    .and_then(|illust| illust.image_urls)
                    .and_then(|urls| urls.medium.or(urls.square_medium))
                    .unwrap_or_default();
                Some(UserPreview {
                    id: user.id,
                    name: user
                        .name
                        .clone()
                        .or(user.account.clone())
                        .unwrap_or_default(),
                    account: user.account.unwrap_or_default(),
                    avatar_url: user
                        .profile_image_urls
                        .as_ref()
                        .and_then(|image| image.px_170x170.clone().or(image.medium.clone()))
                        .unwrap_or_default(),
                    is_followed: user.is_followed.unwrap_or(false),
                    latest_illust_url: latest,
                })
            })
            .collect(),
        next_url: parsed.next_url.unwrap_or_default(),
    })
}
