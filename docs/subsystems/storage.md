# Storage

English | [中文](storage.zh.md)

## Purpose

The storage subsystem provides durable credentials, preferences, browsing history, search history, and download queue state for the macOS application.

## Ownership and dependencies

SQLDelight schema files live under `store/src/main/sqldelight/ceui/pixiv/store`. [`Database`](../../store/src/main/kotlin/ceui/pixiv/store/Database.kt) creates the SQLite driver and exposes generated queries plus typed stores.

`KeychainKv` and [`KeychainTokenStore`](../../store/src/main/kotlin/ceui/pixiv/store/KeychainTokenStore.kt) own credential persistence. `PreferencesKv` and [`SettingsStore`](../../store/src/main/kotlin/ceui/pixiv/store/SettingsStore.kt) own non-secret application settings.

## Core concepts

The database file is `~/Library/Application Support/PixivShaft/shaft.db`. It contains browsing history, search history, and the persistent download queue. Download queue records use explicit kind and status values so the coordinator can recover interrupted work after restart.

Preferences store UI layout settings, reader settings, theme settings, R18 visibility, image host selection, direct-connect settings, download path, and filename templates. Values are clamped or validated by `SettingsStore` before they enter its state flows.

Keychain records use the `PixivShaft` service and store access token, refresh token, and optional user JSON values. The application never stores these credentials in SQLDelight or ordinary Preferences.

## API and extension points

Add durable relational data through a SQLDelight schema and its generated queries, then wrap it in a typed store under `store/src/main/kotlin/ceui/pixiv/store`. Add scalar user settings to `SettingsStore` with a validated default and a state flow when the UI needs live updates.

Schema changes must include the corresponding initialization or compatibility handling in `Database.kt`. Keep secrets behind `KvStore` implementations and do not expose Keychain process details to application screens.

## Failure behavior

Database initialization creates the parent directory and applies the SQLDelight schema. The current download queue compatibility code treats an already-present column as an expected repeat operation and rethrows other SQL errors.

Keychain command failures are treated as missing or unsuccessful credential operations. The application then remains logged out or reports the workflow error instead of using a partially read token.

## Verification

Run `./gradlew test` for SQLDelight stores, settings, history, and download persistence tests. Manual verification should cover application restart, login state restoration, browse history, search history, and recovery of a queued or interrupted download.
