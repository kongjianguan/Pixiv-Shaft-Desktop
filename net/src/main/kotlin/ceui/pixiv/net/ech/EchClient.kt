package ceui.pixiv.net.ech

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import okhttp3.Headers
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.File
import java.io.IOException
import java.util.Base64

/**
 * JNI 封装：Rust ECH 传输（rust/ech，抄自 PixEz rhttp）。
 *
 * 加载顺序（候选逐个尝试，前一个失败继续下一个）：
 * 1. 系统属性 `ech.library.path`（开发/构建机由 Gradle 传入绝对路径）
 * 2. macOS app bundle：java.home 上溯到 Contents/Resources/libech.dylib
 * 3. 开发模式：从 user.dir 向上找 cargo 产物（aarch64 路径优先，旧路径兜底）
 * 4. `System.loadLibrary("ech")`（java.library.path）
 *
 * 注意：打包产物会把 `-Declibrary.path=<构建机绝对路径>` 嵌入 JVM 参数，
 * 其他机器上该路径不存在，所以第 1 步失败必须继续尝试 bundle 路径，
 * 不能直接放弃（否则 ECH 在打包 App 上静默失效，回退 QUIC）。
 *
 * 库不可用时 [available] 为 false，拦截器自动回退 QUIC，不影响功能。
 */
object EchClient {

    private val gson = Gson()
    private val responseType = object : TypeToken<EchResponse>() {}.type

    /** 原生库是否成功加载。 */
    val available: Boolean

    init {
        var loaded = false
        // 候选路径逐个尝试：不存在的路径先跳过，加载失败继续下一个候选
        // （显式路径加载失败必须继续，否则打包 App 在其他机器上 ECH 静默失效）。
        val candidates = buildList {
            // 1) 显式路径（Gradle -Declibrary.path；打包产物里是构建机绝对路径，其他机器上不存在）
            System.getProperty("ech.library.path")?.let { add(it) }
            // 2) macOS app bundle：java.home = PixivShaft.app/Contents/runtime/Contents/Home
            //    → ../../../Resources/libech.dylib（createDistributable 的落点）
            add(
                File(System.getProperty("java.home"))
                    .resolve("../../../Resources/libech.dylib").absolutePath,
            )
            // 3) 开发模式：从 user.dir 向上找 dylib（不依赖 Gradle 传参机制）。
            //    prebuilt 为提交入库的产物；target/ 两个路径兜底手动重编后的构建。
            var dir: File? = File(System.getProperty("user.dir"))
            while (dir != null) {
                add(File(dir, "rust/ech/prebuilt/libech.dylib").absolutePath)
                add(File(dir, "rust/ech/target/aarch64-apple-darwin/release/libech.dylib").absolutePath)
                add(File(dir, "rust/ech/target/release/libech.dylib").absolutePath)
                dir = dir.parentFile
            }
        }
        for (candidate in candidates) {
            if (!File(candidate).isFile) continue
            try {
                System.load(candidate)
                loaded = true
                break
            } catch (e: UnsatisfiedLinkError) {
                System.err.println("[EchClient] failed to load $candidate: ${e.message}")
            }
        }
        // 4) java.library.path（jpackage runtime 的 lib/ 等）
        if (!loaded) {
            try {
                System.loadLibrary("ech")
                loaded = true
            } catch (e: UnsatisfiedLinkError) {
                System.err.println("[EchClient] native library unavailable: ${e.message}")
            }
        }
        available = loaded
    }

    private external fun nativeInit(): Boolean

    private external fun nativeRequest(
        method: String,
        url: String,
        headers: Array<String>,
        body: ByteArray?,
    ): String

    private data class EchResponse(
        val status: Int = 0,
        val headers: List<List<String>>? = null,
        val body: String? = null,
        val error: String? = null,
    )

    /** 预热：拉取 ECH 配置并构建连接池（失败不缓存，下次请求自动重试）。 */
    fun warmUp() {
        if (available) {
            try {
                nativeInit()
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * 通过 ECH 链路执行请求。任何失败抛 [IOException]，
     * 由 [EchInterceptor] 决定回退 QUIC。
     */
    fun execute(request: Request): Response {
        if (!available) throw IOException("ECH library not loaded")
        // FormBody 的 Content-Type 由 OkHttp BridgeInterceptor 添加（在 application
        // interceptor 之后运行），这里必须手动补上，否则 Rust 侧发出的 POST 没有
        // content-type，服务器无法解析 form body（OAuth 报 invalid_client）。
        val headerPairs = mutableListOf<String>()
        val existing = request.headers.names().mapTo(mutableSetOf()) { it.lowercase() }
        // FormBody 的 Content-Type 由 OkHttp BridgeInterceptor 添加（在 application
        // interceptor 之后运行），这里必须手动补上，否则 Rust 侧发出的 POST 没有
        // content-type，服务器无法解析 form body（OAuth 报 invalid_client）。
        // 请求头已显式带 content-type 时不重复添加。
        request.body?.contentType()?.let { ct ->
            if ("content-type" !in existing) headerPairs.add("content-type\u0001${ct}")
        }
        request.headers.forEach { (name, value) ->
            headerPairs.add("$name\u0001$value")
        }
        val body = request.body?.let { rb ->
            if (rb.isOneShot()) throw IOException("one-shot body not replayable via ECH")
            val sink = Buffer()
            rb.writeTo(sink)
            sink.readByteArray()
        }
        val json = try {
            nativeRequest(request.method, request.url.toString(), headerPairs.toTypedArray(), body)
        } catch (e: Exception) {
            throw IOException("ECH native call failed: ${e.message}", e)
        }
        val resp = gson.fromJson<EchResponse>(json, responseType)
            ?: throw IOException("ECH malformed response: ${json.take(120)}")
        resp.error?.let { throw IOException("ECH error: $it") }
        if (resp.status <= 0) throw IOException("ECH bad status ${resp.status}")

        val respHeaders = Headers.Builder().apply {
            resp.headers?.forEach { (name, value) -> add(name, value) }
        }.build()
        val bodyBytes = resp.body?.let { encoded ->
            try {
                Base64.getDecoder().decode(encoded)
            } catch (e: IllegalArgumentException) {
                throw IOException("ECH response body is not valid base64", e)
            }
        } ?: ByteArray(0)
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_2)
            .code(resp.status)
            .message("OK")
            .headers(respHeaders)
            .body(bodyBytes.toResponseBody())
            .build()
    }

    private fun ByteArray.toResponseBody(): ResponseBody = toResponseBody(null)
}
