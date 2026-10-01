//! 验证授权令牌端点经反墙通路可达。
//!
//! 令牌端点无法从国内直连。这个探针用无效刷新令牌走生产通路发一次真实请求：
//! 服务端返回业务错误（`invalid_grant`）说明通路可达，传输层报错则说明通路仍不通。
//! 无效令牌不会让服务端签发新令牌，真实登录态不受影响。

#[tokio::main]
async fn main() {
    let error = pixiv_core::auth::refresh_token("invalid-refresh-token-for-transport-probe")
        .await
        .expect_err("无效刷新令牌不应换回令牌");

    let report = format!("令牌端点返回的业务错误：{error}\n");
    print!("{report}");
    std::fs::write("rust/core/target/oauth-probe.txt", report).expect("写入授权端点验证记录");

    assert!(
        error.starts_with("token 交换失败"),
        "令牌端点没有返回业务错误，通路可能仍不可用：{error}"
    );
}
