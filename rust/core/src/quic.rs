//! QUIC（HTTP/3）传输。
//!
//! ECH 链路不可用时走这条。QUIC 走 UDP，不经过 TCP 那一层的 SNI 检测，
//! 因此在国内可以直连。Cloudflare 要求发送 SNI（不发时握手直接失败），
//! 这里开着 SNI 并正常校验证书。

use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

use bytes::Buf;
use quinn::crypto::rustls::QuicClientConfig;
use reqwest::header::HeaderMap;

/// app-api 的 Cloudflare 地址，与现有版本 `PixivHosts.CF_IPS` 一致。
const CF_IPS: [&str; 2] = ["104.18.42.239", "172.64.145.17"];
const AUTHORITY: &str = "app-api.pixiv.net";

fn client_config() -> Result<quinn::ClientConfig, String> {
    let mut roots = rustls::RootCertStore::empty();
    roots.add_parsable_certificates(webpki_root_certs::TLS_SERVER_ROOT_CERTS.to_vec());

    let mut tls = rustls::ClientConfig::builder_with_provider(Arc::new(
        rustls::crypto::ring::default_provider(),
    ))
    .with_protocol_versions(&[&rustls::version::TLS13])
    .map_err(|e| format!("装配 TLS 1.3 失败：{e}"))?
    .with_root_certificates(roots)
    .with_no_client_auth();
    tls.enable_sni = true;
    tls.alpn_protocols = vec![b"h3".to_vec()];

    let quic = QuicClientConfig::try_from(tls).map_err(|e| format!("装配 QUIC 配置失败：{e}"))?;
    let mut config = quinn::ClientConfig::new(Arc::new(quic));

    let mut transport = quinn::TransportConfig::default();
    transport.max_idle_timeout(Some(
        Duration::from_secs(30)
            .try_into()
            .map_err(|e| format!("空闲超时设置失败：{e}"))?,
    ));
    transport.initial_mtu(1200);
    config.transport_config(Arc::new(transport));
    Ok(config)
}

/// 经 QUIC 发起一次 GET，返回状态码与响应体。
pub async fn get(path: &str, headers: HeaderMap) -> Result<(u16, String), String> {
    let mut last_error = String::from("QUIC 地址列表为空");
    for ip in CF_IPS {
        match get_via(ip, path, headers.clone()).await {
            Ok(result) => return Ok(result),
            Err(error) => last_error = format!("{ip}：{error}"),
        }
    }
    Err(format!("QUIC 请求 {path} 失败：{last_error}"))
}

async fn get_via(ip: &str, path: &str, headers: HeaderMap) -> Result<(u16, String), String> {
    let remote: SocketAddr = format!("{ip}:443")
        .parse()
        .map_err(|e| format!("地址 {ip} 不合法：{e}"))?;
    let bind: SocketAddr = "0.0.0.0:0"
        .parse()
        .map_err(|e| format!("本地绑定地址不合法：{e}"))?;

    let mut endpoint = quinn::Endpoint::client(bind).map_err(|e| format!("建立端点失败：{e}"))?;
    endpoint.set_default_client_config(client_config()?);

    let connecting = endpoint
        .connect(remote, AUTHORITY)
        .map_err(|e| format!("发起连接失败：{e}"))?;
    let connection = tokio::time::timeout(Duration::from_secs(20), connecting)
        .await
        .map_err(|_| "连接超时".to_string())?
        .map_err(|e| format!("连接失败：{e}"))?;

    let (mut driver, mut send_request) = h3::client::new(h3_quinn::Connection::new(connection))
        .await
        .map_err(|e| format!("HTTP/3 握手失败：{e}"))?;

    // h3 要求持续驱动连接，否则请求无法推进。
    let drive = tokio::spawn(async move {
        let _: h3::error::ConnectionError =
            std::future::poll_fn(|cx| driver.poll_close(cx)).await;
    });

    let result = send(path, &headers, &mut send_request).await;

    drive.abort();
    endpoint.wait_idle().await;
    result
}

async fn send(
    path: &str,
    headers: &HeaderMap,
    send_request: &mut h3::client::SendRequest<h3_quinn::OpenStreams, bytes::Bytes>,
) -> Result<(u16, String), String> {
    let mut builder = http::Request::builder()
        .method("GET")
        .uri(format!("https://{AUTHORITY}{path}"))
        .header("host", AUTHORITY);
    for (name, value) in headers {
        // reqwest 自己管理的头交给 QUIC 这一层自己决定。
        let lower = name.as_str().to_ascii_lowercase();
        if matches!(
            lower.as_str(),
            "host" | "connection" | "content-length" | "transfer-encoding" | "accept-encoding"
        ) {
            continue;
        }
        builder = builder.header(name, value);
    }
    let request = builder
        .body(())
        .map_err(|e| format!("构造请求失败：{e}"))?;

    let mut stream = send_request
        .send_request(request)
        .await
        .map_err(|e| format!("发起请求流失败：{e}"))?;
    stream
        .finish()
        .await
        .map_err(|e| format!("结束请求失败：{e}"))?;

    let response = stream
        .recv_response()
        .await
        .map_err(|e| format!("读取响应头失败：{e}"))?;
    let status = response.status().as_u16();

    let mut body = Vec::new();
    loop {
        match stream.recv_data().await {
            Ok(Some(mut chunk)) => {
                let remaining = chunk.remaining();
                body.extend_from_slice(chunk.copy_to_bytes(remaining).as_ref());
            }
            Ok(None) => break,
            Err(e) => return Err(format!("读取响应体失败：{e}")),
        }
    }

    String::from_utf8(body)
        .map(|text| (status, text))
        .map_err(|e| format!("响应体不是合法文本：{e}"))
}
