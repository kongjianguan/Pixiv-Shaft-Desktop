//! 把 rust/ech/src/ech.rs 直接作为模块内联，绕过 JNI 层验证 ECH 链路。
//! 用途：验证「ECH 迁移到 Rust 原生后，dylib + JNI 胶水可以整体删掉」。

mod ech;

fn main() {
    println!("=== ensure_client()：拉取 ECH 配置并建立连接池 ===");
    match ech::ensure_client() {
        Ok(_) => println!("客户端就绪"),
        Err(e) => {
            println!("客户端构建失败: {e}");
            return;
        }
    }

    for (method, url) in [
        ("GET", "https://app-api.pixiv.net/v1/illust/ranking?mode=day&date=2026-09-01"),
        ("GET", "https://www.pixiv.net/ajax/top/illust?mode=all"),
    ] {
        println!("\n--- {method} {url}");
        let headers = vec![
            ("user-agent".to_string(), "PixivIOSApp/8.6.10 (iOS 26.5; iPhone16,2)".to_string()),
            ("app-os".to_string(), "ios".to_string()),
            ("app-os-version".to_string(), "26.5".to_string()),
            ("app-version".to_string(), "8.6.10".to_string()),
        ];
        match ech::request(method, url, &headers, None) {
            Ok(resp) => {
                println!("  HTTP 状态: {}", resp.status);
                println!("  响应头数量: {}", resp.headers.len());
                for (k, v) in resp.headers.iter() {
                    let vv: String = v.chars().take(90).collect();
                    println!("    {k}: {vv}");
                }
                println!("  响应体字节数: {}", resp.body.len());
                let preview: String = String::from_utf8_lossy(&resp.body).chars().take(160).collect();
                println!("  响应体预览: {preview}");
            }
            Err(e) => println!("  失败: {e}"),
        }
    }
}
