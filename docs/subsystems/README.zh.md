# 子系统

[English](README.md) | 中文

本目录描述连接 PixivShaft Desktop 模块的运行时边界和契约。它是所属参考页面的索引，不是任意包摘要的集合。

| 页面 | 负责内容 |
|---|---|
| [application](application.zh.md) | 桌面应用启动、认证门、Voyager 导航和 macOS 平台集成。 |
| [network](network.zh.md) | Retrofit 客户端、OkHttp 拦截器、ECH、QUIC 和图片请求路由。 |
| [authentication](authentication.zh.md) | Pixiv OAuth PKCE、token 存储交接和 access token 刷新。 |
| [storage](storage.zh.md) | SQLDelight 数据、macOS Keychain 凭据和用户偏好设置。 |
| [downloads](downloads.zh.md) | 持久化下载队列、任务生命周期、可恢复文件和导出格式。 |

## 每个子系统一页

当一个子系统拥有独立的术语、边界、生命周期、扩展点或失败模式时，为它创建页面。使用稳定名称，在上面的表格中登记，并从 `docs/architecture.md` 链接到该页面。

每个页面 SHOULD 回答以下问题：

```markdown
# <Subsystem>

## Purpose

State the responsibility and the boundary.

## Ownership and dependencies

State who owns the boundary and which dependencies it may call.

## Core concepts

Define the types, states, and invariants that readers must share.

## API and extension points

Describe the supported entry points and the rules for extending them.

## Failure behavior

Describe errors, recovery, and observable diagnostics.

## Verification

List the focused tests, commands, or generated checks that establish the contract.
```

## 与源码等价的事实

类型签名、公开选项、生成的 API 区域以及其他源代码派生事实 MUST 有源码所有者。链接到生成的参考资料，或从源码声明重新生成。不要在普通 prose 中手工维护第二份签名目录。

## 边界

明确描述依赖方向和所有权。子系统页面 MAY 链接到 Agent Note 了解理由，但当前契约属于此处或它所描述的源码。

## 审查标准

页面应足够精确，能够被验证，也应足够短，便于扫描。如果页面包含多个不相关的边界或读者群体，就拆分页面。新增、删除或重命名子系统后更新架构地图。
