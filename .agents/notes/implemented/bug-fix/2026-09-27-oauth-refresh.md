# Agent Note: macOS OAuth refresh transport

Status: implemented

English | [中文](2026-09-27-oauth-refresh.zh.md)

## Problem

The macOS system proxy can be the only working route to Pixiv's OAuth token host. The Rust token client previously relied on process proxy environment variables, which a Finder-launched application need not have. An OAuth refresh response can also encode the user ID as a JSON string. Losing the exact refresh error makes an expired access token appear to be a general login failure.

## Decision

The existing `reqwest` client enables its macOS system-proxy feature for the OAuth token request. ECH configuration lookup, ECH API transport, and the image CDN client explicitly bypass proxies to preserve their direct connection behavior. The token client has bounded connection and request timeouts. `serde` accepts the OAuth user ID as a number or numeric string. Access-token refresh returns an error with its cause, and credential persistence errors propagate to the caller.

## Alternatives considered

**Depend on shell proxy variables.** Finder does not inherit a terminal session's proxy configuration, so application behavior would depend on its launch path.

**Read `scutil --proxy` and assemble a proxy URL in application code.** `reqwest` already provides the operating-system proxy integration. A second parser would duplicate its platform behavior.

## Consequences

OAuth refresh follows macOS proxy settings when process variables do not define a proxy. API and image requests keep their existing ECH, QUIC, and direct CDN routes. An inaccessible token endpoint, malformed OAuth response, or failed Keychain write produces a specific error at the request that needed refresh.

## Verification

On a Mac with an active system proxy, the authenticated profile probe restores the Keychain session and completes a real token refresh with proxy environment variables removed. The probe then fetches the profile, avatar, bookmark lists, published works, and author bookmarks. Rust compilation and the macOS build verify the client configuration.

## Related

[2026-10-02-oauth-token-transport](2026-10-02-oauth-token-transport.md) replaces the system-proxy route with the anti-blocking transports that API requests use.
