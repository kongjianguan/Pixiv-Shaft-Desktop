# Pixiv-Shaft macOS 移植 — Plan 3: 存储 + 鉴权 + 图片加载

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 补持久化（SQLDelight + Keychain + Preferences）、OAuth PKCE 登录（替 pixiv-login 库）、Coil 3 图片加载（含 pximg 反墙客户端），使 app 能登录拿 token、读写本地 DB、显示 pximg 图片。

**Architecture:** `:store` 填实（SQLDelight driver + .sq schema + KvStore 接口 + Keychain/Preferences + KeychainTokenStore 实现 :net TokenStore）；`:net` 加 `auth/`（PKCE + callback server + token exchange + RealTokenRefresher）；`:app` 加 Coil 3 ImageLoader + 反墙图片 OkHttpClient。依赖链 `:app → :store → :net → :models`。

**Tech Stack:** Kotlin 2.1.20、JDK 21、SQLDelight 2.0.2（JdbcSqliteDriver）、Coil 3.1.0、OkHttp 4.12.0、netty 4.2.2.Final、com.sun.net.httpserver、java.util.prefs、`security` CLI（Keychain）。

## Global Constraints

- macOS Apple Silicon（aarch64）。仓库 `~/workspace/git_repo/Pixiv-Shaft-Desktop`（Plan 2 HEAD `d0a9d40`）。
- **每个 `./gradlew` 前缀 `JAVA_HOME=/opt/homebrew/opt/openjdk@21`**。大陆镜像沿用（Aliyun public/central/google/gradle-plugin）。
- netty SNI 关键：`.sslEngineProvider { q -> ctx.newEngine(q.alloc(), host, port) }` 不动。
- **OAuth**: client_id=`MOBrBDS8blbauoSck0ZfDbtuzpyT`，client_secret=`lsACyCD94FhDUtGTXi3QzcFE2uU1hqtDaKeqrdwj`，redirect_uri=`https://app-api.pixiv.net/web/v1/users/auth/pixiv/callback`，token endpoint=`https://oauth.secure.pixiv.net/auth/token`（form POST 经 QUIC）。
- x-client-hash secret=`28c1fdd170a5204386cb1313c7077b34f83e4aaf4aa829ce78c231e05b0bae2c`。
- DB=`~/Library/Application Support/PixivShaft/shaft.db`；图片缓存=`~/Library/Caches/PixivShaft/images/`（256MB）。
- 不引入 Room/MMKV/pixiv-login/Glide/RxJava。源仓 `~/workspace/git_repo/Pixiv-Shaft`（不修改）。
- Port 任务：源文件即权威 spec + strip 清单。

## File Structure

- `store/build.gradle.kts` — SQLDelight plugin + sqlite-driver + :models/:net 依赖。
- `store/src/main/sqldelight/ceui/pixiv/store/` — .sq schema（IllustHistory.sq、SearchHistory.sq、RemoteKey.sq）。
- `store/src/main/kotlin/ceui/pixiv/store/` — Database、KvStore、PreferencesKv、KeychainKv、KeychainTokenStore、SettingsStore。
- `net/src/main/kotlin/ceui/pixiv/net/auth/` — PixivOAuthConfig、PkceUtils、OAuthCallbackServer、TokenExchange、RealTokenRefresher。
- `app/src/main/kotlin/ceui/pixiv/poc/PocMain.kt` — Plan 3 闸门（Task 12 改写）。
- `app/build.gradle.kts` — 加 Coil 3 依赖。

---

### Task 1: SQLDelight driver + gradle setup

**Files:** Modify `store/build.gradle.kts`；Create `store/src/main/kotlin/ceui/pixiv/store/Database.kt`

**Interfaces:** Produces `Database(driver)` + `createDatabase()` 工厂。

- [ ] **Step 1: 改 `store/build.gradle.kts`**

```kotlin
plugins {
    kotlin("jvm")
    id("app.cash.sqldelight") version "2.0.2"
}
group = "ceui.pixiv"; version = "0.0.1"
java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
repositories {
    maven { url = uri("https://maven.aliyun.com/repository/public") }
    maven { url = uri("https://maven.aliyun.com/repository/central") }
    maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
    maven { url = uri("https://maven.aliyun.com/repository/google") }
    mavenCentral(); gradlePluginPortal()
}
dependencies {
    implementation(project(":models"))
    implementation(project(":net"))
    implementation("app.cash.sqldelight:sqlite-driver:2.0.2")
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}
tasks.test { useJUnitPlatform() }
sqldelight {
    databases { create("ShaftDatabase") { packageName.set("ceui.pixiv.store") } }
}
```

> SQLDelight 2.0.2 可能与 Kotlin 2.1.20 不兼容；若报错换 `2.1.0` 或最新 `2.x`。

- [ ] **Step 2: 写 `Database.kt`**（此时无 .sq，`ShaftDatabase` 尚未生成——先写骨架，Task 2 加 .sq 后补 `Schema.create`）

```kotlin
package ceui.pixiv.store

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.JdbcSqliteDriver
import java.nio.file.Path

class Database(val driver: SqlDriver)

fun createDatabase(): Database {
    val dbPath = Path.of(System.getProperty("user.home"),
        "Library/Application Support/PixivShaft/shaft.db")
    dbPath.parent.toFile().mkdirs()
    val driver = JdbcSqliteDriver("jdbc:sqlite:${dbPath}")
    return Database(driver)
}
```

