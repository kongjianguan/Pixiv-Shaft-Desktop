# Agent Note: OAuth token transport

Status: implemented

English | [中文](2026-10-02-oauth-token-transport.zh.md)

## Problem

The OAuth token endpoint `oauth.secure.pixiv.net` was reached through a dedicated `reqwest` client that connected directly and relied on the macOS system proxy only when no proxy environment variable was set. On mainland China networks that client cannot reach the endpoint at all: the connection attempt times out, and with the system proxy disabled every refresh fails. The failure surfaces at the first authenticated request of the home feed, which reports `请求 token 端点失败：error sending request for url (https://oauth.secure.pixiv.net/auth/token)`.

## Decision

Token requests travel the same transports as API requests. `transport::request` targets the ECH path first (encrypted SNI over TCP) and falls back to QUIC/HTTP3, and both `ech::request` and `quic::request` take the target host as a parameter. The token endpoint host is already resolved to Cloudflare addresses in the ECH client's host list. `auth::post_token` builds the form body, sets the form content type, and posts `/auth/token` on `transport::OAUTH_HOST`; the dedicated client and the `system-proxy` reqwest feature are gone. `api_client::request` calls the same transport function.

## Alternatives considered

**Keep the macOS system-proxy route.** That is the decision recorded in [2026-09-27-oauth-refresh](2026-09-27-oauth-refresh.md). It works only while a system proxy is enabled and reachable, gives the token request no fallback, and differs from the route every other authenticated request already uses.

**Build a second ECH client for the token endpoint.** The ECH client already resolves the token host and holds one shared ECH configuration; a separate client would duplicate both the configuration lookup and its cache.

## Consequences

Token refresh works without a system proxy and shares the two anti-blocking transports with API requests. A rejected ECH configuration is retried with a fresh lookup before the request falls through to QUIC, and a failure names both transports. The refresh request keeps the connection and request bounds of those transports.

## Verification

`oauth_probe` runs the production `auth::refresh_token` path with an invalid refresh token and requires the business error `400 invalid_grant`. `feed_probe` restores the Keychain session and loads the four home feeds. `profile_flow_probe` completes a real refresh, writes the new credentials back to the Keychain, and reads the profile, both bookmark lists, and the published works. All three pass on a mainland China network.

## Related

[2026-09-27-oauth-refresh](2026-09-27-oauth-refresh.md) keeps the decisions that remain in force: the OAuth user ID is accepted as a number or numeric string, and a refresh failure carries its cause to the caller.
