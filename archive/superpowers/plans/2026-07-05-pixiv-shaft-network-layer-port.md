# Pixiv-Shaft macOS 移植 — Plan 2: 网络层搬运

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 Plan 1 的 POC 扩为多模块项目，搬运 loxia 协程网络栈（Client/拦截器/HttpDns/ImageHostManager）+ 实战化 QuicInterceptor，用协程 suspend 调 `getWalkthroughWorks()` 经 QUIC 拿 200+illusts。

**Architecture:** 多模块 Gradle JVM（`:models`/`:net`/`:store` stub/`:app`）。loxia 栈的 Android 依赖（SessionManager/MMKV/Shaft/LanguageHelper/BuildConfig）抽象为 `:net` 接口（`TokenStore`/`TokenRefresher`/`Settings`/`Logger`/`LanguageProvider`）+ JVM 默认实现；Plan 3/4 再换 Keychain/SQLDelight 真实实现。QuicInterceptor 在 Plan 1 基础上补 POST body/生命周期/异常快速失败（连接池 defer 到 Plan 3）。

**Tech Stack:** Kotlin 2.1.20、JDK 21、OkHttp 4.12.0、Retrofit 2.11.0（+ Gson converter）、Gson 2.11.0、netty 4.2.2.Final、kotlinx-coroutines-core、JUnit 5、Compose Multiplatform 1.7.x（`:app` 空窗口）。

## Global Constraints

- 目标机：macOS Apple Silicon（aarch64）。
- 仓库：`~/workspace/git_repo/Pixiv-Shaft-Desktop`（Plan 1 已建，HEAD d789be6）。本 plan 在其上扩为多模块。
- **每个 `./gradlew` 调用前缀 `JAVA_HOME=/opt/homebrew/opt/openjdk@21`**（Homebrew keg-only JDK 21）。
- **大陆镜像**：Gradle 发行版腾讯镜像；Maven Aliyun public+central；Kotlin 插件 Aliyun gradle-plugin（Plan 1 已配，沿用）。
- netty 4.2.2.Final：`netty-codec-http3` + native `osx-aarch_64`，**exclude transitive netty-codec-native-quic**（Plan 1 踩坑：POM 用 `${os.detected.*}` Gradle 不插值）。
- **QuicInterceptor SNI 关键**（Plan 1 踩坑）：必须 `.sslEngineProvider { q -> ctx.newEngine(q.alloc(), host, port) }`，**不能** `.sslContext(ctx)`（后者不发 SNI → CF 丢弃握手）。
- secret `28c1fdd170a5204386cb1313c7077b34f83e4aaf4aa829ce78c231e05b0bae2c`；CF IP `104.18.42.239`/`172.64.145.17`；iOS UA `PixivIOSApp/8.6.10 (iOS 26.5; iPhone16,2)`。
- 源仓 `~/workspace/git_repo/Pixiv-Shaft`（移植来源，不修改）。
- 不引入 RxJava/Compose UI（:app 仅空窗口）/Room/MMKV。
- Port 任务：源文件即权威 spec——"从源仓复制 X，strip 列出的 Android 引用"是完整指令，非占位。

## File Structure

- `settings.gradle.kts` — `:models`, `:net`, `:store`(stub), `:app`。
- `build.gradle.kts`（根）— Kotlin 2.1.20 + Compose plugin。
- `models/build.gradle.kts` — JVM lib，Gson。
- `net/build.gradle.kts` — JVM lib，OkHttp/Retrofit/netty/coroutines，`:models` 依赖。
- `store/build.gradle.kts` — JVM lib stub（Plan 3 填）。
- `app/build.gradle.kts` — Compose Desktop，依赖 `:net`（:app 是 Plan 4 UI 入口；本 plan 只放验证 main + 空窗口）。
- `net/.../auth/`、`net/.../interceptor/`、`net/.../dns/`、`net/.../api/`、`net/.../config/`（PixivConstants）、`net/.../abstractions/`（接口）、`net/.../impl/`（JVM 默认实现）。

---

### Task 1: 多模块骨架

**Files:**
- Modify: `settings.gradle.kts`（加 :models/:net/:store）
- Create: `models/build.gradle.kts`、`net/build.gradle.kts`、`store/build.gradle.kts`
- Modify: `app/build.gradle.kts`（依赖 :net，加 Compose）
- Move: POC 的 `RequestNonce.kt`/`PixivHosts.kt`/`NettyQuicInterceptor.kt` 从 `app/` 移到 `net/src/main/kotlin/ceui/pixiv/net/`（包名改 `ceui.pixiv.net`）；`PocMain.kt` 留 :app（Task 12 改写）。

