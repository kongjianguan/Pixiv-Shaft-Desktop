//! macOS 钥匙串读写。
//!
//! 登录凭据放在系统钥匙串里，服务名与现有版本一致（`PixivShaft:<键名>`），
//! 账号名取当前用户名。沿用同一套命名，现有版本的登录态在新版本里可以直接用。
//!
//! 这里直接调用 Security 框架，不经过 `security` 命令行：命令行方式会把密钥
//! 写进进程参数，同一台机器上的其他进程通过 `ps` 就能看到。

use security_framework::passwords::{
    PasswordOptions, delete_generic_password, generic_password, set_generic_password,
};

const SERVICE_PREFIX: &str = "PixivShaft";

/// 「该项不存在」的系统返回码（`errSecItemNotFound`）。
const ERR_SEC_ITEM_NOT_FOUND: i32 = -25300;

pub const KEY_ACCESS: &str = "access_token";
pub const KEY_REFRESH: &str = "refresh_token";
pub const KEY_USER: &str = "user_json";

fn account() -> String {
    std::env::var("USER").unwrap_or_else(|_| "pixiv".to_string())
}

fn service(key: &str) -> String {
    format!("{SERVICE_PREFIX}:{key}")
}

/// 写入一项凭据，已存在时覆盖。
pub fn put(key: &str, value: &str) -> Result<(), String> {
    set_generic_password(&service(key), &account(), value.as_bytes())
        .map_err(|e| format!("写入钥匙串项 {} 失败：{e}", service(key)))
}

/// 读取一项凭据，不存在时返回 None。
pub fn get(key: &str) -> Result<Option<String>, String> {
    let options = PasswordOptions::new_generic_password(&service(key), &account());
    match generic_password(options) {
        Ok(bytes) => String::from_utf8(bytes)
            .map(Some)
            .map_err(|e| format!("钥匙串项 {} 不是合法文本：{e}", service(key))),
        // 尚未登录时读不到，属于正常情况。
        Err(error) if error.code() == ERR_SEC_ITEM_NOT_FOUND => Ok(None),
        Err(error) => Err(format!("读取钥匙串项 {} 失败：{error}", service(key))),
    }
}

/// 删除一项凭据。不存在时视作成功。
pub fn remove(key: &str) -> Result<(), String> {
    match delete_generic_password(&service(key), &account()) {
        Ok(()) => Ok(()),
        Err(error) if error.code() == ERR_SEC_ITEM_NOT_FOUND => Ok(()),
        Err(error) => Err(format!("删除钥匙串项 {} 失败：{error}", service(key))),
    }
}
