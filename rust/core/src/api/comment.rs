//! 暴露给界面的评论接口。
//!
//! 列表接口返回 `next_url` 作为下一页游标，与现有版本一致。

use serde::Deserialize;

#[derive(Deserialize)]
struct CommentResponse {
    comments: Vec<RawComment>,
    next_url: Option<String>,
}

#[derive(Deserialize)]
struct RawComment {
    id: i64,
    comment: Option<String>,
    date: Option<String>,
    has_replies: Option<bool>,
    user: Option<RawUser>,
}

#[derive(Deserialize)]
struct RawUser {
    id: i64,
    name: Option<String>,
    account: Option<String>,
    profile_image_urls: Option<RawProfileImage>,
}

#[derive(Deserialize)]
struct RawProfileImage {
    px_170x170: Option<String>,
    medium: Option<String>,
}

pub struct CommentEntry {
    pub id: i64,
    pub body: String,
    pub date: String,
    pub author_id: i64,
    pub author_name: String,
    pub avatar_url: String,
    pub has_replies: bool,
}

pub struct CommentPage {
    pub comments: Vec<CommentEntry>,
    /// 下一页游标，为空表示没有更多。
    pub next_url: String,
}

/// 取回一个作品的评论第一页。
pub async fn fetch_illust_comments(illust_id: i64) -> Result<CommentPage, String> {
    fetch_comments(&format!("/v3/illust/comments?illust_id={illust_id}")).await
}

/// 按游标取下一页。`next_url` 是接口返回的完整地址。
pub async fn fetch_next_comments(next_url: String) -> Result<CommentPage, String> {
    fetch_comments(&path_of(&next_url)).await
}

/// 发表评论。`parent_comment_id` 非空时是回复。
pub async fn add_illust_comment(
    illust_id: i64,
    body: String,
    parent_comment_id: i64,
) -> Result<(), String> {
    // 先绑定成局部变量：字段表借用这些字符串，临时值的生命周期不够长。
    let illust = illust_id.to_string();
    let parent = parent_comment_id.to_string();
    let mut fields: Vec<(&str, &str)> = vec![
        ("illust_id", illust.as_str()),
        ("comment", body.as_str()),
    ];
    if parent_comment_id != 0 {
        fields.push(("parent_comment_id", parent.as_str()));
    }
    crate::api_client::post_with_auth("/v1/illust/comment/add", &fields)
        .await
        .map(|_| ())
}

/// 删除自己的评论。
pub async fn delete_illust_comment(comment_id: i64) -> Result<(), String> {
    crate::api_client::post_with_auth(
        "/v1/illust/comment/delete",
        &[("comment_id", &comment_id.to_string())],
    )
    .await
    .map(|_| ())
}

async fn fetch_comments(path: &str) -> Result<CommentPage, String> {
    let text = crate::api_client::get_authed(path).await?;
    let parsed: CommentResponse =
        serde_json::from_str(&text).map_err(|e| format!("解析评论失败：{e}"))?;

    Ok(CommentPage {
        comments: parsed.comments.into_iter().map(convert).collect(),
        next_url: parsed.next_url.unwrap_or_default(),
    })
}

fn convert(raw: RawComment) -> CommentEntry {
    let user = raw.user;
    CommentEntry {
        id: raw.id,
        body: raw.comment.unwrap_or_default(),
        date: raw.date.unwrap_or_default(),
        author_id: user.as_ref().map(|u| u.id).unwrap_or(0),
        author_name: user
            .as_ref()
            .map(|u| u.name.clone().or(u.account.clone()).unwrap_or_default())
            .unwrap_or_default(),
        avatar_url: user
            .as_ref()
            .and_then(|u| u.profile_image_urls.as_ref())
            .and_then(|image| image.px_170x170.clone().or(image.medium.clone()))
            .unwrap_or_default(),
        has_replies: raw.has_replies.unwrap_or(false),
    }
}

/// 把接口返回的完整地址转成传输层要的路径。
pub(crate) fn path_of(url: &str) -> String {
    match url.find("://") {
        Some(scheme_end) => {
            let after_scheme = &url[scheme_end + 3..];
            match after_scheme.find('/') {
                Some(path_start) => after_scheme[path_start..].to_string(),
                None => "/".to_string(),
            }
        }
        None => url.to_string(),
    }
}
