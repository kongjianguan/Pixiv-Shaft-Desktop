//! 端到端验证：用不发送 SNI 的 TLS 从 pximg 取回一张真实图片。
//!
//! 这个程序回答两个问题：
//! 1. 取图能否成功（失败时以非零码退出，可作为构建门禁）
//! 2. 成功是否确实来自「不发 SNI」，而不是 reqwest 忽略了传入的 TLS 配置
//!
//! 取回的图片写入 `fetched-image.bin`，作为可复查的产物。
//!
//! 运行方式：
//! ```text
//! cargo run --bin fetch_probe
//! cargo run --bin fetch_probe -- <图片地址>
//! ```

const DEFAULT_URL: &str =
    "https://i.pximg.net/c/600x1200_90_webp/img-master/img/2026/03/17/00/37/51/142389693_p0_master1200.jpg";

#[tokio::main]
async fn main() {
    let url = std::env::args().nth(1).unwrap_or_else(|| DEFAULT_URL.to_string());
    println!("目标地址: {url}");
    println!();

    let without_sni = fetch_with_sni(&url, false).await;
    report("不发送 SNI", &without_sni);

    let with_sni = fetch_with_sni(&url, true).await;
    report("发送 SNI", &with_sni);

    println!();
    println!("解读：在中国大陆网络下，「发送 SNI」会被中断，「不发送 SNI」成功。");
    println!("      在不受干扰的网络（例如境外构建机）两种配置都可能成功，");
    println!("      因此构建机上的这组对照不能替代抓包证据。");

    match without_sni {
        Ok(bytes) => {
            std::fs::write("fetched-image.bin", &bytes).expect("写入获取到的图片");
            println!();
            println!("已写入 fetched-image.bin");
        }
        Err(e) => {
            eprintln!();
            eprintln!("不发送 SNI 的取图失败，这是核心能力，构建判定为失败: {e}");
            std::process::exit(1);
        }
    }
}

fn report(label: &str, result: &Result<Vec<u8>, String>) {
    match result {
        Ok(bytes) => println!(
            "{label}: 成功，{} 字节，文件头 {:02x} {:02x} {:02x} {:02x}，识别为 {}",
            bytes.len(),
            bytes[0],
            bytes[1],
            bytes[2],
            bytes[3],
            sniff(bytes)
        ),
        Err(e) => println!("{label}: 失败，{e}"),
    }
}

async fn fetch_with_sni(url: &str, enable_sni: bool) -> Result<Vec<u8>, String> {
    let response = pixiv_core::image::image_client(enable_sni)
        .get(url)
        .send()
        .await
        .map_err(|e| format!("{e}"))?;
    let status = response.status();
    if !status.is_success() {
        return Err(format!("状态码 {status}"));
    }
    Ok(response.bytes().await.map_err(|e| format!("{e}"))?.to_vec())
}

/// 按文件头判断图片格式，用来确认拿到的确实是图片而不是错误页。
fn sniff(bytes: &[u8]) -> &'static str {
    match bytes {
        [0xFF, 0xD8, ..] => "JPEG",
        [0x89, b'P', b'N', b'G', ..] => "PNG",
        [b'R', b'I', b'F', b'F', _, _, _, _, b'W', b'E', b'B', b'P', ..] => "WebP",
        [b'G', b'I', b'F', b'8', ..] => "GIF",
        _ => "未知（可能是错误页）",
    }
}
