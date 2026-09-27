//! 搜索补全与当前账号可用的筛选选项。

use serde::Deserialize;

#[derive(Deserialize)]
struct SuggestionsResponse {
    tags: Option<Vec<RawSuggestion>>,
    trend_tags: Option<Vec<RawSuggestion>>,
}

#[derive(Deserialize)]
struct RawSuggestion {
    tag: Option<String>,
    name: Option<String>,
    translated_name: Option<String>,
}

pub struct SearchSuggestion {
    pub tag: String,
    pub translated_name: String,
}

/// 取回关键词补全建议。
pub async fn fetch_search_suggestions(word: String) -> Result<Vec<SearchSuggestion>, String> {
    let text = crate::api_client::get_authed(&format!(
        "/v2/search/autocomplete?merge_plain_keyword_results=true&word={}",
        crate::api_client::encode_component(&word)
    ))
    .await?;
    let parsed: SuggestionsResponse =
        serde_json::from_str(&text).map_err(|e| format!("解析搜索建议失败：{e}"))?;
    Ok(parsed
        .trend_tags
        .or(parsed.tags)
        .unwrap_or_default()
        .into_iter()
        .filter_map(|raw| {
            let tag = raw
                .tag
                .filter(|value| !value.is_empty())
                .or(raw.name.filter(|value| !value.is_empty()))?;
            Some(SearchSuggestion {
                tag,
                translated_name: raw.translated_name.unwrap_or_default(),
            })
        })
        .collect())
}

#[derive(Deserialize)]
struct RawOptions {
    illust: Option<RawScope>,
    novel: Option<RawScope>,
}

#[derive(Deserialize)]
struct RawScope {
    tool: Option<RawTools>,
    genre: Option<RawGenres>,
    lang: Option<RawLanguages>,
}

#[derive(Deserialize)]
struct RawTools {
    options: Vec<String>,
}

#[derive(Deserialize)]
struct RawGenres {
    options: Vec<RawGenre>,
}

#[derive(Deserialize)]
struct RawGenre {
    id: i64,
    label: String,
}

#[derive(Deserialize)]
struct RawLanguages {
    options: Vec<RawLanguage>,
}

#[derive(Deserialize)]
struct RawLanguage {
    code: String,
    name: String,
}

pub struct SearchOption {
    pub value: String,
    pub label: String,
}

pub struct SearchOptions {
    pub tools: Vec<String>,
    pub illust_languages: Vec<SearchOption>,
    pub novel_languages: Vec<SearchOption>,
    pub genres: Vec<SearchOption>,
}

/// 取回与当前关键词关联的语言、制图工具和小说类型选项。
pub async fn fetch_search_options(word: String, target: String) -> Result<SearchOptions, String> {
    let text = crate::api_client::get_authed(&format!(
        "/v1/search/options?word={}&search_target={}&merge_plain_keyword_results=true&include_translated_tag_results=true&search_ai_type=0",
        crate::api_client::encode_component(&word),
        crate::api_client::encode_component(&target),
    ))
    .await?;
    let parsed: RawOptions =
        serde_json::from_str(&text).map_err(|e| format!("解析搜索选项失败：{e}"))?;
    let illust = parsed.illust;
    let novel = parsed.novel;
    Ok(SearchOptions {
        tools: illust
            .as_ref()
            .and_then(|scope| scope.tool.as_ref())
            .map(|tools| tools.options.clone())
            .unwrap_or_default(),
        illust_languages: illust
            .as_ref()
            .and_then(|scope| scope.lang.as_ref())
            .map(|languages| convert_languages(&languages.options))
            .unwrap_or_default(),
        novel_languages: novel
            .as_ref()
            .and_then(|scope| scope.lang.as_ref())
            .map(|languages| convert_languages(&languages.options))
            .unwrap_or_default(),
        genres: novel
            .as_ref()
            .and_then(|scope| scope.genre.as_ref())
            .map(|genres| {
                genres
                    .options
                    .iter()
                    .map(|genre| SearchOption {
                        value: genre.id.to_string(),
                        label: genre.label.clone(),
                    })
                    .collect()
            })
            .unwrap_or_default(),
    })
}

fn convert_languages(languages: &[RawLanguage]) -> Vec<SearchOption> {
    languages
        .iter()
        .map(|language| SearchOption {
            value: language.code.clone(),
            label: language.name.clone(),
        })
        .collect()
}