- [ ] **Step 3: 验证 + commit**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :store:compileKotlin --no-daemon
git add -A && git commit -m "feat(store): SQLDelight driver + gradle setup"
```

---

### Task 2: Core tables .sq + schema create

**Files:** Create `store/src/main/sqldelight/ceui/pixiv/store/{IllustHistory.sq,SearchHistory.sq,RemoteKey.sq}`；Modify `Database.kt`（补 `ShaftDatabase.Schema.create(driver)` + 暴露 queries）

**Interfaces:** Produces `Database.queries: ShaftDatabase`（SQLDelight 生成的 queries 对象）。Port 源：`ceui.lisa.database.IllustHistoryEntity.java`（illust_table: illustID PK, illustJson, time, type）、`SearchEntity.java`（search_table: id PK, keyword, searchTime, searchType, pinned, previewIllustsJson）、`ceui.pixiv.db.RemoteKey.kt`（remote_keys: recordType PK, nextPageUrl, lastUpdatedTime）。

- [ ] **Step 1: 写 `IllustHistory.sq`**

```sql
CREATE TABLE illust_table (
    illustID INTEGER NOT NULL PRIMARY KEY,
    illustJson TEXT NOT NULL,
    time INTEGER NOT NULL,
    type INTEGER NOT NULL
);

insertIllust:
INSERT INTO illust_table(illustID, illustJson, time, type) VALUES (?, ?, ?, ?)
ON CONFLICT(illustID) DO UPDATE SET illustJson=excluded.illustJson, time=excluded.time, type=excluded.type;

selectRecentIllusts:
SELECT * FROM illust_table ORDER BY time DESC LIMIT ?;

deleteIllust:
DELETE FROM illust_table WHERE illustID = ?;
```

- [ ] **Step 2: 写 `SearchHistory.sq`**

```sql
CREATE TABLE search_table (
    id INTEGER NOT NULL PRIMARY KEY,
    keyword TEXT NOT NULL,
    searchTime INTEGER NOT NULL,
    searchType INTEGER NOT NULL,
    pinned INTEGER NOT NULL DEFAULT 0,
    previewIllustsJson TEXT
);

insertSearch:
INSERT INTO search_table(id, keyword, searchTime, searchType, pinned, previewIllustsJson) VALUES (?, ?, ?, ?, ?, ?)
ON CONFLICT(id) DO UPDATE SET keyword=excluded.keyword, searchTime=excluded.searchTime, searchType=excluded.searchType, pinned=excluded.pinned, previewIllustsJson=excluded.previewIllustsJson;

selectRecentSearches:
SELECT * FROM search_table ORDER BY pinned DESC, searchTime DESC LIMIT ?;

deleteSearch:
DELETE FROM search_table WHERE id = ?;
```

- [ ] **Step 3: 写 `RemoteKey.sq`**

```sql
CREATE TABLE remote_keys (
    recordType INTEGER NOT NULL PRIMARY KEY,
    nextPageUrl TEXT,
    lastUpdatedTime INTEGER NOT NULL
);

upsertRemoteKey:
INSERT INTO remote_keys(recordType, nextPageUrl, lastUpdatedTime) VALUES (?, ?, ?)
ON CONFLICT(recordType) DO UPDATE SET nextPageUrl=excluded.nextPageUrl, lastUpdatedTime=excluded.lastUpdatedTime;

selectRemoteKey:
SELECT * FROM remote_keys WHERE recordType = ?;
```

- [ ] **Step 4: 改 `Database.kt`（补 Schema.create + queries）**

```kotlin
package ceui.pixiv.store

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.JdbcSqliteDriver
import java.nio.file.Path

class Database(val driver: SqlDriver) {
    val queries: ShaftDatabase by lazy { ShaftDatabase(driver) }
}

fun createDatabase(): Database {
    val dbPath = Path.of(System.getProperty("user.home"),
        "Library/Application Support/PixivShaft/shaft.db")
    dbPath.parent.toFile().mkdirs()
    val driver = JdbcSqliteDriver("jdbc:sqlite:${dbPath}")
    ShaftDatabase.Schema.create(driver)
    return Database(driver)
}
```

- [ ] **Step 5: 写失败测试 `StoreTest.kt`**

```kotlin
package ceui.pixiv.store

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class StoreTest {
    @Test fun `illust history roundtrip`(@TempDir dir: Path) {
        val driver = app.cash.sqldelight.driver.jdbc.JdbcSqliteDriver("jdbc:sqlite:${dir.resolve("t.db")}")
        ShaftDatabase.Schema.create(driver)
        val db = Database(driver)
        db.queries.illustHistoryQueries.insertIllust(48723512, """{"id":48723512}""", 100L, 0)
        val rows = db.queries.illustHistoryQueries.selectRecentIllusts(10).executeAsList()
        assertEquals(1, rows.size)
        assertEquals(48723512, rows[0].illustID)
        db.queries.illustHistoryQueries.deleteIllust(48723512)
        assertTrue(db.queries.illustHistoryQueries.selectRecentIllusts(10).executeAsList().isEmpty())
    }
}
```

- [ ] **Step 6: 跑测试 + commit**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :store:test --no-daemon
git add -A && git commit -m "feat(store): core tables .sq + schema create + roundtrip test"
```

---

### Task 3: KvStore interface + PreferencesKv

**Files:** Create `store/src/main/kotlin/ceui/pixiv/store/{KvStore.kt,PreferencesKv.kt}`；Create `store/src/test/kotlin/ceui/pixiv/store/PreferencesKvTest.kt`

**Interfaces:** Produces `interface KvStore`（getString/putString/remove + getBoolean/getInt/putBoolean/putInt 便利方法）+ `PreferencesKv : KvStore`（`java.util.prefs.Preferences` 实现）+ `PreferencesKv.forApp()` 工厂。

- [ ] **Step 1: 写 `KvStore.kt`**

```kotlin
package ceui.pixiv.store

interface KvStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun getBoolean(key: String, default: Boolean = false): Boolean = getString(key)?.toBoolean() ?: default
    fun getInt(key: String, default: Int = 0): Int = getString(key)?.toIntOrNull() ?: default
    fun putBoolean(key: String, value: Boolean) = putString(key, value.toString())
    fun putInt(key: String, value: Int) = putString(key, value.toString())
    fun remove(key: String)
}
```

- [ ] **Step 2: 写 `PreferencesKv.kt`**