**Interfaces:**
- Produces: `./gradlew :app:run` 仍打印 POC 闸门通过（QuicInterceptor 已在 :net，:app 经 :net 依赖调用）；空 Compose 窗口可编译（暂不显示，main 仍跑 POC 逻辑）。

- [ ] **Step 1: 改 `settings.gradle.kts`**

```kotlin
pluginManagement {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        gradlePluginPortal()
    }
}
rootProject.name = "Pixiv-Shaft-Desktop"
include(":models", ":net", ":store", ":app")
```

- [ ] **Step 2: 改根 `build.gradle.kts`**

```kotlin
plugins {
    kotlin("jvm") version "2.1.20" apply false
    id("org.jetbrains.compose") version "1.7.3" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
}
```

- [ ] **Step 3: 写 `models/build.gradle.kts`**

```kotlin
plugins { kotlin("jvm") }
group = "ceui.pixiv"; version = "0.0.1"
java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
repositories {
    maven { url = uri("https://maven.aliyun.com/repository/public") }
    maven { url = uri("https://maven.aliyun.com/repository/central") }
    mavenCentral()
}
dependencies { implementation("com.google.code.gson:gson:2.11.0") }
```

- [ ] **Step 4: 写 `net/build.gradle.kts`**

```kotlin
plugins { kotlin("jvm") }
group = "ceui.pixiv"; version = "0.0.1"
java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
repositories {
    maven { url = uri("https://maven.aliyun.com/repository/public") }
    maven { url = uri("https://maven.aliyun.com/repository/central") }
    mavenCentral()
}
dependencies {
    implementation(project(":models"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("io.netty:netty-codec-http3:4.2.2.Final") {
        exclude(group = "io.netty", module = "netty-codec-native-quic")
    }
    runtimeOnly("io.netty:netty-codec-native-quic:4.2.2.Final:osx-aarch_64")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}
tasks.test { useJUnitPlatform() }
```

- [ ] **Step 5: 写 `store/build.gradle.kts`（stub）**

```kotlin
plugins { kotlin("jvm") }
group = "ceui.pixiv"; version = "0.0.1"
java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
```

- [ ] **Step 6: 改 `app/build.gradle.kts`（依赖 :net + Compose）**

在现有基础上加 `implementation(project(":net"))` 与 Compose 插件（保留 POC 的 netty 依赖或改从 :net 传递；为稳，:app 仍直接加 netty runtime native）：

```kotlin
plugins {
    id("com.google.osdetector") version "1.7.3"
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    application
}
group = "ceui.pixiv"; version = "0.0.1"
java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
repositories {
    maven { url = uri("https://maven.aliyun.com/repository/public") }
    maven { url = uri("https://maven.aliyun.com/repository/central") }
    mavenCentral()
}
dependencies {
    implementation(project(":net"))
    runtimeOnly("io.netty:netty-codec-native-quic:4.2.2.Final:osx-aarch_64")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}
tasks.test { useJUnitPlatform() }
application { mainClass.set("ceui.pixiv.poc.PocMainKt") }  // Task 12 改为 AppMain
```

- [ ] **Step 7: 移动 POC 文件到 :net**

把 `app/src/main/kotlin/ceui/pixiv/poc/{RequestNonce,PixivHosts,NettyQuicInterceptor}.kt` 移到 `net/src/main/kotlin/ceui/pixiv/net/`，包名 `ceui.pixiv.poc` → `ceui.pixiv.net`（更新 `package` 行 + 互相引用）。`PocMain.kt` 留 :app，更新 import 为 `ceui.pixiv.net.*`。

- [ ] **Step 8: 验证 + commit**

```bash
cd ~/workspace/git_repo/Pixiv-Shaft-Desktop
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:run --no-daemon
```
Expected: 仍 `POC GATE PASSED`（QuicInterceptor 在 :net，经 :app→:net 调用）。

```bash
git add -A && git commit -m "refactor: 扩为多模块（:models/:net/:store/:app），POC 文件移入 :net"
```

---

### Task 2: :net 抽象接口 + PixivConstants

