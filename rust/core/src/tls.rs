//! TLS 客户端配置。
//!
//! 本项目需要两种截然不同的 TLS 参数：
//! - 图片 CDN（pximg.net）**必须不发送** SNI，否则连接被重置
//! - API 与授权域名（Cloudflare 承载）**必须发送** SNI，不发则握手失败

use std::sync::Arc;

use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use rustls::{ClientConfig, DigitallySignedStruct, SignatureScheme};

/// 接受任意服务器证书。不发 SNI 时无法把证书里的名称与目标域名比对，
/// 因此校验必然放行。等价于 Kotlin 侧的 TrustAllCertManager。
#[derive(Debug)]
struct AcceptAnyServerCert;

impl ServerCertVerifier for AcceptAnyServerCert {
    fn verify_server_cert(
        &self,
        _end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, rustls::Error> {
        Ok(ServerCertVerified::assertion())
    }

    fn verify_tls12_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        Ok(HandshakeSignatureValid::assertion())
    }

    fn verify_tls13_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        Ok(HandshakeSignatureValid::assertion())
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        rustls::crypto::ring::default_provider()
            .signature_verification_algorithms
            .supported_schemes()
    }
}

/// 构造客户端 TLS 配置。`enable_sni` 为 false 时不发送 SNI 扩展。
///
/// 注意：传入 `ServerName::IpAddress` 本身就不会发送 SNI，因此比较实验必须
/// 用域名连接，否则对照组失效。
pub fn client_config(enable_sni: bool) -> Arc<ClientConfig> {
    let mut config = ClientConfig::builder_with_provider(Arc::new(
        rustls::crypto::ring::default_provider(),
    ))
    .with_protocol_versions(rustls::DEFAULT_VERSIONS)
    .expect("rustls 默认协议版本可用")
    .dangerous()
    .with_custom_certificate_verifier(Arc::new(AcceptAnyServerCert))
    .with_no_client_auth();

    config.enable_sni = enable_sni;
    Arc::new(config)
}
