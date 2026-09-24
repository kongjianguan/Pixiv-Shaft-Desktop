use h3_verify::accept_all::AcceptAllVerifier;

use std::net::{SocketAddr, ToSocketAddrs};
use std::sync::Arc;
use std::time::Duration;


use bytes::{Buf, Bytes};
use h3::client::Connection;
use h3_quinn::quinn::crypto::rustls::QuicClientConfig;
use rustls::crypto::ring::default_provider;
use rustls::pki_types::ServerName;

fn build_quic_config() -> Result<h3_quinn::quinn::ClientConfig, String> {
    let mut tls = rustls::ClientConfig::builder_with_provider(Arc::new(default_provider()))
        .with_protocol_versions(&[&rustls::version::TLS13])
        .map_err(|e| format!("tls versions: {e}"))?
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(AcceptAllVerifier))
        .with_no_client_auth();
    // QUIC 走 Cloudflare，必须发 SNI（实测不发 SNI 时 CF 直接 handshake_failure）
    tls.enable_sni = true;
    tls.alpn_protocols = vec![b"h3".to_vec()];

    let quic = QuicClientConfig::try_from(tls).map_err(|e| format!("quic config: {e}"))?;
    let mut cfg = h3_quinn::quinn::ClientConfig::new(Arc::new(quic));
    let mut transport = h3_quinn::quinn::TransportConfig::default();
    transport.max_idle_timeout(Some(Duration::from_secs(30).try_into().map_err(|e| format!("{e}"))?));
    transport.initial_mtu(1200);
    cfg.transport_config(Arc::new(transport));
    Ok(cfg)
}

#[tokio::main]
async fn main() {
    // 对 app-api.pixiv.net 的 Cloudflare IP 发起 QUIC(HTTP/3) 请求
    let remote: SocketAddr = "104.18.42.239:443"
        .to_socket_addrs()
        .expect("resolve")
        .next()
        .expect("addr");
    // 连接目标 IP，但 SNI 保持真实域名（与 NettyQuicInterceptor 一致）
    let bind: SocketAddr = "0.0.0.0:0".parse().unwrap();
    let endpoint = h3_quinn::quinn::Endpoint::client(bind).expect("endpoint");
    let cfg = match build_quic_config() {
        Ok(c) => c,
        Err(e) => {
            println!("配置构建失败: {e}");
            return;
        }
    };

    println!("QUIC {remote} server_name=app-api.pixiv.net (发 SNI)");
    let connecting = match endpoint.connect_with(cfg, remote, "app-api.pixiv.net") {
        Ok(c) => c,
        Err(e) => {
            println!("  connect 调用失败: {e}");
            endpoint.wait_idle().await;
            return;
        }
    };
    let conn = match tokio::time::timeout(Duration::from_secs(25), connecting).await {
        Ok(Ok(c)) => c,
        Ok(Err(e)) => {
            println!("  连接失败: {e}");
            endpoint.wait_idle().await;
            return;
        }
        Err(_) => {
            println!("  连接超时（QUIC 被阻断或 UDP 不通）");
            endpoint.wait_idle().await;
            return;
        }
    };
    println!("  QUIC 连接建立: {:?}", conn.remote_address());

    let (mut driver, mut send_request) = match h3::client::new(h3_quinn::Connection::new(conn)).await {
        Ok(v) => v,
        Err(e) => {
            println!("  HTTP/3 连接失败: {e}");
            return;
        }
    };

    // h3 0.0.8 要求驱动连接 fut 才能推进
    let drive = tokio::spawn(async move {
        let err: h3::error::ConnectionError = std::future::poll_fn(|cx| driver.poll_close(cx)).await;
        println!("  driver 结束: {err}");
    });

    let req = http::Request::builder()
        .method("GET")
        .uri("https://app-api.pixiv.net/v1/illust/ranking?mode=day&date=2026-09-01")
        .header("host", "app-api.pixiv.net")
        .header("user-agent", "PixivIOSApp/8.6.10 (iOS 26.5; iPhone16,2)")
        .header("app-os", "ios")
        .header("app-os-version", "26.5")
        .header("app-version", "8.6.10")
        .body(())
        .expect("request");

    match send_request.send_request(req).await {
        Ok(mut stream) => match stream.finish().await {
            Ok(_) => match stream.recv_response().await {
                Ok(resp) => {
                    println!("  HTTP/3 响应状态: {}", resp.status());
                    for (k, v) in resp.headers() {
                        let vv = v.to_str().unwrap_or("<binary>");
                        let vv: String = vv.chars().take(80).collect();
                        println!("    {k}: {vv}");
                    }
                    let mut body = Vec::new();
                    loop {
                        match stream.recv_data().await {
                            Ok(Some(mut chunk)) => {
                                let n = chunk.remaining();
                                let b = chunk.copy_to_bytes(n);
                                body.extend_from_slice(b.as_ref());
                            }
                            Ok(None) => break,
                            Err(e) => {
                                println!("  body 读取失败: {e}");
                                break;
                            }
                        }
                    }
                    let preview: String = String::from_utf8_lossy(&body).chars().take(200).collect();
                    println!("  响应体字节数: {}", body.len());
                    println!("  预览: {preview}");
                    let _: Option<Bytes> = None;
                }
                Err(e) => println!("  读取响应失败: {e}"),
            },
            Err(e) => println!("  发送请求体失败: {e}"),
        },
        Err(e) => println!("  发起流失败: {e}"),
    }

    drive.abort();
    endpoint.wait_idle().await;
    let _: Option<ServerName<'static>> = None;
}