**Files:**
- Create: `net/src/main/kotlin/ceui/pixiv/net/abstractions/NetAbstractions.kt`
- Create: `net/src/main/kotlin/ceui/pixiv/net/config/PixivConstants.kt`

**Interfaces:**
- Produces: `interface Settings`、`interface TokenStore`、`interface TokenRefresher`、`interface Logger`、`interface LanguageProvider`；`object PixivConstants`（`TOKEN_ERROR_1`/`_2`/`HEADER_AUTH`/`TOKEN_HEAD`/`WEB_USER_AGENT`/host 常量）。

- [ ] **Step 1: 写 `NetAbstractions.kt`**

```kotlin
package ceui.pixiv.net.abstractions

interface Settings {
    val isDirectConnect: Boolean
    val isUseSecureDns: Boolean
    val imageHostMode: Int
    val customImageHost: String
}

interface TokenStore {
    val isLoggedIn: Boolean
    fun getAccessToken(): String?
    fun getBearerToken(): String? = getAccessToken()?.let { "Bearer $it" }
    fun getRefreshToken(): String?
    fun saveTokens(accessToken: String?, refreshToken: String?, userJson: String? = null)
    fun clear()
}

interface TokenRefresher {
    suspend fun refreshAccessToken(currentAccessToken: String?): String?
}

interface Logger {
    fun d(msg: String)
    fun w(msg: String, t: Throwable? = null)
    fun e(msg: String, t: Throwable? = null)
}

interface LanguageProvider {
    fun acceptLanguage(): String          // Accept-Language
    fun appAcceptLanguage(): String       // App-Accept-Language
}
```

- [ ] **Step 2: 写 `PixivConstants.kt`**（搬 `ClientManager` 常量，源 `Client.kt:55-86`）

```kotlin
package ceui.pixiv.net.config

object PixivConstants {
    const val APP_API_HOST = "https://app-api.pixiv.net"
    const val WEB_API_HOST = "https://www.pixiv.net"
    const val WEB_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.39 Mobile Safari/537.36"
    const val TOKEN_HEAD = "Bearer "
    const val HEADER_AUTH = "authorization"
    const val TOKEN_ERROR_1 = "Error occurred at the OAuth process"
    const val TOKEN_ERROR_2 = "Invalid refresh token"
}
```

- [ ] **Step 3: 编译 + commit**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :net:compileKotlin --no-daemon
git add -A && git commit -m "feat(net): 网络层抽象接口 + PixivConstants"
```

---

### Task 3: :net JVM 默认实现 + 测试

**Files:**
- Create: `net/src/main/kotlin/ceui/pixiv/net/impl/Defaults.kt`
- Create: `net/src/test/kotlin/ceui/pixiv/net/impl/DefaultsTest.kt`

**Interfaces:**
- Consumes: Task 2 接口。
- Produces: `InMemorySettings`、`FileTokenStore(path)`、`StubTokenRefresher`、`StdoutLogger`、`DefaultLanguageProvider`。

- [ ] **Step 1: 写失败测试**

```kotlin
package ceui.pixiv.net.impl

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class DefaultsTest {
    @Test fun `inmemory settings defaults`() {
        val s = InMemorySettings()
        assertFalse(s.isDirectConnect)
        assertFalse(s.isUseSecureDns)
        assertEquals(0, s.imageHostMode)
        assertEquals("", s.customImageHost)
    }
    @Test fun `file token store roundtrip`(@TempDir dir: Path) {
        val store = FileTokenStore(dir.resolve("t.json"))
        assertFalse(store.isLoggedIn)
        store.saveTokens("at", "rt", "{}")
        assertTrue(store.isLoggedIn)
        assertEquals("at", store.getAccessToken())
        assertEquals("Bearer at", store.getBearerToken())
        assertEquals("rt", store.getRefreshToken())
        store.clear()
        assertFalse(store.isLoggedIn)
    }
    @Test fun `stub refresher returns null`() {
        val r = StubTokenRefresher()
        assertNull(r.refreshAccessToken("x") /* not suspend; see note */.let { null }
            .let { kotlin.runBlocking { r.refreshAccessToken("x") } })
    }
    @Test fun `default language is zh`() {
        assertEquals("zh-Hans", DefaultLanguageProvider().acceptLanguage().substringBefore('-').let { "zh" })
    }
}
```

> 第三个测试用 `runBlocking` 调 suspend；简化为：`@Test fun \`stub refresher returns null\`() = assertNull(kotlinx.coroutines.runBlocking { StubTokenRefresher().refreshAccessToken("x") })`

