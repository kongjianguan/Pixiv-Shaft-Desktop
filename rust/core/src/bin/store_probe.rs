//! 端到端验证：登录凭据与下载队列在进程重启后仍然保留。
//!
//! 每个子命令都在独立进程里运行，因此「重启后还在不在」是被真正验证的，
//! 而不是同一个进程里读写一遍。
//!
//! 为了不动到真实的登录态与下载队列，这里用带 `probe_` 前缀的钥匙串键名，
//! 并把 `HOME` 指到工作区内的目录，让数据库落在隔离的位置。
//!
//! 用法（由 verify 脚本驱动，也可手动按顺序执行）：
//! ```text
//! store_probe token-write
//! store_probe token-verify
//! store_probe token-clear
//! store_probe enqueue <id>
//! store_probe pause <id>
//! store_probe expect <id> <状态>
//! store_probe download
//! store_probe clear-queue
//! ```

use std::path::PathBuf;

use pixiv_core::download;
use pixiv_core::keychain;

/// 数据库落在当前目录下，不去碰真实的应用数据。
const PROBE_DATABASE: &str = "probe.db";

const KEY_ACCESS: &str = "probe_access_token";
const KEY_REFRESH: &str = "probe_refresh_token";

const ACCESS_VALUE: &str = "probe-access-token-8f3a";
const REFRESH_VALUE: &str = "probe-refresh-token-2b7c";

/// 下载产物落在当前目录下，不使用系统临时目录。
const DOWNLOAD_DIR: &str = "probe-downloads";

#[tokio::main]
async fn main() {
    pixiv_core::db::use_database(PathBuf::from(PROBE_DATABASE));

    let command = std::env::args().nth(1).unwrap_or_default();
    let result = match command.as_str() {
        "token-write" => token_write(),
        "token-verify" => token_verify(),
        "token-clear" => token_clear(),
        "enqueue" => enqueue(&argument(2)),
        "pause" => set_state(&argument(2), download::STATUS_PAUSED),
        "resume" => set_state(&argument(2), download::STATUS_QUEUED),
        "expect" => expect_status(&argument(2), &argument(3)),
        "download" => download_pending().await,
        "list" => list_tasks(),
        "clear-queue" => clear_queue(),
        "setting-write" => setting_write(&argument(2), &argument(3)),
        "setting-expect" => setting_expect(&argument(2), &argument(3)),
        "setting-reset" => setting_reset(&argument(2)),
        "browse-write" => browse_write(&argument(2), &argument(3)),
        "browse-expect" => browse_expect(&argument(2), &argument(3), &argument(4)),
        "search-write" => search_write(&argument(2)),
        "search-expect" => search_expect(&argument(2)),
        "clear-history" => clear_history(),
        other => {
            eprintln!("未知子命令：{other}");
            std::process::exit(2);
        }
    };

    if let Err(error) = result {
        eprintln!("失败：{error}");
        std::process::exit(1);
    }
}

fn argument(index: usize) -> String {
    std::env::args().nth(index).unwrap_or_else(|| {
        eprintln!("缺少第 {index} 个参数");
        std::process::exit(2);
    })
}

fn token_write() -> Result<(), String> {
    keychain::put(KEY_ACCESS, ACCESS_VALUE)?;
    keychain::put(KEY_REFRESH, REFRESH_VALUE)?;
    println!("已写入两项凭据");
    Ok(())
}

fn token_verify() -> Result<(), String> {
    let access = keychain::get(KEY_ACCESS)?;
    let refresh = keychain::get(KEY_REFRESH)?;

    println!("access_token: {access:?}");
    println!("refresh_token: {refresh:?}");

    if access.as_deref() != Some(ACCESS_VALUE) {
        return Err("access_token 与写入值不一致".into());
    }
    if refresh.as_deref() != Some(REFRESH_VALUE) {
        return Err("refresh_token 与写入值不一致".into());
    }
    println!("两项凭据与写入值一致");
    Ok(())
}

fn token_clear() -> Result<(), String> {
    keychain::remove(KEY_ACCESS)?;
    keychain::remove(KEY_REFRESH)?;

    if keychain::get(KEY_ACCESS)?.is_some() {
        return Err("删除后仍能读到 access_token".into());
    }
    println!("已删除两项凭据，且确认读不到了");
    Ok(())
}