```kotlin
package ceui.pixiv.store

import java.util.prefs.Preferences

class PreferencesKv(private val prefs: Preferences) : KvStore {
    override fun getString(key: String): String? = prefs.get(key, null)
    override fun putString(key: String, value: String) = prefs.put(key, value)
    override fun remove(key: String) = prefs.remove(key)

    companion object {
        fun forApp(): PreferencesKv = PreferencesKv(Preferences.userRoot().node("PixivShaft"))
    }
}
```

- [ ] **Step 3: 写测试 + 跑 + commit**

```kotlin
package ceui.pixiv.store

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PreferencesKvTest {
    @Test fun `string roundtrip`() {
        val kv = PreferencesKv(java.util.prefs.Preferences.userRoot().node("test-${System.nanoTime()}"))
        assertNull(kv.getString("x"))
        kv.putString("x", "hello")
        assertEquals("hello", kv.getString("x"))
        kv.remove("x")
        assertNull(kv.getString("x"))
    }
    @Test fun `boolean and int`() {
        val kv = PreferencesKv(java.util.prefs.Preferences.userRoot().node("test-${System.nanoTime()}"))
        assertFalse(kv.getBoolean("b"))
        kv.putBoolean("b", true)
        assertTrue(kv.getBoolean("b"))
        assertEquals(0, kv.getInt("i"))
        kv.putInt("i", 42)
        assertEquals(42, kv.getInt("i"))
    }
}
```

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :store:test --no-daemon
git add -A && git commit -m "feat(store): KvStore interface + PreferencesKv"
```

---

### Task 4: KeychainKv（macOS Keychain via `security` CLI）

**Files:** Create `store/src/main/kotlin/ceui/pixiv/store/KeychainKv.kt`；Create `store/src/test/kotlin/ceui/pixiv/store/KeychainKvTest.kt`

**Interfaces:** Produces `KeychainKv : KvStore`（shell 到 `security add/find/delete-generic-password`，service 前缀隔离 key）。

- [ ] **Step 1: 写 `KeychainKv.kt`**

```kotlin
package ceui.pixiv.store

class KeychainKv(
    private val service: String = "PixivShaft",
    private val account: String = System.getProperty("user.name"),
) : KvStore {

    override fun getString(key: String): String? = try {
        val proc = ProcessBuilder("security", "find-generic-password",
            "-a", account, "-s", "$service:$key", "-w")
            .redirectErrorStream(true).start()
        val out = proc.inputStream.readBytes().decodeToString().trim()
        proc.waitFor()
        if (proc.exitValue() == 0 && out.isNotEmpty()) out else null
    } catch (e: Exception) { null }

    override fun putString(key: String, value: String) {
        val proc = ProcessBuilder("security", "add-generic-password",
            "-a", account, "-s", "$service:$key", "-w", value, "-U")
            .redirectErrorStream(true).start()
        proc.waitFor()
    }

    override fun remove(key: String) {
        val proc = ProcessBuilder("security", "delete-generic-password",
            "-a", account, "-s", "$service:$key")
            .redirectErrorStream(true).start()
        proc.waitFor()
    }
}
```

- [ ] **Step 2: 写测试 + 跑 + commit**

```kotlin
package ceui.pixiv.store

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

@EnabledOnOs(OS.MAC)
class KeychainKvTest {
    @Test fun `string roundtrip`() {
        val kv = KeychainKv(service = "PixivShaftTest-${System.nanoTime()}")
        assertNull(kv.getString("token"))
        kv.putString("token", "abc123")
        assertEquals("abc123", kv.getString("token"))
        kv.remove("token")
        assertNull(kv.getString("token"))
    }
}
```

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :store:test --no-daemon
git add -A && git commit -m "feat(store): KeychainKv（macOS Keychain via security CLI）"
```

> 注：首次跑测试 macOS 会弹 Keychain 授权弹窗，点"始终允许"。

---

### Task 5: KeychainTokenStore + SettingsStore

**Files:** Create `store/src/main/kotlin/ceui/pixiv/store/{KeychainTokenStore.kt,SettingsStore.kt}`；Create `store/src/test/kotlin/ceui/pixiv/store/{KeychainTokenStoreTest.kt,SettingsStoreTest.kt}`

**Interfaces:** Consumes `:net` 的 `TokenStore`/`Settings`（Plan 2 Task 2）。Produces `KeychainTokenStore : TokenStore`（access/refresh/userJson 存 Keychain）+ `SettingsStore : Settings`（直连/DNS/host 存 Preferences，默认 isDirectConnect=true）。

- [ ] **Step 1: 写 `KeychainTokenStore.kt`**

```kotlin
package ceui.pixiv.store

import ceui.pixiv.net.abstractions.TokenStore

class KeychainTokenStore(
    private val keychain: KeychainKv = KeychainKv(),
) : TokenStore {
    private companion object {
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_USER = "user_json"
    }

    override val isLoggedIn: Boolean get() = keychain.getString(KEY_ACCESS) != null
    override fun getAccessToken(): String? = keychain.getString(KEY_ACCESS)
    override fun getRefreshToken(): String? = keychain.getString(KEY_REFRESH)
    override fun saveTokens(accessToken: String?, refreshToken: String?, userJson: String?) {
        if (accessToken != null) keychain.putString(KEY_ACCESS, accessToken)
        if (refreshToken != null) keychain.putString(KEY_REFRESH, refreshToken)
        if (userJson != null) keychain.putString(KEY_USER, userJson)
    }
    override fun clear() {
        keychain.remove(KEY_ACCESS); keychain.remove(KEY_REFRESH); keychain.remove(KEY_USER)
    }
    fun getUserJson(): String? = keychain.getString(KEY_USER)
}
```

- [ ] **Step 2: 写 `SettingsStore.kt`**

```kotlin
package ceui.pixiv.store

import ceui.pixiv.net.abstractions.Settings

class SettingsStore(
    private val kv: KvStore = PreferencesKv.forApp(),
) : Settings {
    override val isDirectConnect: Boolean get() = kv.getBoolean("isDirectConnect", true)
    override val isUseSecureDns: Boolean get() = kv.getBoolean("isUseSecureDns", false)
    override val imageHostMode: Int get() = kv.getInt("imageHostMode", 0)
    override val customImageHost: String get() = kv.getString("customImageHost") ?: ""
}
```

