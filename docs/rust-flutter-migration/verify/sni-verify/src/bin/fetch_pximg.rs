use std::io::{Read, Write};
use std::net::TcpStream;
use std::sync::Arc;


use rustls::crypto::ring::default_provider;
use rustls::pki_types::ServerName;
use rustls::{ClientConfig, ClientConnection};

use sni_verify::accept_all::AcceptAllVerifier;

/// 构造「不发送 SNI + 信任任意证书」的客户端配置，
/// 等价于 Kotlin 侧 RubySSLSocketFactory + TrustAllCertManager 的组合。
fn build_no_sni_config() -> Arc<ClientConfig> {
    let mut config = ClientConfig::builder_with_provider(Arc::new(default_provider()))
        .with_protocol_versions(rustls::DEFAULT_VERSIONS)
        .expect("protocol versions")
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(AcceptAllVerifier))
        .with_no_client_auth();
    // 反墙核心：不发送 SNI 扩展
    config.enable_sni = false;
    Arc::new(config)
}

/// 连到指定 IP，但 TLS 层的 server_name 仍然是原始域名（只是不发出去）。
/// 对应 Kotlin 侧的 InetAddress.getByAddress(host, ipBytes) 技巧。
fn fetch(ip: &str, port: u16, sni_host: &str, request: &[u8]) -> Result<Vec<u8>, String> {
    let server_name = ServerName::DnsName(
        sni_host
            .to_string()
            .try_into()
            .map_err(|_| format!("bad server name: {sni_host}"))?,
    );

    let mut conn =
        ClientConnection::new(build_no_sni_config(), server_name).map_err(|e| format!("conn: {e}"))?;
    let mut sock = TcpStream::connect((ip, port)).map_err(|e| format!("tcp connect {ip}: {e}"))?;
    sock.set_read_timeout(Some(std::time::Duration::from_secs(20)))
        .map_err(|e| format!("set timeout: {e}"))?;

    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(25);
    while conn.is_handshaking() {
        if conn.wants_write() {
            conn.write_tls(&mut sock).map_err(|e| format!("write_tls: {e}"))?;
        }
        if conn.wants_read() {
            match conn.read_tls(&mut sock) {
                Ok(0) => return Err("握手期间对端关闭连接".into()),
                Ok(_) => {
                    conn.process_new_packets()
                        .map_err(|e| format!("process_new_packets: {e}"))?;
                }
                Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {}
                Err(e) => return Err(format!("read_tls: {e}")),
            }
            sock.flush().ok();
        }
        if std::time::Instant::now() > deadline {
            return Err("握手超时".into());
        }
    }

    println!("  握手完成: {:?}", conn.protocol_version());

    let mut tls_stream = rustls::StreamOwned::new(conn, sock);
    tls_stream
        .write_all(request)
        .map_err(|e| format!("写请求: {e}"))?;
    tls_stream.flush().map_err(|e| format!("flush: {e}"))?;

    let mut out = Vec::new();
    tls_stream
        .read_to_end(&mut out)
        .map_err(|e| format!("读响应: {e}"))?;
    Ok(out)
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let ip = args.get(1).map(|s| s.as_str()).unwrap_or("210.140.139.134");
    let host = args.get(2).map(|s| s.as_str()).unwrap_or("i.pximg.net");
    let path = args
        .get(3)
        .map(|s| s.as_str())
        .unwrap_or("/img-original/img/2024/01/01/00/00/00/12345_p0.jpg");

    let request = format!(
        "GET {path} HTTP/1.1\r\n\
         Host: {host}\r\n\
         Referer: https://app-api.pixiv.net/\r\n\
         User-Agent: PixivIOSApp/8.6.10 (iOS 26.5; iPhone16,2)\r\n\
         Accept: image/*\r\n\
         Connection: close\r\n\r\n"
    );

    println!("目标 {ip}:443  层内 server_name={host}（不发出）");
    match fetch(ip, 443, host, request.as_bytes()) {
        Ok(resp) => {
            let text = String::from_utf8_lossy(&resp);
            let head = text.split("\r\n\r\n").next().unwrap_or("");
            println!("  响应字节数: {}", resp.len());
            println!("  状态行: {}", head.lines().next().unwrap_or(""));
            for line in head.lines() {
                let low = line.to_ascii_lowercase();
                if low.starts_with("content-type")
                    || low.starts_with("content-length")
                    || low.starts_with("server:")
                    || low.starts_with("cache-control")
                {
                    println!("  头: {line}");
                }
            }
            let out = format!("resp_{}.bin", ip.replace('.', "_"));
            std::fs::write(&out, &resp).ok();
            println!("  原始响应已写入 {out}");
        }
        Err(e) => println!("  失败: {e}"),
    }
}