- [ ] **Step 2: 跑确认 fail**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :net:test --tests "ceui.pixiv.net.impl.DefaultsTest" --no-daemon
```
Expected: FAIL（类未定义）。

- [ ] **Step 3: 写实现**

```kotlin
package ceui.pixiv.net.impl

import ceui.pixiv.net.abstractions.*
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.nio.file.Path
import kotlin.io.path.*

class InMemorySettings(
    override var isDirectConnect: Boolean = false,
    override var isUseSecureDns: Boolean = false,
    override var imageHostMode: Int = 0,
    override var customImageHost: String = ""
) : Settings

class FileTokenStore(private val path: Path) : TokenStore {
    private val gson = Gson()
    private data class Bag(var access: String? = null, var refresh: String? = null, var user: String? = null)
    private var bag: Bag = load()
    override val isLoggedIn get() = bag.access != null
    override fun getAccessToken() = bag.access
    override fun getRefreshToken() = bag.refresh
    override fun saveTokens(accessToken: String?, refreshToken: String?, userJson: String?) {
        bag = Bag(accessToken, refreshToken ?: bag.refresh, userJson ?: bag.user)
        flush()
    }
    override fun clear() { bag = Bag(); flush() }
    private fun load(): Bag = if (path.exists()) try { gson.fromJson(path.readText(), Bag::class.java) ?: Bag() } catch (e: Exception) { Bag() } else Bag()
    private fun flush() { path.parent.createDirectories(); path.writeText(gson.toJson(bag)) }
}

class StubTokenRefresher : TokenRefresher {
    override suspend fun refreshAccessToken(currentAccessToken: String?): String? = null
}

object StdoutLogger : Logger {
    override fun d(msg: String) = println("[D] $msg")
    override fun w(msg: String, t: Throwable?) = println("[W] $msg").also { t?.printStackTrace() }
    override fun e(msg: String, t: Throwable?) = println("[E] $msg").also { t?.printStackTrace() }
}

class DefaultLanguageProvider : LanguageProvider {
    override fun acceptLanguage() = "zh"
    override fun appAcceptLanguage() = "zh-Hans"
}
```

- [ ] **Step 4: 跑确认 pass + commit**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :net:test --tests "ceui.pixiv.net.impl.DefaultsTest" --no-daemon
git add -A && git commit -m "feat(net): JVM 默认实现（InMemorySettings/FileTokenStore/StubRefresher/StdoutLogger/DefaultLang）"
```

---

### Task 4: 搬运 :models

**Files:**
- Port from source: `/Users/he/workspace/git_repo/Pixiv-Shaft/models/src/main/java/ceui/lisa/models/*.java`（54 文件）→ `models/src/main/java/ceui/lisa/models/`
- Port from source: `/Users/he/workspace/git_repo/Pixiv-Shaft/app/src/main/java/ceui/loxia/Models.kt`（879 行）→ `models/src/main/kotlin/ceui/loxia/Models.kt`
- Port from source: `app/src/main/java/ceui/lisa/model/ListTrendingtag.*`、`app/.../ceui/lisa/utils/Params.java`、`app/.../ceui/lisa/http/NullCtrl.java`/`ErrorCtrl.java`（API.kt 引用）

**Strip list（搬运时删/改）：**
- `Models.kt`：删 `import android.os.Parcelable` + 所有 `: Parcelable` 与 `@Parcelize`；`android.text.TextUtils.isEmpty(x)` → `x.isNullOrEmpty()`。
- `.java` models：若有 `android.os.Parcelable` 同上；绝大多数是纯 Gson bean，原样搬。
- `Params.java`：若有 Android 引用，删；否则原样。
- 包名保持不变（`ceui.lisa.models`/`ceui.loxia`/`ceui.lisa.model`/`ceui.lisa.utils`），避免 API.kt/Models.kt 的 import 改动。

**Interfaces:**
- Produces: `:models` 编译通过，`Models.kt` 的 `IllustResponse`/`Illust`/`HomeIllustResponse`/`User`/`AccountResponse` 等可用。

- [ ] **Step 1: 批量复制 + strip**