- [ ] **Step 3: 写测试 + 跑 + commit**

```kotlin
package ceui.pixiv.store

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

class SettingsStoreTest {
    @Test fun `defaults`() {
        val kv = PreferencesKv(java.util.prefs.Preferences.userRoot().node("test-${System.nanoTime()}"))
        val s = SettingsStore(kv)
        assertTrue(s.isDirectConnect)
        assertFalse(s.isUseSecureDns)
        assertEquals(0, s.imageHostMode)
        assertEquals("", s.customImageHost)
    }
}

@EnabledOnOs(OS.MAC)
class KeychainTokenStoreTest {
    @Test fun `token roundtrip`() {
        val store = KeychainTokenStore(KeychainKv(service = "PixivShaftTest-${System.nanoTime()}"))
        assertFalse(store.isLoggedIn)
        store.saveTokens("at", "rt", """{"id":1}""")
        assertTrue(store.isLoggedIn)
        assertEquals("at", store.getAccessToken())
        assertEquals("Bearer at", store.getBearerToken())
        assertEquals("rt", store.getRefreshToken())
        store.clear()
        assertFalse(store.isLoggedIn)
    }
}
```

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :store:test --no-daemon
git add -A && git commit -m "feat(store): KeychainTokenStore + SettingsStore（替 FileTokenStore/InMemorySettings）"
```

---

### Task 6: OAuth config + PKCE utils

**Files:** Create `net/src/main/kotlin/ceui/pixiv/net/auth/{PixivOAuthConfig.kt,PkceUtils.kt}`；Create `net/src/test/kotlin/ceui/pixiv/net/auth/PkceUtilsTest.kt`

**Interfaces:** Produces `PixivOAuthConfig`（client_id/secret/redirect/token endpoint 常量）+ `PkceUtils.generate(): PkcePair`（S256）+ `PkceUtils.buildAuthUrl(challenge): String`。

- [ ] **Step 1: 写 `PixivOAuthConfig.kt`**

```kotlin
package ceui.pixiv.net.auth

object PixivOAuthConfig {
    const val CLIENT_ID = "MOBrBDS8blbauoSck0ZfDbtuzpyT"
    const val CLIENT_SECRET = "lsACyCD94FhDUtGTXi3QzcFE2uU1hqtDaKeqrdwj"
    const val REDIRECT_URI = "https://app-api.pixiv.net/web/v1/users/auth/pixiv/callback"
    const val LOGIN_URL = "https://app-api.pixiv.net/web/v1/login"
    const val TOKEN_ENDPOINT = "https://oauth.secure.pixiv.net/auth/token"
    const val CLIENT_PARAM = "pixiv-android"
}
```

- [ ] **Step 2: 写 `PkceUtils.kt`**

```kotlin
package ceui.pixiv.net.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.net.URLEncoder

data class PkcePair(val verifier: String, val challenge: String)

object PkceUtils {
    fun generate(): PkcePair {
        val random = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(random)
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        return PkcePair(verifier, challenge)
    }

