# Agent Note: OAuth token transport

Status: implemented

[English](2026-10-02-oauth-token-transport.md) | 中文

## Problem

授权令牌端点 `oauth.secure.pixiv.net` 此前通过一个专用的 `reqwest` 客户端直连，只有在进程里没有代理环境变量时才使用 macOS 系统代理。在中国大陆网络下这个客户端根本到不了端点：连接尝试超时，系统代理没有启用时每次刷新都失败。失败出现在首页第一个带凭据的请求上，报出 `请求 token 端点失败：error sending request for url (https://oauth.secure.pixiv.net/auth/token)`。

## Decision

令牌请求和接口请求使用同一组通路。`transport::request` 先走 ECH（加密 SNI 的 TCP 直连），再回退到 QUIC（HTTP/3），`ech::request` 与 `quic::request` 都把目标主机作为参数。令牌端点主机已经在 ECH 客户端的主机列表里解析到 Cloudflare 地址。`auth::post_token` 组装表单请求体、设置表单内容类型，并通过 `transport::OAUTH_HOST` 请求 `/auth/token`；专用客户端与 `reqwest` 的 `system-proxy` 功能一并删除。`api_client::request` 调用同一个通路函数。

## Alternatives considered

**保留 macOS 系统代理路线。** 这是 [2026-09-27-oauth-refresh](2026-09-27-oauth-refresh.zh.md) 记录的决策。它只在系统代理启用且可达时有效，令牌请求没有回退路径，而且与其余所有带凭据请求使用的路线不一致。

**为令牌端点单独建一个 ECH 客户端。** ECH 客户端已经解析了令牌主机并持有一份共享的 ECH 配置；另建客户端会重复配置查询和它的缓存。

## Consequences

令牌刷新不再依赖系统代理，与接口请求共用两条反墙通路。ECH 配置被服务端拒绝时会重新取一份配置重试，之后才回退到 QUIC，失败信息会同时给出两条通路的原因。刷新请求沿用这两条通路的连接与请求超时。

## Verification

`oauth_probe` 用无效刷新令牌走生产的 `auth::refresh_token` 路径，要求得到业务错误 `400 invalid_grant`。`feed_probe` 从钥匙串恢复登录状态并加载首页四条作品流。`profile_flow_probe` 完成一次真实刷新、把新凭据写回钥匙串，并读取个人资料、两类收藏与已发表作品。三条探针在中国大陆网络下全部通过。

## Related

[2026-09-27-oauth-refresh](2026-09-27-oauth-refresh.zh.md) 保留了仍然有效的决策：授权用户编号接受数字或数字字符串，刷新失败把具体原因交给调用方。