```bash
SRC=/Users/he/workspace/git_repo/Pixiv-Shaft
DST=~/workspace/git_repo/Pixiv-Shaft-Desktop
mkdir -p $DST/models/src/main/java/ceui/lisa/models $DST/models/src/main/kotlin/ceui/loxia $DST/models/src/main/java/ceui/lisa/model $DST/models/src/main/java/ceui/lisa/utils
cp $SRC/models/src/main/java/ceui/lisa/models/*.java $DST/models/src/main/java/ceui/lisa/models/
cp $SRC/app/src/main/java/ceui/loxia/Models.kt $DST/models/src/main/kotlin/ceui/loxia/
# API.kt 引用的辅助类
cp $SRC/app/src/main/java/ceui/lisa/model/ListTrendingtag.* $DST/models/src/main/java/ceui/lisa/model/ 2>/dev/null
cp $SRC/app/src/main/java/ceui/lisa/utils/Params.java $DST/models/src/main/java/ceui/lisa/utils/
```
然后编辑 `Models.kt`：删 `import android.os.Parcelable`、`import android.text.TextUtils`；全局替换 `: Parcelable`→删除、`@Parcelize`→删除、`TextUtils.isEmpty(`→`(` 已由 kotlin `isEmpty` 替代则改 `.isEmpty()`。用 grep 确认无残留 `android.`：
```bash
grep -rn "android\." $DST/models/src/main 2>/dev/null || echo "clean"
```

- [ ] **Step 2: 编译 + 修残留 + commit**

```bash
cd $DST && JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :models:compileKotlin --no-daemon
```
按编译错修残留 Android 引用（如 `Params.java` 里的 `android.content.Context` 等——若存在则删该字段或替换）。直到 `BUILD SUCCESSFUL`。

```bash
git add -A && git commit -m "feat(models): 搬运 Gson bean + loxia Models.kt（strip Parcelable/TextUtils）"
```

---

### Task 5: RequestNonce + PixivHosts 移入 :net（已在 Task 1 移动，此处补测试）

**Files:**
- 已在 `net/.../ceui/pixiv/net/RequestNonce.kt` + `PixivHosts.kt`（Task 1 移动，包名 `ceui.pixiv.net`）。
- Create: `net/src/test/kotlin/ceui/pixiv/net/RequestNonceTest.kt` + `PixivHostsTest.kt`（从 POC 的 test 复制，改包名）。

- [ ] **Step 1: 复制测试，改包名 `ceui.pixiv.poc`→`ceui.pixiv.net`**

- [ ] **Step 2: 跑测试 + commit**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :net:test --no-daemon
git add -A && git commit -m "test(net): RequestNonce + PixivHosts 测试迁移到 :net"
```

---

### Task 6: 搬运 HeaderInterceptor

**Files:**
- Port from source: `app/src/main/java/ceui/loxia/HeaderInterceptor.kt` → `net/src/main/kotlin/ceui/pixiv/net/interceptor/HeaderInterceptor.kt`
- 包名 `ceui.loxia` → `ceui.pixiv.net.interceptor`。

**Strip/替换：**
- `import ceui.lisa.helper.LanguageHelper` → 用构造注入的 `LanguageProvider`。
- `import ceui.pixiv.session.SessionManager` → 构造注入 `TokenStore`。
- `ClientManager.HEADER_AUTH` → `PixivConstants.HEADER_AUTH`。
- `RequestNonce` import → `ceui.pixiv.net.RequestNonce`。
- 构造改为 `class HeaderInterceptor(private val tokenStore: TokenStore, private val lang: LanguageProvider)`。
- `SessionManager.isLoggedIn`→`tokenStore.isLoggedIn`；`SessionManager.getBearerToken()`→`tokenStore.getBearerToken()`。
- `LanguageHelper.getRequestHeaderAcceptLanguageFromAppLanguage()`→`lang.acceptLanguage()`；`getRequestHeaderAppAcceptLanguageFromAppLanguage()`→`lang.appAcceptLanguage()`。
- `app-os`/`app-version`/UA 常量从 `HeaderInterceptor` companion（源 `:12-17`）搬入 `PixivConstants` 或保留 companion。

**Interfaces:**
- Produces: `HeaderInterceptor(tokenStore, lang): Interceptor`，编译通过，注入版单测（mock TokenStore 返回 Bearer，断言请求头含 authorization/app-os/x-client-hash）。

- [ ] **Step 1: 写注入版 + 单测**（mock TokenStore：`object : TokenStore { override val isLoggedIn=true; override fun getAccessToken()="at"; ... }`；断言 `chain.proceed` 收到的请求含 `authorization=Bearer at`、`app-os=ios`、`x-client-hash` 非空）。
- [ ] **Step 2: 跑测试 + commit**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :net:test --no-daemon
git add -A && git commit -m "feat(net): 搬运 HeaderInterceptor（注入 TokenStore/LanguageProvider）"
```