    fun buildAuthUrl(challenge: String): String {
        val params = mapOf(
            "client_id" to PixivOAuthConfig.CLIENT_ID,
            "redirect_uri" to PixivOAuthConfig.REDIRECT_URI,
            "response_type" to "code",
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "client" to PixivOAuthConfig.CLIENT_PARAM,
        )
        val query = params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        return "${PixivOAuthConfig.LOGIN_URL}?$query"
    }
}
```

- [ ] **Step 3: 写测试 + 跑 + commit**

```kotlin
package ceui.pixiv.net.auth

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PkceUtilsTest {
    @Test fun `verifier is 43+ chars url-safe base64`() {
        val (verifier, _) = PkceUtils.generate()
        assertTrue(verifier.length >= 43)
        assertTrue(verifier.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }
    @Test fun `challenge is 43 chars no padding`() {
        val (_, challenge) = PkceUtils.generate()
        assertEquals(43, challenge.length)
        assertFalse(challenge.endsWith("="))
    }
    @Test fun `auth url contains challenge and S256`() {
        val url = PkceUtils.buildAuthUrl("test-challenge")
        assertTrue(url.contains("code_challenge=test-challenge"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("client_id=${PixivOAuthConfig.CLIENT_ID}"))
    }
}
```

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :net:test --no-daemon
git add -A && git commit -m "feat(net/auth): OAuth config + PKCE utils（code_verifier/challenge S256）"
```

---

### Task 7: OAuth callback server

**Files:** Create `net/src/main/kotlin/ceui/pixiv/net/auth/OAuthCallbackServer.kt`；Create `net/src/test/kotlin/ceui/pixiv/net/auth/OAuthCallbackServerTest.kt`

**Interfaces:** Produces `OAuthCallbackServer(port: Int)`（`com.sun.net.httpserver.HttpServer` 监听 `/callback`，捕获 `code` 参数，返回简单 HTML 关闭页，`fun waitForCode(timeoutMs): String?`）。

- [ ] **Step 1: 写 `OAuthCallbackServer.kt`**

```kotlin
package ceui.pixiv.net.auth

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class OAuthCallbackServer(port: Int = 0) : AutoCloseable {
    private val codeFuture = CompletableFuture<String>()
    private val server: HttpServer = HttpServer.create(InetSocketAddress(port), 0).apply {
        createContext("/callback") { exchange ->
            val query = exchange.requestURI.rawQuery ?: ""
            val code = query.split("&")
                .mapNotNull { it.split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
                .firstOrNull { it.first == "code" }?.second
            val resp = if (code != null) {
                codeFuture.complete(code)
                """<html><body><h2>Login successful</h2><p>You can close this window.</p></body></html>"""
            } else {
                """<html><body><h2>Login failed</h2><p>No code received.</p></body></html>"""
            }
            val bytes = resp.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        start()
    }

    val actualPort: Int get() = server.address.port
    val redirectUri: String get() = "http://localhost:$actualPort/callback"

    fun waitForCode(timeoutMs: Long = 120_000): String? =
        codeFuture.get(timeoutMs, TimeUnit.MILLISECONDS)

    override fun close() = server.stop(0)
}
```

- [ ] **Step 2: 写测试 + 跑 + commit**

```kotlin
package ceui.pixiv.net.auth

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.HttpURLConnection
import java.net.URL

class OAuthCallbackServerTest {
    @Test fun `receives code from callback request`() {
        OAuthCallbackServer(0).use { server ->
            val port = server.actualPort
            // 模拟浏览器重定向到 callback
            val url = URL("http://localhost:$port/callback?code=test-auth-code-123&state=xyz")
            val conn = url.openConnection() as HttpURLConnection
            assertEquals(200, conn.responseCode)
            conn.inputStream.readBytes()
            conn.disconnect()
            // waitForCode 应立即返回 code
            val code = server.waitForCode(1000)
            assertEquals("test-auth-code-123", code)
        }
    }
}
```

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :net:test --no-daemon
git add -A && git commit -m "feat(net/auth): OAuth callback server（HttpServer 监听 /callback 捕获 code）"
```

---

### Task 8: OAuth token exchange（form POST via QUIC）

**Files:** Create `net/src/main/kotlin/ceui/pixiv/net/auth/TokenExchange.kt`；Create `net/src/test/kotlin/ceui/pixiv/net/auth/TokenExchangeTest.kt`

**Interfaces:** Consumes `PixivOAuthConfig`（Task 6）+ :net 的 `OkHttpClient`（经 QuicInterceptor，`oauth.secure.pixiv.net` 在 `PixivHosts.shouldQuic` 内）。Produces `TokenExchange`（`suspend fun exchangeCode(code, verifier): OAuthTokenResponse` + `suspend fun refreshToken(refreshToken): OAuthTokenResponse`），form-encoded POST 到 token endpoint。

- [ ] **Step 1: 写 `TokenExchange.kt`**

```kotlin
package ceui.pixiv.net.auth

import com.google.gson.annotations.SerializedName
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class OAuthTokenResponse(
    @SerializedName("access_token") val accessToken: String?,
    @SerializedName("refresh_token") val refreshToken: String?,
    @SerializedName("expires_in") val expiresIn: Long = 0,
    @SerializedName("user") val user: OAuthUser? = null,
)

data class OAuthUser(
    @SerializedName("id") val id: Long = 0,
    @SerializedName("name") val name: String? = null,
    @SerializedName("account") val account: String? = null,
)

open class TokenExchange(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    open suspend fun exchangeCode(code: String, codeVerifier: String): OAuthTokenResponse {
        val body = FormBody.Builder()
            .add("client_id", PixivOAuthConfig.CLIENT_ID)
            .add("client_secret", PixivOAuthConfig.CLIENT_SECRET)
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("code_verifier", codeVerifier)
            .add("redirect_uri", PixivOAuthConfig.REDIRECT_URI)
            .add("include_policy", "true")
            .build()
        return postToken(body)
    }

    open suspend fun refreshToken(refreshToken: String): OAuthTokenResponse {
        val body = FormBody.Builder()
            .add("client_id", PixivOAuthConfig.CLIENT_ID)
            .add("client_secret", PixivOAuthConfig.CLIENT_SECRET)
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("include_policy", "true")
            .build()
        return postToken(body)
    }

    private suspend fun postToken(body: FormBody): OAuthTokenResponse {
        val request = Request.Builder()
            .url(PixivOAuthConfig.TOKEN_ENDPOINT)
            .post(body)
            .build()
        return kotlinx.coroutines.Dispatchers.IO.let { ctx ->
            kotlinx.coroutines.withContext(ctx) {
                client.newCall(request).execute().use { resp ->
                    val json = resp.body?.string() ?: ""
                    if (!resp.isSuccessful) throw RuntimeException("OAuth token exchange failed: ${resp.code} $json")
                    com.google.gson.Gson().fromJson(json, OAuthTokenResponse::class.java)
                }
            }
        }
    }
}
```

> **注意**：此 OkHttpClient 需挂 `NettyQuicInterceptor`（oauth.secure.pixiv.net 被 SNI RST）。Task 12 闸门中用 `Client` 的 OkHttpClient（已含 QuicInterceptor）。单测用普通 OkHttpClient 发到 oauth endpoint 会超时（GFW RST）——单测只验证 form body 构造，不发真实请求。

- [ ] **Step 2: 写测试（验证 body 构造，不发真实请求） + 跑 + commit**

```kotlin
package ceui.pixiv.net.auth

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TokenExchangeTest {
    @Test fun `exchangeCode builds correct form body`() {
        // 验证 form body 字段：不能直接调 exchangeCode（会发真实 HTTP）。
        // 改为验证 OAuthTokenResponse 可从 JSON 反序列化。
        val json = """{"access_token":"at123","refresh_token":"rt456","expires_in":3600}"""
        val resp = com.google.gson.Gson().fromJson(json, OAuthTokenResponse::class.java)
        assertEquals("at123", resp.accessToken)
        assertEquals("rt456", resp.refreshToken)
        assertEquals(3600, resp.expiresIn)
    }
}
```

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :net:test --no-daemon
git add -A && git commit -m "feat(net/auth): token exchange（form POST code+verifier / refresh_token）"
```

---

### Task 9: RealTokenRefresher

**Files:** Create `net/src/main/kotlin/ceui/pixiv/net/auth/RealTokenRefresher.kt`；Create `net/src/test/kotlin/ceui/pixiv/net/auth/RealTokenRefresherTest.kt`

**Interfaces:** Consumes `TokenExchange`（Task 8）+ `:net` 的 `TokenStore`/`TokenRefresher`（Plan 2）。Produces `RealTokenRefresher(tokenStore, tokenExchange) : TokenRefresher`（`suspend fun refreshAccessToken(currentAccessToken): String?` → 调 `tokenExchange.refreshToken(tokenStore.getRefreshToken())` → 存新 token → 返回新 access_token；失败返回 null）。

- [ ] **Step 1: 写 `RealTokenRefresher.kt`**

```kotlin
package ceui.pixiv.net.auth

import ceui.pixiv.net.abstractions.TokenRefresher
import ceui.pixiv.net.abstractions.TokenStore

class RealTokenRefresher(
    private val tokenStore: TokenStore,
    private val tokenExchange: TokenExchange,
) : TokenRefresher {

    override suspend fun refreshAccessToken(currentAccessToken: String?): String? = try {
        val refreshToken = tokenStore.getRefreshToken() ?: return null
        val resp = tokenExchange.refreshToken(refreshToken)
        if (resp.accessToken != null) {
            tokenStore.saveTokens(resp.accessToken, resp.refreshToken)
            resp.accessToken
        } else null
    } catch (e: Exception) {
        null
    }
}
```

- [ ] **Step 2: 写测试（mock TokenExchange + TokenStore） + 跑 + commit**

```kotlin
package ceui.pixiv.net.auth

import ceui.pixiv.net.abstractions.TokenStore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RealTokenRefresherTest {
    private fun fakeStore(rt: String?) = object : TokenStore {
        var access: String? = "old-at"
        override val isLoggedIn get() = access != null
        override fun getAccessToken() = access
        override fun getRefreshToken() = rt
        override fun saveTokens(accessToken: String?, refreshToken: String?, userJson: String?) {
            access = accessToken
        }
        override fun clear() { access = null }
    }
    private class FakeExchange(val resp: OAuthTokenResponse, val shouldThrow: Boolean = false) : TokenExchange() {
        override suspend fun refreshToken(refreshToken: String): OAuthTokenResponse {
            if (shouldThrow) throw RuntimeException("network error")
            return resp
        }
    }

    @Test fun `refresh succeeds returns new token and persists`() = runBlocking {
        val store = fakeStore("old-rt")
        val refresher = RealTokenRefresher(store, FakeExchange(OAuthTokenResponse("new-at", "new-rt")))
        assertEquals("new-at", refresher.refreshAccessToken("old-at"))
        assertEquals("new-at", store.getAccessToken())
    }
    @Test fun `no refresh token returns null`() = runBlocking {
        val store = fakeStore(null)
        val refresher = RealTokenRefresher(store, FakeExchange(OAuthTokenResponse("x", "y")))
        assertNull(refresher.refreshAccessToken("old-at"))
    }
    @Test fun `exchange throws returns null`() = runBlocking {
        val store = fakeStore("old-rt")
        val refresher = RealTokenRefresher(store, FakeExchange(OAuthTokenResponse("x", "y"), shouldThrow = true))
        assertNull(refresher.refreshAccessToken("old-at"))
    }
}
```

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :net:test --no-daemon
git add -A && git commit -m "feat(net/auth): RealTokenRefresher（调 TokenExchange 刷 token，存回 TokenStore）"
```

---

### Task 10: 搬运 RubySSLSocketFactory + TrustAllCertManager

**Files:** Port from source: `app/src/main/java/ceui/lisa/http/{RubySSLSocketFactory.java,TrustAllCertManager.java}` → `net/src/main/kotlin/ceui/pixiv/net/image/{RubySSLSocketFactory.kt,TrustAllCertManager.kt}`；Create `net/src/test/kotlin/ceui/pixiv/net/image/RubySSLSocketFactoryTest.kt`

**Strip/替换：**
- `RubySSLSocketFactory.java`：删 `import android.util.Log` + `org.jetbrains.annotations.*`；`Log.d(...)`→删（或 `println`）；包名 `ceui.lisa.http`→`ceui.pixiv.net.image`；转 Kotlin（`.java`→`.kt`，`@NotNull`/`@Nullable` 删，`public final class`→`class`，方法参数可空标 `?`）。关键：`createSocket(socket, null, port, autoClose)` 的 `null` hostname 不动（这是不发 SNI 的核心）。
- `TrustAllCertManager.java`：删 `@SuppressLint` + `import android.annotation.*`；包名改；转 Kotlin。3 个空方法 + `getAcceptedIssuers` 返回空数组，原样。

**Interfaces:** Produces `RubySSLSocketFactory : SSLSocketFactory`（无 SNI TLS）+ `TrustAllCertManager : X509TrustManager`（trust-all）。

- [ ] **Step 1: 搬 `TrustAllCertManager.kt`**

```kotlin
package ceui.pixiv.net.image

import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

class TrustAllCertManager : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
}
```

- [ ] **Step 2: 搬 `RubySSLSocketFactory.kt`**

```kotlin
package ceui.pixiv.net.image

import java.io.IOException
import java.net.Socket
import java.security.KeyManagementException
import java.security.NoSuchAlgorithmException
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager

class RubySSLSocketFactory : SSLSocketFactory {
    private val delegate: SSLSocketFactory

    init {
        try {
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf<TrustManager>(TrustAllCertManager()), null)
            delegate = sslContext.socketFactory
        } catch (e: NoSuchAlgorithmException) { throw RuntimeException(e)
        } catch (e: KeyManagementException) { throw RuntimeException(e) }
    }

    override fun createSocket(host: String?, port: Int): Socket =
        throw UnsupportedOperationException("Use createSocket(Socket, String, Int, Boolean)")
    override fun createSocket(host: String?, port: Int, localAddr: java.net.InetAddress?, localPort: Int): Socket =
        throw UnsupportedOperationException("Use createSocket(Socket, String, Int, Boolean)")
    override fun createSocket(addr: java.net.InetAddress?, port: Int): Socket =
        throw UnsupportedOperationException("Use createSocket(Socket, String, Int, Boolean)")
    override fun createSocket(addr: java.net.InetAddress?, port: Int, localAddr: java.net.InetAddress?, localPort: Int): Socket =
        throw UnsupportedOperationException("Use createSocket(Socket, String, Int, Boolean)")

    override fun createSocket(socket: Socket?, host: String?, port: Int, autoClose: Boolean): Socket {
        if (socket == null) throw NullPointerException("socket is null")
        // 传 null hostname → Java TLS 不在 ClientHello 中包含 SNI 扩展（反墙核心）
        val sslSocket = delegate.createSocket(socket, null, port, autoClose) as SSLSocket
        sslSocket.enabledProtocols = sslSocket.supportedProtocols
        return sslSocket
    }

    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
}
```

- [ ] **Step 3: 写测试 + 跑 + commit**

```kotlin
package ceui.pixiv.net.image

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import javax.net.ssl.SSLSocketFactory

