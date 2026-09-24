//! macOS 钥匙串读写。
//!
//! 登录凭据放在系统钥匙串里，服务名与现有版本一致（`PixivShaft:<键名>`），
//! 账号名取当前用户名。沿用同一套命名，现有版本的登录态在新版本里可以直接用。
//!
//! 走 `security` 命令行而不是直接调用 Security 框架：实测框架的写入在无人应答
//! 的会话里会一直等待授权提示，命令行方式在同样环境下可以正常完成。
//!
//! 密码经 `-w` 传参。这个形式下密码会出现在进程参数里，同一台机器上的同一用户
//! 通过 `ps` 可以看到；交互式提示那一形式要求把密码重复输入两次，在非交互环境
//! 里不可靠。
//!
//! 只存访问令牌与刷新令牌，两者都是 ASCII。`security -w` 对含非 ASCII 字节的值
//! 会以十六进制输出，用户名这类内容因此不放这里，需要时从接口取。

use std::process::{Command, Stdio};

const SERVICE_PREFIX: &str = "PixivShaft";
/// 「该项不存在」的返回码，与 Security 框架的 errSecItemNotFound 一致。
const ERR_SEC_ITEM_NOT_FOUND: i32 = 44;

pub const KEY_ACCESS: &str = "access_token";
pub const KEY_REFRESH: &str = "refresh_token";

fn account() -> String {
    std::env::var("USER").unwrap_or_else(|_| "pixiv".to_string())
}

fn service(key: &str) -> String {
    format!("{SERVICE_PREFIX}:{key}")
}

/// 写入一项凭据，已存在时覆盖。
pub fn put(key: &str, value: &str) -> Result<(), String> {
    let output = Command::new("security")
        .args([
            "add-generic-password",
            "-a",
            &account(),
            "-s",
            &service(key),
            "-U",
            "-w",
            value,
        ])
        .stdin(Stdio::null())
        .stderr(Stdio::piped())
        .output()
        .map_err(|e| format!("启动 security 失败：{e}"))?;

    if !output.status.success() {
        return Err(format!(
            "写入钥匙串项 {} 失败：{}",
            service(key),
            String::from_utf8_lossy(&output.stderr).trim()
        ));
    }
    Ok(())
}

/// 读取一项凭据，不存在时返回 None。
pub fn get(key: &str) -> Result<Option<String>, String> {
    let output = Command::new("security")
        .args([
            "find-generic-password",
            "-a",
            &account(),
            "-s",
            &service(key),
            "-w",
        ])
        .stderr(Stdio::piped())
        .output()
        .map_err(|e| format!("启动 security 失败：{e}"))?;

    if output.status.success() {
        let text = String::from_utf8(output.stdout)
            .map_err(|e| format!("钥匙串项 {} 不是合法文本：{e}", service(key)))?;
        // 只去掉命令行追加的换行，凭据本身的内容保持原样。
        return Ok(Some(text.strip_suffix('\n').unwrap_or(&text).to_string()));
    }

    let code = output.status.code().unwrap_or(-1);
    let message = String::from_utf8_lossy(&output.stderr).trim().to_string();
    if code == ERR_SEC_ITEM_NOT_FOUND || message.contains("could not be found") {
        return Ok(None);
    }
    Err(format!("读取钥匙串项 {} 失败：{message}", service(key)))
}

/// 删除一项凭据。不存在时视作成功。
pub fn remove(key: &str) -> Result<(), String> {
    let output = Command::new("security")
        .args([
            "delete-generic-password",
            "-a",
            &account(),
            "-s",
            &service(key),
        ])
        .stderr(Stdio::piped())
        .output()
        .map_err(|e| format!("启动 security 失败：{e}"))?;

    if output.status.success() {
        return Ok(());
    }
    let message = String::from_utf8_lossy(&output.stderr).trim().to_string();
    if message.contains("could not be found") {
        return Ok(());
    }
    Err(format!("删除钥匙串项 {} 失败：{message}", service(key)))
}