---

### Task 7: 搬运 TokenFetcherInterceptor

**Files:**
- Port from source: `app/src/main/java/ceui/loxia/TokenFetcherInterceptor.kt` → `net/.../interceptor/TokenFetcherInterceptor.kt`

**Strip/替换：**
- `SessionManager.isLoggedIn`→`tokenStore.isLoggedIn`；`SessionManager.refreshAccessToken(token)`→suspend 调 `refresher.refreshAccessToken(token)`（注意：OkHttp 拦截器非 suspend，用 `runBlocking` 包——保留源 app 的同步语义，但用协程；Plan 4 再优化）。
- `ClientManager.TOKEN_ERROR_1/2`→`PixivConstants`；`ClientManager.HEADER_AUTH`/`TOKEN_HEAD`→`PixivConstants`。
- 构造：`class TokenFetcherInterceptor(private val tokenStore: TokenStore, private val refresher: TokenRefresher)`。

**Interfaces:**
- Produces: `TokenFetcherInterceptor(tokenStore, refresher): Interceptor`，编译通过。单测：mock 400+`Invalid refresh token` 响应 → 断言调 `refresher.refreshAccessToken` 并重发（mock chain 验证第二次请求带新 token）。

- [ ] **Step 1: 写注入版 + 单测**（400 响应触发刷新路径；200 直透）。
- [ ] **Step 2: 跑 + commit**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :net:test --no-daemon
git add -A && git commit -m "feat(net): 搬运 TokenFetcherInterceptor（注入 TokenStore/Refresher，400 刷新）"
```

---

### Task 8: 搬运 HttpDns + CloudFlareDns/Service/Response

**Files:**
- Port from source: `app/src/main/java/ceui/lisa/http/{HttpDns.java,CloudFlareDns.kt,CloudFlareDNSService.kt,CloudFlareDNSResponse.kt}` → `net/.../dns/`
- 包名 `ceui.lisa.http` → `ceui.pixiv.net.dns`。

**Strip/替换：**
- `HttpDns.java`：`import ceui.lisa.activities.Shaft`→构造注入 `Settings` + `Logger`；`Shaft.sSettings.isUseSecureDns()`→`settings.isUseSecureDns`；`Common.showLog`→`logger.d`；`Shaft.sSettings` 其他→`settings`。`getInstance()` 单例改为构造注入或 `class HttpDns(settings, logger): Dns`。
- `CloudFlareDNSService`：纯 OkHttp/Retrofit，包名改即可。`CLOUDFLARE_DOH_POINT` 等常量保留。
- `CloudFlareDNSResponse`：纯 Gson，包名改。

**Interfaces:**
- Produces: `HttpDns(settings, logger): Dns`、`CloudFlareDNSService`（Retrofit）、`CloudFlareDns`、`CloudFlareDNSResponse`，编译通过。单测：`lookup("i.pximg.net")` 返回 `210.140.139.x` fallback；`lookup("app-api.pixiv.net")` 返回 CF IP fallback（DoH 关时）。

- [ ] **Step 1: 搬运 + strip + 单测**
- [ ] **Step 2: 跑 + commit**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :net:test --no-daemon
git add -A && git commit -m "feat(net): 搬运 HttpDns/CloudFlareDns/Service/Response（注入 Settings/Logger）"
```

---

### Task 9: 搬运 ImageHostManager

**Files:**
- Port from source: `app/src/main/java/ceui/lisa/http/ImageHostManager.kt` → `net/.../imagehost/ImageHostManager.kt`

**Strip/替换：**
- 纯 Kotlin，无 Android 依赖。仅包名 `ceui.lisa.http`→`ceui.pixiv.net.imagehost`。`object ImageHostManager` 原样。

**Interfaces:**
- Produces: `ImageHostManager`（`Mode` 枚举、`rewrite(url)`、`requiresStandardClient()`）。单测：`rewrite("https://i.pximg.net/x.jpg")` 在 `PIXIV_CAT` 模式→`https://i.pixiv.cat/x.jpg`；`PIXIV` 模式→原样。