class RubySSLSocketFactoryTest {
    @Test fun `factory instantiates without error`() {
        val factory: SSLSocketFactory = RubySSLSocketFactory()
        assertNotNull(factory.defaultCipherSuites)
        assertTrue(factory.defaultCipherSuites.isNotEmpty())
    }
    @Test fun `unsupported overloads throw`() {
        val factory = RubySSLSocketFactory()
        assertThrows<UnsupportedOperationException> { factory.createSocket("host", 443) }
    }
    @Test fun `trustAll accepts empty issuers`() {
        val tm = TrustAllCertManager()
        assertEquals(0, tm.acceptedIssuers.size)
    }
}
```

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :net:test --no-daemon
git add -A && git commit -m "feat(net/image): 搬运 RubySSLSocketFactory + TrustAllCertManager（无 SNI TLS）"
```

---

### Task 11: Coil 3 ImageLoader + 反墙图片 OkHttpClient

**Files:** Modify `app/build.gradle.kts`（加 Coil 3 依赖）；Create `app/src/main/kotlin/ceui/pixiv/image/ImageLoaderFactory.kt`

**Interfaces:** Consumes `:net` 的 `HttpDns`/`PixivHosts`/`ImageHostManager`/`RubySSLSocketFactory`/`TrustAllCertManager` + `:store` 的 `SettingsStore`。Produces `ImageLoaderFactory`（`fun create(settings, logger): ImageLoader`——PIXIV+直连模式装 RubySSLSocketFactory + HttpDns + HTTP/1.1；非 PIXIV 模式走系统 DNS+标准 TLS；`ImageHostManager.rewrite` 接入 Coil mapper）。

