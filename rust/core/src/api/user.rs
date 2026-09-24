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
