//! 模拟 PixivShaft 的真实 API 形态，用于检验 flutter_rust_bridge 2.13 的
//! 异步 / Stream / 大字节负载支持。

use flutter_rust_bridge::frb;
use flutter_rust_bridge::StreamSink;
use std::time::Duration;

/// 下载队列中的一项进度。
pub struct DownloadProgress {
    pub id: u64,
    pub sent_bytes: u64,
    pub total_bytes: u64,
    pub done: bool,
}

/// 一次 API 分页结果。
pub struct PageResult {
    pub items: Vec<String>,
    pub next_url: Option<String>,
}

#[frb(init)]
pub fn init_app() {
    flutter_rust_bridge::setup_default_user_utils();
}

/// 同步调用：占用调用方线程。
#[frb(sync)]
pub fn greet(name: String) -> String {
    format!("Hello, {name}!")
}

/// 异步单次返回：模拟一次 pixiv API 请求。
pub async fn fetch_illust_page(next_url: Option<String>) -> anyhow::Result<PageResult> {
    tokio::time::sleep(Duration::from_millis(50)).await;
    Ok(PageResult {
        items: vec!["illust-1".to_string(), "illust-2".to_string()],
        next_url: next_url,
    })
}

/// 持续数据流：下载队列进度推送。
pub fn watch_download_queue(sink: StreamSink<DownloadProgress>) -> anyhow::Result<()> {
    for i in 0..100u64 {
        sink.add(DownloadProgress {
            id: i,
            sent_bytes: i * 1024,
            total_bytes: 100 * 1024,
            done: i == 99,
        })?;
        std::thread::sleep(Duration::from_millis(10));
    }
    Ok(())
}

/// 分页流式返回：每次 yield 一批。
pub fn stream_ranking(stream: StreamSink<Vec<String>>) -> anyhow::Result<()> {
    for page in 0..5 {
        stream.add(vec![format!("page-{page}-a"), format!("page-{page}-b")])?;
    }
    Ok(())
}

/// 图片字节流：每次一块 raw bytes，模拟分块下载写盘。
pub fn stream_image_chunks(
    sink: StreamSink<Vec<u8>>,
    chunks: u32,
    chunk_size: usize,
) -> anyhow::Result<()> {
    for i in 0..chunks {
        sink.add(vec![(i % 251) as u8; chunk_size])?;
    }
    Ok(())
}

/// 大字节一次性返回：走序列化会拷贝。
pub fn load_image_bytes(size: usize) -> Vec<u8> {
    vec![0xABu8; size]
}
