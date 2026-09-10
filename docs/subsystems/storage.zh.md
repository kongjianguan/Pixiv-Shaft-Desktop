# 存储

[English](storage.md) | 中文

## 职责

存储子系统为 macOS 应用提供持久化凭据、偏好设置、浏览历史、搜索历史和下载队列状态。

## 所有权与依赖

SQLDelight schema 文件位于 `store/src/main/sqldelight/ceui/pixiv/store`。[`Database`](../../store/src/main/kotlin/ceui/pixiv/store/Database.kt) 创建 SQLite driver，并暴露生成的 queries 和类型化 store。

`KeychainKv` 和 [`KeychainTokenStore`](../../store/src/main/kotlin/ceui/pixiv/store/KeychainTokenStore.kt) 负责凭据持久化。`PreferencesKv` 和 [`SettingsStore`](../../store/src/main/kotlin/ceui/pixiv/store/SettingsStore.kt) 负责非机密应用设置。

## 核心概念

数据库文件是 `~/Library/Application Support/PixivShaft/shaft.db`。其中包含浏览历史、搜索历史和持久化下载队列。下载队列记录使用明确的 kind 和 status 值，使协调器可以在重启后恢复中断任务。

Preferences 保存 UI 布局设置、阅读器设置、主题设置、R18 可见性、图片主机选择、直连设置、下载路径和文件名模板。`SettingsStore` 会在值进入其 state flow 前完成范围限制或验证。

Keychain 记录使用 `PixivShaft` service，并保存 access token、refresh token 和可选的用户 JSON 值。应用不会把这些凭据存入 SQLDelight 或普通 Preferences。

## API 与扩展点

通过 SQLDelight schema 和生成的 queries 添加持久化关系数据，然后在 `store/src/main/kotlin/ceui/pixiv/store` 下用类型化 store 封装。向 `SettingsStore` 添加标量用户设置时，提供经过验证的默认值；如果 UI 需要实时更新，还要提供 state flow。

Schema 变化必须同时更新 `Database.kt` 中对应的初始化或兼容处理。机密信息必须隐藏在 `KvStore` 实现后，不要把 Keychain 进程细节暴露给应用 screen。

## 失败行为

数据库初始化会创建父目录并应用 SQLDelight schema。当前下载队列兼容代码把已存在的列视为预期的重复操作，并重新抛出其他 SQL 错误。

Keychain 命令失败会被视为凭据缺失或操作失败。应用随后保持登出状态，或报告工作流错误，不会使用部分读取的 token。

## 验证

使用 `./gradlew test` 运行 SQLDelight store、设置、历史和下载持久化测试。手动验证应覆盖应用重启、登录状态恢复、浏览历史、搜索历史，以及排队或中断下载的恢复。