fn enqueue(id: &str) -> Result<(), String> {
    std::fs::create_dir_all(DOWNLOAD_DIR).map_err(|e| format!("创建下载目录失败：{e}"))?;
    download::enqueue_image(
        id,
        142389693,
        0,
        1,
        "验证用作品",
        "验证用作者",
        "https://i.pximg.net/c/600x1200_90_webp/img-master/img/2026/03/17/00/37/51/142389693_p0_master1200.jpg",
        DOWNLOAD_DIR,
    )?;
    println!("已加入队列：{id}");
    Ok(())
}

fn set_state(id: &str, status: &str) -> Result<(), String> {
    download::set_status(id, status, None)?;
    println!("{id} 状态改为 {status}");
    Ok(())
}

fn expect_status(id: &str, expected: &str) -> Result<(), String> {
    let actual = download::status_of(id)?;
    println!("{id} 当前状态：{actual:?}");
    match actual.as_deref() {
        Some(status) if status == expected => Ok(()),
        other => Err(format!("期望状态 {expected}，实际 {other:?}")),
    }
}

fn list_tasks() -> Result<(), String> {
    for task in download::list()? {
        println!(
            "{} status={} bytes={}/{} output={}",
            task.id, task.status, task.bytes_downloaded, task.total_bytes, task.output_path
        );
    }
    Ok(())
}

async fn download_pending() -> Result<(), String> {
    for (id, outcome) in download::process_queue().await? {
        match outcome {
            Ok(()) => println!("{id} 处理完成"),
            Err(error) => println!("{id} 处理失败：{error}"),
        }
    }
    Ok(())
}

fn clear_queue() -> Result<(), String> {
    let tasks = download::list()?;
    let count = tasks.len();
    for task in tasks {
        download::remove(&task.id)?;
    }
    println!("已清空 {count} 条任务");
    Ok(())
}

fn setting_write(key: &str, value: &str) -> Result<(), String> {
    pixiv_core::settings::put(key, value)?;
    println!("已写入设置 {key} = {value}");
    Ok(())
}

fn setting_expect(key: &str, expected: &str) -> Result<(), String> {
    let actual = pixiv_core::settings::get(key)?;
    println!("设置 {key} 当前值：{actual:?}");
    match actual.as_deref() {
        Some(value) if value == expected => Ok(()),
        other => Err(format!("设置 {key} 期望 {expected}，实际 {other:?}")),
    }
}

fn setting_reset(key: &str) -> Result<(), String> {
    pixiv_core::settings::reset(key)?;
    println!("已重置设置 {key}");
    Ok(())
}

fn browse_write(content_type: &str, target_id: &str) -> Result<(), String> {
    let id: i64 = target_id
        .parse()
        .map_err(|e| format!("目标 id {target_id} 不是整数：{e}"))?;
    pixiv_core::history::record_browse(content_type, id, r#"{"title":"验证用"}"#)?;
    println!("已记录浏览 {content_type}/{id}");
    Ok(())
}

fn browse_expect(content_type: &str, target_id: &str, expected: &str) -> Result<(), String> {
    let id: i64 = target_id
        .parse()
        .map_err(|e| format!("目标 id {target_id} 不是整数：{e}"))?;
    let entries = pixiv_core::history::list_browse(content_type, 100, 0)?;
    let found = entries.iter().any(|entry| entry.target_id == id);
    println!("浏览记录条数：{}，是否含 {id}：{found}", entries.len());
    if found != (expected == "present") {
        return Err(format!("期望 {expected}，实际 {}",
            if found { "present" } else { "absent" }));
    }
    Ok(())
}

fn search_write(keyword: &str) -> Result<(), String> {
    pixiv_core::history::record_search(keyword, 0)?;
    println!("已记录搜索 {keyword}");
    Ok(())
}

fn search_expect(expected_count: &str) -> Result<(), String> {
    let expected: usize = expected_count
        .parse()
        .map_err(|e| format!("条数 {expected_count} 不是整数：{e}"))?;
    let entries = pixiv_core::history::list_searches(50)?;
    println!("搜索记录条数：{}", entries.len());
    for entry in &entries {
        println!("  {} pinned={}", entry.keyword, entry.pinned);
    }
    if entries.len() != expected {
        return Err(format!("期望 {expected} 条，实际 {} 条", entries.len()));
    }
    Ok(())
}

fn clear_history() -> Result<(), String> {
    pixiv_core::history::clear_browse()?;
    pixiv_core::history::clear_searches()?;
    println!("已清空浏览记录与搜索记录");
    Ok(())
}