- [ ] **Step 1: 改 `app/build.gradle.kts`（加 Coil 3）**

在 `dependencies` 块加：
```kotlin
    implementation("io.coil-kt.coil3:coil-compose:3.1.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.1.0")
```

- [ ] **Step 2: 写 `ImageLoaderFactory.kt`**

```kotlin
package ceui.pixiv.image

import ceui.pixiv.net.Hosts
import ceui.pixiv.net.dns.HttpDns
import ceui.pixiv.net.image.RubySSLSocketFactory
import ceui.pixiv.net.image.TrustAllCertManager
import ceui.pixiv.net.imagehost.ImageHostManager
import ceui.pixiv.net.abstractions.Settings
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.TimeUnit

object ImageLoaderFactory {
    fun create(settings: Settings): ImageLoader {
        val client = buildImageClient(settings)
        return ImageLoader.Builder(PlatformContext.INSTANCE)
            .crossfade(true)
            .memoryCache { MemoryCache.Builder().maxSizePercent(0.25).build() }
            .diskCache {
                val cacheDir = Path.of(System.getProperty("user.home"),
                    "Library/Caches/PixivShaft/images/").toFile()
                cacheDir.mkdirs()
                DiskCache.Builder()
                    .directory(cacheDir.absoluteFile)
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }
            .components {
                add(OkHttpNetworkFetcherFactory(client))
            }
            .build()
    }

    private fun buildImageClient(settings: Settings): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)

        if (settings.isDirectConnect && !ImageHostManager.requiresStandardClient()) {
            // PIXIV 模式 + 直连：无 SNI TLS + HttpDns(pximg 钉 210.140.139.x) + HTTP/1.1
            val trustManager = TrustAllCertManager()
            builder.sslSocketFactory(RubySSLSocketFactory(), trustManager)
            builder.hostnameVerifier { _, _ -> true }
            builder.dns(HttpDns(settings, ceui.pixiv.net.impl.StdoutLogger))
            builder.protocols(Collections.singletonList(Protocol.HTTP_1_1))
        }
        return builder.build()
    }
}
```

> **注意**：`PlatformContext.INSTANCE` 是 Coil 3 Desktop 的 context；具体 API 以 Coil 3.1.0 实际为准，编译时修正。`HttpDns` 构造需 `Settings` + `Logger`（Plan 2 Task 8）。`StdoutLogger` 在 `:net/impl`。

- [ ] **Step 3: 编译 + commit**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:compileKotlin --no-daemon
git add -A && git commit -m "feat(app/image): Coil 3 ImageLoader + 反墙图片 OkHttpClient"
```

---

### Task 12: Plan 3 闸门（SQLDelight + Keychain + OAuth + Image）

**Files:** Modify `app/src/main/kotlin/ceui/pixiv/poc/PocMain.kt`（改写为 Plan 3 闸门）；Modify `app/build.gradle.kts`（加 `implementation(project(":store"))` 依赖）

**Interfaces:** Consumes all Plan 3 components。Produces：`./gradlew :app:run` 验证：① SQLDelight 写读 illust history；② Keychain 存取 token；③ 无 token 时打印 OAuth URL 等待登录；④ 有 token 时 `getWalkthroughWorks()` 带 auth header；⑤ Coil 加载一张 pximg 图片。打印 `PLAN 3 GATE PASSED`。

- [ ] **Step 1: 加 `:store` 依赖到 `app/build.gradle.kts`**

```kotlin
    implementation(project(":store"))
```

- [ ] **Step 2: 重写 `PocMain.kt`**

```kotlin
package ceui.pixiv.poc

