# 网络层迁移评估：Kotlin → Rust（Rust + Flutter 方案）

评估范围：`net/src/main/kotlin/ceui/pixiv/net/`（1728 行）+ `rust/ech/`（334 行）。
评估日期：2026-09-23。
评估环境：中国大陆网络（实测 `curl https://cloudflare.com/cdn-cgi/trace` 返回 `loc=CN`），macOS arm64，rustc 1.96.0。

## 结论摘要

| 模块 | Kotlin 现状 | Rust 预估 | 验证状态 | 判定 |
|------|------------|----------|---------|------|
| 无 SNI TLS（图片 CDN 反墙核心） | 53 行 | 58 行 | **已实测通过** | 能 |
| ECH 直连 | 327 行（含 JNI 封装） | 204 行 | **已实测通过**（含内联版） | 能，且胶水可全删 |
| QUIC / HTTP/3 | 201 行 | 约 170 行 | **已实测通过** | 能 |
| DoH 解析 | 199 行 | 约 80 行 | 端点连通性已实测 | 能，需换 AliDNS |
| 拦截器（头注入 / token 刷新） | 145 行 | 约 105 行【假设】 | 未实际编译 | 能 |
| OAuth PKCE + 回调 server | 176 行 | 约 140 行【假设】 | 未实际编译 | 能 |
| 图片缓存（内存 LRU / 磁盘 / 去重 / 取消） | Coil 免费提供 | 180-270 行【假设】 | 未实际实现 | 能，但要写代码 |

**咽喉点判定：`Rust 不发送 SNI` 成立。** 字节级证据在 [EVIDENCE-sni.md](verify/EVIDENCE-sni.md)，重构方案可以继续进行。

三条主要链路（无 SNI / ECH / QUIC）都已在真实网络环境下跑通，不是纸面推断。详见第 1、2、3 节的实测输出。

---

## 1. 无 SNI TLS —— 反墙核心【已实测】

这是整个方案的技术前提。ui-scout 已确认 Dart 侧无法禁用 SNI（dart-lang/sdk#44122 至今 open），所以这条必须靠 Rust。

### 1.1 现状

