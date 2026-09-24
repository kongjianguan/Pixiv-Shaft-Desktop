use sni_verify::accept_all::AcceptAllVerifier;

use std::io::{Read, Write};
use std::net::TcpStream;
use std::sync::Arc;


use rustls::pki_types::ServerName;
use rustls::{ClientConfig, ClientConnection, RootCertStore};

/// 构造一个客户端 TLS 配置。enable_sni=false 时禁用 SNI 扩展。
/// 两种方式必须任选其一：要么.enable_sni=false，要么 ServerName::IpAddress，否则 rustls 构造 ClientConfig 时会 panic。
fn build_config(enable_sni: bool) -> Arc<ClientConfig> {
    let mut config = ClientConfig::builder_with_provider(Arc::new(
        rustls::crypto::ring::default_provider(),
    ))
    .with_protocol_versions(rustls::DEFAULT_VERSIONS)
    .expect("protocol versions")
    // 无 SNI 时域名无法与证书比对，必须放行证书校验
    .dangerous()
    .with_custom_certificate_verifier(Arc::new(AcceptAllVerifier))
    .with_no_client_auth();

    config.enable_sni = enable_sni;
    Arc::new(config)
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let host = args.get(1).cloned().unwrap_or_else(|| "127.0.0.1".to_string());
    let port: u16 = args.get(2).cloned().and_then(|p| p.parse().ok()).unwrap_or(8443);
    let enable_sni: bool = args.get(3).map(|s| s == "true").unwrap_or(false);

    let server_name = if host.parse::<std::net::IpAddr>().is_ok() {
        ServerName::IpAddress(
            host.parse::<std::net::IpAddr>()
                .expect("valid ip")
                .into(),
        )
    } else {
        ServerName::DnsName(host.clone().try_into().expect("dns name"))
    };

    let config = build_config(enable_sni);
    println!("握手目标: {}:{} enable_sni={}", host, port, enable_sni);

    let mut conn = ClientConnection::new(config, server_name).expect("conn");
    let mut sock = TcpStream::connect((host.as_str(), port)).expect("connect");

    // 驱动握手，最多 30 秒
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(30);
    while conn.is_handshaking() {
        if conn.wants_write() {
            conn.write_tls(&mut sock).expect("write_tls");
        }
        if conn.wants_read() {
            match conn.read_tls(&mut sock) {
                Ok(0) => {
                    println!("握手未完成：对端关闭连接");
                    return;
                }
                Ok(_) => {
                    conn.process_new_packets().expect("process_new_packets");
                }
                Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {}
                Err(e) => {
                    println!("read_tls 失败: {}", e);
                    return;
                }
            }
            sock.flush().ok();
        }
        if std::time::Instant::now() > deadline {
            println!("握手超时");
            return;
        }
    }

    println!("握手完成");
    if let Some(alpn) = conn.alpn_protocol() {
        println!("ALPN: {:?}", String::from_utf8_lossy(alpn));
    }
    println!("协商协议: {:?}", conn.protocol_version());
    println!("对端证书数量: {}", conn.peer_certificates().map(|c| c.len()).unwrap_or(0));
    if let Some(certs) = conn.peer_certificates() {
        if let Some(first) = certs.first() {
            println!("叶证书长度: {} 字节", first.len());
        }
    }
}