- [ ] **Step 1: 搬运 + 单测**
- [ ] **Step 2: 跑 + commit**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :net:test --no-daemon
git add -A && git commit -m "feat(net): 搬运 ImageHostManager"
```

---

### Task 10: 实战化 NettyQuicInterceptor（补 Plan 1 deferred Minors）

**Files:**
- Modify: `net/src/main/kotlin/ceui/pixiv/net/NettyQuicInterceptor.kt`

**改动（基于 Plan 1 已验证版本）：**
1. **POST body**：`request.body` 非空时，写一个 `DefaultHttp3DataFrame(bodyBytes)` 在 `writeAndFlush(headersFrame)` 后、`SHUTDOWN_OUTPUT` 前（用 `streamChannel.writeAndFlush(dataFrame)` 再 `addListener(SHUTDOWN_OUTPUT)`）。`bodyBytes` 从 `request.body?.writeTo(Buffer)` 取。
2. **event-loop 生命周期**：`group` 改为 `private val group` 不变，加 `fun close() { group.shutdownGracefully().sync() }`，供 :app 退出时调（连接池 defer Plan 3，仍每请求建连）。
3. **异常快速失败**：inbound handler 加 `exceptionCaught(ctx, t)` → `done.completeExceptionally(t)`；`channelInputClosed` 仅在未完成时 complete。
4. **InterruptedException 保留中断标志**：catch 块里 `(e as? InterruptedException)?.let { Thread.currentThread().interrupt() }`。

**Interfaces:**
- Produces: `NettyQuicInterceptor` 带 POST body + close() + 异常快速失败。SNI 修复**保留**（`.sslEngineProvider` 不动）。

- [ ] **Step 1: 改实现（保留 SNI fix）**
- [ ] **Step 2: 编译 + 跑 POC 闸门确认未回归**

```bash
cd ~/workspace/git_repo/Pixiv-Shaft-Desktop
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:run --no-daemon
```
Expected: 仍 `POC GATE PASSED`（walkthrough GET 不走 POST 路径，不回归）。

- [ ] **Step 3: commit**

```bash
git add -A && git commit -m "feat(net): QuicInterceptor 实战化（POST body/生命周期/异常快速失败）"
```

---

### Task 11: 搬运 Client/ClientManager + API.kt

**Files:**
- Port from source: `app/src/main/java/ceui/loxia/Client.kt` → `net/.../api/Client.kt`
- Port from source: `app/src/main/java/ceui/loxia/API.kt` → `net/.../api/API.kt`
- 包名 `ceui.loxia`→`ceui.pixiv.net.api`。

**Strip/替换（Client.kt）：**
- `import ceui.lisa.BuildConfig`→删（SHAFT_EVENTS_HMAC 仅 pixshaft 用，不在范围）。
- `import ceui.lisa.activities.Shaft`→构造注入 `Settings`。
- `Shaft.sSettings.isDirectConnect`→`settings.isDirectConnect`。
- `CronetInterceptor`→`NettyQuicInterceptor`（构造注入或单例）。
- `ClientManager` 常量→`PixivConstants`（host/HEADER_AUTH/TOKEN_HEAD/TOKEN_ERROR_*/WEB_USER_AGENT）。
- `HeaderInterceptor()`→`HeaderInterceptor(tokenStore, lang)`；`TokenFetcherInterceptor()`→`TokenFetcherInterceptor(tokenStore, refresher)`。
- `applyDirectConnect`：`settings.isDirectConnect` 时挂 `NettyQuicInterceptor`。
- `object Client` 改为 `class Client(settings, tokenStore, refresher, lang, logger)` 持有依赖；或保留 `object` + `init(...)` 注入。推荐 `class Client(...)`，:app 构造一个实例。
- **补 `fun close()`**：委托关 `NettyQuicInterceptor.close()`（Task 10 已加），供 :app 退出时调。
- `createMoonAPIService`/`createPixshaftService`：**删**（moonAPI/pixshaft 不在范围）。
- `API.kt`：纯 Retrofit interface，包名改；`import ceui.lisa.model.ListTrendingtag`/`ceui.lisa.models.NullResponse`/`ceui.lisa.utils.Params` 已在 :models（Task 4）。原样搬。

**Interfaces:**
- Produces: `class Client(...)`，`appApi: API`（Retrofit suspend），`webApi` 可保留但本 plan 不验证。编译通过。

- [ ] **Step 1: 搬 Client.kt + strip + API.kt**
- [ ] **Step 2: 编译 + 修残留**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :net:compileKotlin --no-daemon
```