import ceui.pixiv.image.ImageLoaderFactory
import ceui.pixiv.net.api.Client
import ceui.pixiv.net.auth.OAuthCallbackServer
import ceui.pixiv.net.auth.PkceUtils
import ceui.pixiv.net.auth.PixivOAuthConfig
import ceui.pixiv.net.auth.RealTokenRefresher
import ceui.pixiv.net.auth.TokenExchange
import ceui.pixiv.net.impl.DefaultLanguageProvider
import ceui.pixiv.net.impl.StdoutLogger
import ceui.pixiv.store.KeychainTokenStore
import ceui.pixiv.store.SettingsStore
import ceui.pixiv.store.createDatabase
import kotlinx.coroutines.runBlocking
import java.awt.Desktop
import java.net.URI

fun main() = runBlocking {
    val settings = SettingsStore()
    val tokenStore = KeychainTokenStore()
    val logger = StdoutLogger

    // ① SQLDelight 写读
    val db = createDatabase()
    db.queries.illustHistoryQueries.insertIllust(48723512, """{"id":48723512}""", System.currentTimeMillis(), 0)
    val rows = db.queries.illustHistoryQueries.selectRecentIllusts(10).executeAsList()
    println("SQLDelight: ${rows.size} history rows, first illustID=${rows.firstOrNull()?.illustID}")

    // ② Keychain 存取
    println("Keychain: isLoggedIn=${tokenStore.isLoggedIn}")

    // ③ 无 token 时 OAuth 登录
    if (!tokenStore.isLoggedIn) {
        println("OAuth: no token, starting login flow...")
        val (verifier, challenge) = PkceUtils.generate()
        OAuthCallbackServer().use { server ->
            val authUrl = PkceUtils.buildAuthUrl(challenge)
            println("OAuth: open this URL in browser:\n  $authUrl")
            try { Desktop.getDesktop().browse(URI(authUrl)) } catch (e: Exception) { println("(auto-open failed, copy URL manually)") }
            val code = server.waitForCode(120_000)
            if (code != null) {
                println("OAuth: got code, exchanging token via QUIC...")
                val exchange = TokenExchange()  // 注意：生产应用 Client 的 OkHttpClient（含 QuicInterceptor）
                val resp = exchange.exchangeCode(code, verifier)
                if (resp.accessToken != null) {
                    tokenStore.saveTokens(resp.accessToken, resp.refreshToken)
                    println("OAuth: token stored in Keychain")
                } else { println("OAuth: token exchange failed"); return@runBlocking }
            } else { println("OAuth: timed out waiting for callback"); return@runBlocking }
        }
    }

    // ④ 带 auth header 调 API（用 Client + KeychainTokenStore + RealTokenRefresher）
    val refresher = RealTokenRefresher(tokenStore, TokenExchange())
    val client = Client(settings, tokenStore, refresher, DefaultLanguageProvider(), logger)
    val resp = client.appApi.getWalkthroughWorks()
    println("API: walkthrough illusts count=${resp.illusts.size}")
    resp.illusts.firstOrNull()?.let { println("API: first illust id=${it.id} title=${it.title}") }

    // ⑤ Coil 加载 pximg 图片
    val imageUrl = resp.illusts.firstOrNull()?.imageUrls?.firstOrNull()
    if (imageUrl != null) {
        println("Image: loading $imageUrl via Coil (anti-GFW client)...")
        val loader = ImageLoaderFactory.create(settings)
        val request = coil3.request.ImageRequest.Builder(ceui.pixiv.image.PlatformContext.INSTANCE)
            .data(imageUrl)
            .build()
        val result = loader.execute(request)
        println("Image: loaded, ${result.image?.width}x${result.image?.height}")
    }

    println("PLAN 3 GATE PASSED")
    client.close()
}
```

> **注意**：`imageUrls` 字段名以 Models.kt `Illust` 为准，编译时按实际修正。`PlatformContext.INSTANCE` 以 Coil 3.1.0 实际 API 为准。`TokenExchange()` 默认 OkHttpClient 不含 QuicInterceptor——生产应传入 `Client` 的 `okhttpClient`（需 `Client` 暴露它，或 `TokenExchange` 接受 OkHttpClient 参数）。闸门首次运行需手动开浏览器完成 OAuth；后续运行 Keychain 有 token 直接跳过。

- [ ] **Step 3: 跑闸门 + commit**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:run --no-daemon
```
Expected: `SQLDelight: 1 history rows`, `Keychain: isLoggedIn=true/false`, OAuth flow (首次), `API: walkthrough illusts count=112`, `Image: loaded`, `PLAN 3 GATE PASSED`。

```bash
git add -A && git commit -m "feat(app): Plan 3 闸门 — SQLDelight + Keychain + OAuth + Coil 图片加载"
```

---

## Self-Review

- **Spec 覆盖**：§3.1 SQLDelight（Task 1-2：driver + 3 core tables，剩余表 defer Plan 4+）；§3.2 KV（Task 3-5：KvStore + Preferences + Keychain + TokenStore/Settings 实现）；§5.1 Coil 3（Task 11-12）；§2.4 图片反墙客户端（Task 10-11：RubySSLSocketFactory + TrustAllCertManager + HttpDns + ImageHostManager）；§6.1 OAuth PKCE（Task 6-9：config + PKCE + callback + token exchange + refresher）。✓
- **占位符**：Task 8 注明单测不发真实 QUIC 请求（GFW RST）；Task 11 注明 Coil API 以实际为准编译修正；Task 12 注明 OAuth 首次需手动、imageUrls 字段名编译修正。无 TBD。✓
- **类型一致**：`TokenStore`/`TokenRefresher`/`Settings` 跨 Plan 2/3 一致；`KeychainTokenStore` 替 `FileTokenStore`、`SettingsStore` 替 `InMemorySettings`、`RealTokenRefresher` 替 `StubTokenRefresher`——:app 可按需选真实/stub 实现。✓
- **范围**：12 task，单 plan 可执行；剩余 SQLDelight 表（download/novel/mute 等）defer Plan 5/6；AuthState StateFlow defer Plan 4（UI 导航需要时）。

---