`RubySSLSocketFactory.kt` 只有 43 行，核心是 [RubySSLSocketFactory.kt:36](../../net/src/main/kotlin/ceui/pixiv/net/image/RubySSLSocketFactory.kt#L36)：把 hostname 传 `null` 调 `delegate.createSocket(socket, null, port, autoClose)`，Java TLS 就不在 ClientHello 里带 SNI。配合 `TrustAllCertManager`（10 行，三个空方法）放行证书校验。

### 1.2 Rust 实现

rustls 0.23 提供 `ClientConfig::enable_sni: bool` 字段（`src/client/client_conn.rs:213`，仓库已有依赖 `rustls = "0.23"`，实测版本 0.23.43）。置 `false` 时，`src/client/hs.rs:260-271` 的 `exts.server_name` 走 `(None, false) => None` 分支，SNI 扩展彻底不进 ClientHello。

无 SNI 后证书无法按域名校验，必须配自定义 `ServerCertVerifier`（等价 `TrustAllCertManager`），实现见 [accept_all.rs](verify/sni-verify/src/accept_all.rs)（58 行）。

### 1.3 字节级验证结果

用本地 TLS 服务 + 抓包代理，dump 实际 ClientHello 并手工解析扩展列表（解析器见 [parse_clienthello.py](verify/parse_clienthello.py)）。同一域名 `localtest.me`，只改 `enable_sni`：

| 配置 | ClientHello 字节数 | 扩展数量 | 扩展类型列表 | SNI(type=0) |
|------|-------------------|---------|-------------|------------|
| `enable_sni=true`（对照） | 239 | 10 | `[45,23,13,10,5,**0**,51,35,11,43]` | 出现，携带 `localtest.me` |
| `enable_sni=false` | 218 | 9 | `[45,11,35,51,10,13,43,5,23]` | **不存在** |

对照组证明检测方法有效（能查出 SNI 时一定会查出来），目标组 SNI 确实消失。**验证结论：rustls 能做到完全不发送 SNI。**

### 1.4 真实 CDN 端到端验证

用 `enable_sni=false` + 信任任意证书，直连 `HttpDns` 里硬编码的 pximg IP：

| 用例 | 结果 |
|------|------|
| Rust 连 `210.140.139.134:443` 取 `/robots.txt` | **HTTP/1.1 200 OK**，nginx，281 字节，`Content-Length: 75` |
| Rust 连 `210.140.139.134:443` 取 `/crossdomain.xml` | **HTTP/1.1 200 OK**，`Content-Type: text/xml`，581 字节 |
| 同一 IP **发** SNI（OpenSSL 对照） | `ConnectionResetError: [Errno 54] Connection reset by peer` —— GFW 重置 |
| 同一 IP **不发** SNI（OpenSSL 对照） | 握手成功，TLSv1.3，HTTP 404 |

这组对照是关键：同一个 IP，发 SNI 被重置、不发 SNI 正常返回，证明「无 SNI」在当前网络环境下确实是有效手段，并且 Rust 实现拿到了与 JVM 实现相同的结果。

### 1.5 代码量对比

| 项 | Kotlin | Rust | 说明 |
|----|--------|------|------|
| 无 SNI socket 工厂 | 43 行 | 0 行（配置项 `config.enable_sni = false`） | Rust 是配置开关，Kotlin 要覆写 5 个 `createSocket` 重载 |
| 信任所有证书 | 10 行 | 58 行（[accept_all.rs](verify/sni-verify/src/accept_all.rs)） | Rust 的 `ServerCertVerifier` trait 有 4 个方法要写 |
| 合计 | 53 行 | 58 行 | 基本持平 |

### 1.6 风险

- **GFW 行为会变。** 无 SNI 依赖「服务端按 IP 选证书」。pximg 的 nginx 接受，Cloudflare 不接受（见第 3 节）。若 pximg 未来迁到 Cloudflare 或强制 SNI，这条链路立刻失效，届时必须改走 ECH。
- **信任任意证书意味着放弃 MITM 防护。** 与现状一致（现状也是 `TrustAllCertManager`），不引入新风险。
- **硬编码 IP 会变。** `HttpDns` 里的 `210.140.139.x` 是写死的，实测三个都通，但长期仍需 DoH 兜底。

### 1.7 需要的 crate

`rustls = "0.23"`（最新 0.23.45，2026-09-14 发布，活跃维护，累计下载 9.35 亿）。仓库 `rust/ech/Cargo.toml` 已经在用，无需新增。

---

## 2. ECH 直连【已实测，且可内联】

### 2.1 现状

`EchClient.kt` 165 行是 JNI 封装，`rust/ech/` 334 行是 Rust 实现（`ech.rs` 204 + `lib.rs` 130）。构建产物 `prebuilt/libech.dylib` 7.8MB。

`EchClient.kt` 里有四层降级逻辑（[EchClient.kt:43-81](../../net/src/main/kotlin/ceui/pixiv/net/ech/EchClient.kt#L43)）：依次尝试系统属性路径、app bundle 路径、从 `user.dir` 向上遍历找 cargo 产物、最后 `System.loadLibrary`。这段存在的原因写在注释里：打包产物会把构建机绝对路径嵌进 JVM 参数，其他机器上路径不存在，所以每一步失败都必须继续。

### 2.2 实测结果

**A. 现有 dylib 通过真实 JNI 可用。** 用 JDK 21 编译一个类名严格为 `ceui.pixiv.net.ech.EchClient` 的探针（JNI 符号名由类名推导），加载 prebuilt dylib：

```
加载 dylib: libech.dylib (7793200 字节)
nativeInit() -> true
--- GET https://app-api.pixiv.net/v1/illust/ranking?mode=day&date=2026-09-01
  {"status":400,"headers":[["date","Wed, 23 Sep 2026 15:54:33 GMT"],... ["server","cloudflare"], ...
--- GET https://www.pixiv.net/ajax/top/illust?mode=all
  {"status":400,"headers":[...,["server","cloudflare"],...
```

HTTP 400 是预期的（没带 token，Pixiv 返回 OAuth 错误），关键是拿到了真实的 Cloudflare 响应头，说明 ECH 链路在中国大陆网络下确实通。

**B. 内联版（去掉 JNI）同样可用。** 把 `rust/ech/src/ech.rs` 原样复制为普通模块，main 直接调 `ech::request()`（[ech-inline/src/main.rs](verify/ech-inline/src/main.rs)，42 行驱动代码）：

```
=== ensure_client()：拉取 ECH 配置并建立连接池 ===
客户端就绪
--- GET https://app-api.pixiv.net/v1/illust/ranking?mode=day&date=2026-09-01
  HTTP 状态: 400
    server: cloudflare
    alt-svc: h3=":443"; ma=86400
  响应体字节数: 191
```

**C. 顺带确认：Pixiv 通过 `alt-svc: h3=":443"; ma=86400` 声明支持 HTTP/3。**

### 2.3 判定：能，且 JNI 层可整体删除

如果整个网络层都在 Rust 里，`EchClient.kt` 165 行全部删除，`rust/ech/src/lib.rs` 130 行 JNI 胶水全部删除，只保留 `ech.rs` 204 行作为普通模块直接函数调用。

省掉的不仅是行数：

| 被删除的负担 | 现状代价 |
|-------------|---------|
| `EchClient.available` 四层路径探测 | 165 行里的绝大部分，且打包后在别的机器上有静默失效风险（注释已写明） |
| JNI 字符串转换 | `nativeRequest` 把 header 拼成 `"name\u0001value"` 字符串数组，Rust 侧再 `split_once('\u{1}')` 拆回来 |
| 响应体序列化往返 | Rust → JSON 字符串 → base64 body → Kotlin Gson 解析 → base64 解码 → `byte[]`。图片字节全部走这条路，两侧各一次 base64 编解码 |
| `catch_unwind` 包装 | 存在的原因：panic 跨 JNI 边界是未定义行为，会导致整个 JVM 终止 |
| dylib 加载与分发 | 7.8MB 产物，且被迫开 `debug = true` 才能被 dlopen（Cargo.toml 注释记录了这个 macOS linker bug） |

结论：ECH 那 204 行逻辑不需要改，直接内联成函数调用。**这是本次迁移收益最确定的一块。**

### 2.4 风险

- ECH 配置从 `cloudflare-ech.com` 经 AliDNS（`223.5.5.5`）引导。实测 AliDNS 通（88ms），但如果 AliDNS 挂了，ECH 就退化到 QUIC，QUIC 再失败才真正不可用。
- ECH 的 `ECH_IPS`（`104.18.10.118` / `104.18.11.118`）是硬编码的 Cloudflare anycast IP，实测 TCP 443 通。

---

## 3. QUIC / HTTP/3【已实测】

### 3.1 现状

`NettyQuicInterceptor.kt` 201 行，把 OkHttp 请求转成 Netty QUIC 的 HTTP/3 请求。关键技巧在 [NettyQuicInterceptor.kt:59-75](../../net/src/main/kotlin/ceui/pixiv/net/NettyQuicInterceptor.kt#L59)：用 `InetAddress.getByAddress(host, ipBytes)` 把主机名附到 IP 上，这样连接目标是 CF IP 但 SNI 仍是真实域名；并且必须显式调 `sslEngineProvider` 走带 `peerHost` 的 `newEngine` 重载，否则默认不发 SNI，Cloudflare 直接丢弃握手。

**注意这里与图片链路相反：API 走 Cloudflare，必须发 SNI。** 实测印证：对 `104.18.42.239` 和 `172.64.145.17` 不发 SNI，两边都是 `SSLV3_ALERT_HANDSHAKE_FAILURE`。所以两条链路的 TLS 参数不同，不能共用一套配置。

### 3.2 Rust 实测

用 `quinn` + `h3` + `h3-quinn` 对 `104.18.42.239:443` 发 HTTP/3 请求（[h3-verify/src/main.rs](verify/h3-verify/src/main.rs)，142 行）：

```
QUIC 104.18.42.239:443 server_name=app-api.pixiv.net (发 SNI)
  QUIC 连接建立: 104.18.42.239:443
  HTTP/3 响应状态: 400 Bad Request
    server: cloudflare
    alt-svc: h3=":443"; ma=86400
    cf-ray: a3fabfaa98da7d2a-HKG
  响应体字节数: 191
```

QUIC 握手、HTTP/3 请求、响应头、响应体全部正常，从中国大陆直连成功。

### 3.3 判定与 crate

**能。** 用 `quinn = "0.11"`（最新 0.11.12，2026-09-14，活跃维护，3.17 亿下载）+ `h3 = "0.0.8"` + `h3-quinn = "0.0.10"`。

选 `quinn` 不选 `s2n-quic`：前者下载量是后者两个数量级（3.17 亿 vs 73 万），社区例程多；后者是 AWS 官方 crate，更新更勤（2026-09-22）但生态小。

### 3.4 代码量

| 项 | Kotlin | Rust |
|----|--------|------|
| QUIC 拦截器 | 201 行 | 约 150-180 行 |

Rust 侧不写 OkHttp 拦截器适配（不用再手动补 `Content-Type`、不用处理 `Protocol.HTTP_2` 伪装），但要自己管 HTTP/3 流生命周期，量级接近。

### 3.5 风险

- `h3` crate 版本仍是 `0.0.8`，**最后发布 2025-05-06，已 16 个月未更新**。API 在 0.0.x 阶段会变（本次验证就撞上 `read_response`→`recv_response`、`read_data`→`recv_data` 的重命名）。这是 QUIC 链路最大的维护隐患。
- `h3` 0.0.8 与 `quinn` 0.11 的版本耦合较紧，升级任一方都要重新验证。
- QUIC 需要驱动 connection future 才能推进流，写法比 OkHttp 拦截器绕。

---

## 4. DoH 解析【能，但现状两个端点都不通】

### 4.1 现状

`HttpDns.kt` 139 行 + `CloudFlareDNSService.kt` 50 行。配了两个端点：`https://1.0.0.1/` 和 `https://185.222.222.222/`，都只查 `app-api.pixiv.net` 和 `oauth.secure.pixiv.net` 两个域名（[HttpDns.kt:22-25](../../net/src/main/kotlin/ceui/pixiv/net/dns/HttpDns.kt#L22)）。解析不到就退回硬编码 IP：API 走 `PixivHosts.CF_IPS`，图片走 `210.140.139.x`。

### 4.2 实测：两个端点都不通

| 端点 | 结果 |
|------|------|
| `https://1.0.0.1/dns-query` | 超时（8s 无响应） |
| `https://185.222.222.222/dns-query` | 超时（8s 无响应） |
| `https://cloudflare-dns.com/dns-query` | 000（0.2s 失败） |
| `https://223.5.5.5/resolve`（AliDNS，ECH 已在用） | **200，89ms** |

`run.log` 里的真实记录与实测一致，且频繁出现：

```
[D] HttpDns DoH failed for app-api.pixiv.net: Connect timed out
[D] HttpDns all DoH failed for app-api.pixiv.net, will use fallback IPs
[D] HttpDns lookup i.pximg.net → fallback-image [/210.140.139.134, /210.140.139.133, /210.140.139.131] [0 ms]
```

**也就是说：当前 DoH 功能在实际运行中从未生效，一直靠硬编码 IP 兜底。** 迁移时这是一个可以顺手修掉的既有问题——把端点换成 AliDNS（`223.5.5.5/resolve`，项目里 ECH 模块已在用，实测 89ms）。

### 4.3 Rust 实现

两种做法：

1. **直接用 reqwest 打 DoH JSON 接口**（推荐）。项目里已有 `reqwest = "0.13.4"`，`CloudFlareDNSResponse` 的 Gson 模型换成 serde 结构体即可，约 60 行。AliDNS 的 `/resolve` 返回格式与 Cloudflare `/dns-query` 的 JSON 格式一致（都有 `Answer[].data`），现有解析逻辑几乎不用改。
2. 用 `hickory-resolver = "0.26"`（最新 0.26.3，2026-09-10，活跃维护）的 DoH 支持。功能更全（支持 DoH3、DNSSEC），但引入较大依赖，对本项目是过度设计。

**推荐第 1 种**，与现有代码结构一一对应，且不加新依赖。

### 4.4 代码量

| 项 | Kotlin | Rust |
|----|--------|------|
| DoH 查询 + 缓存 + 回退 | 189 行 | 约 70-90 行 |

Rust 侧省掉 Retrofit 那层接口声明（`CloudFlareDNSService` 的注解式定义 + `enqueue` 回调链），直接用 reqwest 的同步/异步调用，回退逻辑用 `Option` 表达更紧凑。

### 4.5 风险

- DoH 端点本身可能被墙（实测两个都已被墙）。必须保留硬编码 IP 兜底，否则 DoH 一挂就全站不可用。
- AliDNS 是国内服务，如果用户网络在境外，AliDNS 可能不通。需要多端点 + 快速失败。

---

## 5. 拦截器：头注入 / token 刷新【能，代码量属推断】

### 5.1 现状

- `HeaderInterceptor.kt` 48 行：注入 iOS 人设头（UA / app-os / app-version / x-client-time / x-client-hash）。
- `RequestNonce.kt` 34 行：`x-client-hash` = MD5(时间 + 硬编码 secret)，Java 的 `SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZZZZZ")`。
- `TokenFetcherInterceptor.kt` 45 行：响应码 400 且 body 含特定错误串时，刷新 token 并重放请求。
- `WebHeaderInterceptor.kt` 18 行：匿名 web 请求头。

### 5.2 Rust 实现

**能。** 本节未实际编译验证，结论基于 API 形态判断，代码量为估算。**【假设】**

拦截器在 Rust 侧没有 OkHttp 的 `Interceptor` 抽象可用，但有两条干净的路：

1. 用 `reqwest` 的 middleware 或直接在请求构造处显式加头。reqwest 0.13 有 `RequestBuilder` 链式调用，头注入就是 `.header(name, value)`，比 OkHttp 拦截器更直接（不需要 Interceptor 类、不需要 `chain.proceed`）。
2. token 刷新用 `reqwest_middleware`（若需要统一重试策略）或手写一层封装函数。

**MD5 部分要注意**：需要 `md5` crate（`md5 = "0.7"`）。时间格式要与 Java 的 `SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZZZZZ")` 输出保持一致。**实测该格式串的输出是 `2026-09-24T00:09:46+0800`（末尾时区不带冒号，共 24 字符）**，所以 `chrono` 侧用 `%Y-%m-%dT%H:%M:%S%z` 即可，不要写成 `%:z`。这个值会参与 MD5 计算，格式串必须与现状逐字节一致，否则 Pixiv 会拒绝 `x-client-hash`。（此处「chrono 用 `%z`」为【假设】，未实际编译 chrono 验证；Java 侧输出已实测。）

### 5.3 代码量

| 项 | Kotlin | Rust |
|----|--------|------|
| 头注入 | 48 行 | 约 30 行 |
| nonce / MD5 | 34 行 | 约 20 行（含 chrono 格式核对） |
| token 刷新 | 45 行 | 约 45 行（逻辑一样） |
| web 头 | 18 行 | 约 10 行 |
| 合计 | 145 行 | 约 105 行 |

### 5.4 风险

- **时间戳格式必须与现状逐字节一致。** 实测 Java 的 `ZZZZZ` 输出 `2026-09-24T00:09:46+0800`（**不带冒号**，24 字符）。这个值参与 MD5，`x-client-hash` 会算错导致 API 拒绝。迁移后要有单元测试锁死这个格式。
- token 刷新要处理并发：多个请求同时 401 时会重复刷新。现状用 `runBlocking` + OkHttp 串行化，Rust 侧需要用 `tokio::sync::Mutex` 或 OnceCell 保证只刷新一次。

---

## 6. OAuth PKCE + 回调 server【能，代码量属推断】

### 6.1 现状

`auth/` 共 176 行：`PkceUtils.kt` 33 行（32 字节随机 → base64url → SHA256 → base64url challenge）、`OAuthCallbackServer.kt` 43 行（`com.sun.net.httpserver.HttpServer` 监听随机端口，等 `code`）、`TokenExchange.kt` 67 行（form POST 换 token / 刷新）、`RealTokenRefresher.kt` 23 行、`PixivOAuthConfig.kt` 10 行。

### 6.2 Rust 实现

**能。** 本节未实际编译验证，结论基于 API 形态判断，代码量为估算。**【假设】**

- **PKCE**：`rand` 生成 32 字节，`base64` crate 的 `URL_SAFE_NO_PAD`，`sha2` 做 SHA-256。约 25 行。
- **回调 server**：用 `axum = "0.8"`（2026-04-14，482M 下载，极成熟）或更轻的 `tiny_http`。约 40-50 行。注意 macOS 上要处理 App Sandbox 对本端口监听的限制——现状用 JDK 的 `HttpServer` 已经跑通，换成 Rust 后这部分行为不变，风险不新增。
- **Token 交换**：reqwest 发 form POST。约 40 行。

### 6.3 代码量

| 项 | Kotlin | Rust |
|----|--------|------|
| PKCE | 33 行 | 约 25 行 |
| 回调 server | 43 行 | 约 45 行 |
| Token 交换 | 67 行 | 约 40 行 |
| refresher | 23 行 | 约 20 行 |
| 配置常量 | 10 行 | 约 10 行 |
| 合计 | 176 行 | 约 140 行 |

### 6.4 需要的 crate

`axum = "0.8"`、`rand = "0.8"`、`sha2 = "0.10"`、`base64 = "0.22"`（项目已有）。
`oauth2 = "5.0"` crate **不建议用**——Pixiv 的 OAuth 有自己的一套参数（`include_policy=true`、client_id/client_secret 写死），套通用 crate 反而要绕。

### 6.5 风险

- OAuth 回调端口是随机的，Pixiv 的 `redirect_uri` 是写死的 `https://app-api.pixiv.net/web/v1/users/auth/pixiv/callback`，实际靠本地 server 拦截。这个机制与语言无关，迁移风险低。
- token 存 Keychain 的部分不在网络层（在 `:store`），本次不评估。

---

## 7. 图片缓存能力（回应 ui-scout 的提问）

ui-scout 问：Coil 免费给的内存 LRU、磁盘缓存、请求去重合并、取消语义，Rust 生态有没有现成 crate，还是得手写。

我的判断：**四条能力要分开看，不能笼统说「有现成 crate」或「都得手写」。** 下方行数为估算，未实际实现。**【假设】**

| 能力 | Coil 现状 | Rust 生态 | 我的判断 |
|------|----------|----------|---------|
| 内存 LRU 缓存 | Coil 内建 | `moka = "0.12"`（2026-08-09，1.29 亿下载，活跃维护） | **用 crate**，约 10 行配置 |
| 磁盘缓存 | Coil 内建 | 无直接对应；`object_store = "0.14"` 是对象存储抽象（面向 S3/GCS），对本地图片缓存是过度设计；`cacache = "13"` 是 npm 风格内容寻址存储，2024-11 后未更新 | **手写**，约 100-150 行（文件写入 + LRU 清理 + 容量统计） |
| 请求去重合并 | Coil 内建（同 URL 并发请求合并） | 无现成 crate，这是 HTTP 客户端层的语义 | **手写**，约 50-80 行（URL → 共享 Future 的 map） |
| 取消语义 | Coil 内建（滚出屏幕自动取消） | Rust 侧 `tokio` 的 `JoinHandle::abort()` 或 `CancellationToken` | **用 tokio 原语**，约 20-30 行 |

合计约 **180-270 行**，比 ui-scout 估的 300-500 行略少，主要因为内存 LRU 和取消语义能直接拿现成东西（`moka` + tokio），不必手写。

需要说明的一点：磁盘缓存和请求去重这两块在 Rust 生态里确实没有贴切的现成 crate，得自己写。但它们逻辑简单、边界清晰，属于可控工作量，不构成阻塞。

---

## 8. 汇总：代码量对比

| 模块 | Kotlin 现状 | Rust 预估 | 增减 |
|------|------------|----------|------|
| 无 SNI TLS（工厂 43 + 信任证书 10） | 53 行 | 58 行 | +5 |
| ECH（Kotlin 197 + Rust 胶水 130） | 327 行 | 204 行 | **-123** |
| QUIC / HTTP/3 | 201 行 | 170 行 | -31 |
| DoH（139 + 50 + 10） | 199 行 | 80 行 | -119 |
| 拦截器（48 + 45 + 18 + 34） | 145 行 | 105 行 | -40 |
| OAuth（33 + 43 + 67 + 23 + 10） | 176 行 | 140 行 | -36 |
| 图片缓存（Coil 免费给的部分） | 0 行（Coil） | 180-270 行 | **+180~270** |
| **合计** | **1101 行** | **937-1027 行** | **净减少约 70-160 行** |

上表只统计有直接 Rust 对应物的部分。`net/` 模块共 1728 行，另有 757 行不在本次对比内：`api/API.kt` 461 行（Retrofit 接口声明，迁移时改为 Rust API 客户端，属另一块工作量）、`api/Client.kt` 95 行（OkHttp 组装，Rust 侧不需要）、`imagehost/ImageHostManager.kt` 120 行（URL 重写，纯字符串逻辑可平移）、其余 81 行常量与抽象接口。

真正的收益不在行数，在下面三点：

1. **删掉 JNI 边界**：165 行 Kotlin 胶水 + 130 行 Rust 胶水 + base64 双向编解码 + `catch_unwind` 包装 + 7.8MB dylib 分发 + 四层路径探测，全部消失。
2. **删掉 OkHttp 拦截器适配**：不用再手工补 `Content-Type`、不用把 HTTP/3 响应伪装成 `Protocol.HTTP_2`、不用处理 one-shot body 不可重放。
3. **删掉 `InetAddress.getByAddress(host, ipBytes)` 这个技巧**：QUIC 那套「把主机名绑到 IP 上骗 SNI」的做法在 Rust 侧可以直接分开传（连接地址与 SNI 是两个独立参数），不需要绕。

新增成本只有图片缓存那一块（180-270 行），因为 Coil 免费给的能力在 Rust 侧要自己搭。

---

## 9. 风险清单

按严重程度排序：

| 风险 | 影响 | 触发条件 |
|------|------|---------|
| GFW 行为变化 | 无 SNI 链路失效 | pximg 迁到 Cloudflare 或强制 SNI；届时只能改走 ECH |
| `h3` crate 停在 0.0.8（16 个月未更新） | QUIC 链路维护负担 | 升级 quinn 或 h3 时 API 破坏 |
| DoH 端点被墙 | DoH 功能不生效 | 现状已发生（两个端点都超时）；必须靠硬编码 IP 兜底 |
| 时间戳格式与现状不一致 | `x-client-hash` 校验失败，全部 API 401 | chrono 格式串写错（必须用 `%z`；写成 `%:z` 会多出一个冒号） |
| 硬编码 IP 失效 | 图片与 API 全部失效 | pximg 或 CF 换 IP |
| 并发 token 刷新 | 重复刷新、token 互相覆盖 | 多请求同时 401；需要 Mutex 串行化 |

---

## 10. 明确判定

1. **Rust 不发送 SNI：能。** 字节级证据 + 真实 CDN 端到端 200 响应，咽喉点通过，重构方案成立。
2. **ECH 内联：能，且收益明确。** 204 行逻辑原样保留，295 行胶水删除。
3. **QUIC：能。** 实测 HTTP/3 直连成功，但 `h3` crate 维护状态是隐患。
4. **DoH：能，但现状两个端点都不通，需换 AliDNS。** 这是迁移顺手修掉的既有问题。
5. **拦截器 / OAuth：能。** 注意时间戳格式必须与现状逐字节一致。
6. **图片缓存：能，需自写 180-270 行。** 内存 LRU 和取消语义有现成 crate，磁盘缓存和请求去重要手写。

---

## 附录：验证产物

全部在 [verify/](verify/) 目录，可复现：

| 文件 | 用途 |
|------|------|
| [EVIDENCE-sni.md](verify/EVIDENCE-sni.md) | SNI 字节级验证 + CDN 对照的完整输出 |
| [sni-verify/src/main.rs](verify/sni-verify/src/main.rs) | rustls 客户端，可切换 enable_sni |
| [sni-verify/src/accept_all.rs](verify/sni-verify/src/accept_all.rs) | 等价 TrustAllCertManager 的验证器 |
| [sni-verify/src/bin/fetch_pximg.rs](verify/sni-verify/src/bin/fetch_pximg.rs) | 真实 CDN 端到端取内容 |
| [parse_clienthello.py](verify/parse_clienthello.py) | ClientHello 扩展解析器（不依赖第三方库） |
| [ech-inline/src/main.rs](verify/ech-inline/src/main.rs) | 内联 ECH（无 JNI）验证 |
| [jni-probe/src/ceui/pixiv/net/ech/EchClient.java](verify/jni-probe/src/ceui/pixiv/net/ech/EchClient.java) | 真实 JNI 加载 prebuilt dylib |
| [h3-verify/src/main.rs](verify/h3-verify/src/main.rs) | HTTP/3 QUIC 客户端 |
| [crate_report.py](verify/crate_report.py) | crate 版本与维护状态抓取 |
| [tls_probe.py](verify/tls_probe.py) / [probe_paths.py](verify/probe_paths.py) | OpenSSL 对照探针 |

复现方式（需要一个能直连 pximg IP 的网络环境）：

```bash
# 构建
export CARGO_HOME=$PWD/docs/rust-flutter-migration/verify/.cargo-home
cd docs/rust-flutter-migration/verify/sni-verify && cargo build --release

# 字节级 SNI 验证（需先起本地 TLS 服务 + 抓包代理，见 tls_server.py / capture_proxy.py）
./target/release/sni-verify localtest.me <capture_port> false

# 真实 CDN 端到端
./target/release/fetch_pximg 210.140.139.134 i.pximg.net /robots.txt
```

补充说明：验证环境的 `.cargo-home` 是本地 cargo 缓存目录（221MB），因为默认的 `~/.cargo` 在本次会话沙箱下不可写。