- [ ] **Step 3: commit**

```bash
git add -A && git commit -m "feat(net): 搬运 Client/ClientManager + API.kt（注入依赖，删 moon/pixshaft）"
```

---

### Task 12: CLI 闸门（协程 suspend 经 QUIC）

**Files:**
- Modify: `app/src/main/kotlin/ceui/pixiv/poc/PocMain.kt` → 重写为 `AppMain.kt`（或保留 PocMain 名），用 `Client` 而非裸 QuicInterceptor。

**Interfaces:**
- Consumes: `Client`（Task 11）、JVM 默认实现（Task 3）。
- Produces: `./gradlew :app:run` 用 `runBlocking { Client(...).appApi.getWalkthroughWorks() }` 经 QUIC 拿 200 + illusts，打印首张 id/title。**Plan 2 闸门。**

- [ ] **Step 1: 重写 main**

```kotlin
package ceui.pixiv.poc

import ceui.pixiv.net.api.Client
import ceui.pixiv.net.impl.*
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val client = Client(
        settings = InMemorySettings(isDirectConnect = true),
        tokenStore = FileTokenStore(java.nio.file.Path.of(System.getProperty("user.home"),
            "Library/Application Support/PixivShaft/token.json")),
        refresher = StubTokenRefresher(),
        lang = DefaultLanguageProvider(),
        logger = StdoutLogger
    )
    val resp = client.appApi.getWalkthroughWorks()
    println("HTTP via QUIC — illusts count = ${resp.illusts?.size ?: 0}")
    resp.illusts?.firstOrNull()?.let { println("first illust id=${it.id} title=${it.title}") }
    println("PLAN 2 GATE PASSED")
    client.close()  // 关 QuicInterceptor event-loop
}
```

> 注：`getWalkthroughWorks()` 返回 `IllustResponse`（:models）；字段名 `illusts`/`id`/`title` 以 Models.kt 为准，编译时按实际修正。

- [ ] **Step 2: 跑闸门**

```bash
cd ~/workspace/git_repo/Pixiv-Shaft-Desktop
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:run --no-daemon
```
Expected:
```
HTTP via QUIC — illusts count = 112
first illust id=... title=...
PLAN 2 GATE PASSED
```

- [ ] **Step 3: commit**

```bash
git add -A && git commit -m "feat(app): Plan 2 闸门 — 协程 suspend 经 QUIC 调 getWalkthroughWorks"
```

---

## Self-Review

- **Spec 覆盖**：覆盖 design v2 §1（模块结构：:models/:net/:store/:app）、§2.1（QuicInterceptor 实战化）、§2.2（HeaderInterceptor/TokenFetcherInterceptor 搬运 + 400 刷新纠偏）、§2.3（HttpDns 搬运）、§2.4（ImageHostManager 搬运，图片 OkHttpClient 完整实装留 Plan 3）、§2.5（TokenRefresher 抽象 + Stub，真刷新留 Plan 4）、§2.6（QUIC POC 已通过，本 plan 沿用 SNI fix）。§3-§7 留后续 plan。✓
- **占位符**：Port 任务（4/6/7/8/9/11）以"源文件即权威 spec + strip 清单"为完整指令，非占位；新代码（2/3/10/12）含完整代码。第三个测试的 suspend 处理有注。无 TBD。✓
- **类型一致**：`TokenStore.getBearerToken()`/`isLoggedIn`、`PixivConstants.*`、`HeaderInterceptor(tokenStore, lang)`/`TokenFetcherInterceptor(tokenStore, refresher)`、`Client(settings, tokenStore, refresher, lang, logger)` 跨 task 一致。`Client.close()` 需 Task 11 实现（调 QuicInterceptor.close()）。✗ 需在 Task 11 补 `fun close()`——已隐含在 Task 10 的 `NettyQuicInterceptor.close()`，Task 11 Client 须暴露 `close()` 委托。
- **范围**：12 task，单 plan 可执行；连接池/真 token 刷新/图片 OkHttpClient/UI 显式 defer 到 Plan 3/4。
- **风险**：Task 4 models 搬运可能有未预期的 Android 引用（Params.java 等），需编译迭代修；Task 11 Client 改 object→class 涉及调用点（仅 :app main），可控。
