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
 * 加载顺序：
 * 1. 系统属性 `ech.library.path`（开发模式由 Gradle 传入绝对路径）
 * 2. `System.loadLibrary("ech")`（打包后 dylib 位于 jpackage runtime 的 lib/）
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
        try {
            // 1) 显式路径（Gradle -Declibrary.path）
            val explicit = System.getProperty("ech.library.path")
            if (explicit != null) {
                System.load(explicit)
                loaded = true
            } else {
                // 2) macOS app bundle：java.home = PixivShaft.app/Contents/runtime/Contents/Home
                //    → ../../../Resources/libech.dylib（packageDmg 的 appResourcesRootDir 落点）
                val bundleLib = File(System.getProperty("java.home"))
                    .resolve("../../../Resources/libech.dylib")
                if (bundleLib.isFile) {
                    System.load(bundleLib.absolutePath)
                    loaded = true
                } else {
                    // 3) 开发模式：从 user.dir 向上找 cargo 产物（不依赖 Gradle 传参机制）
                    var dir: File? = File(System.getProperty("user.dir"))
                    while (dir != null && !loaded) {
                        val candidate = File(dir, "rust/ech/target/release/libech.dylib")
                        if (candidate.isFile) {
                            System.load(candidate.absolutePath)
                            loaded = true
                        }
                        dir = dir.parentFile
                    }
                    // 4) java.library.path（jpackage runtime 的 lib/ 等）
                    if (!loaded) System.loadLibrary("ech")
                }
            }
        } catch (e: UnsatisfiedLinkError) {
            System.err.println("[EchClient] native library unavailable: ${e.message}")
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
        request.body?.contentType()?.let { ct ->
            headerPairs.add("content-type\u0001${ct}")
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
        } catch (e: Throwable) {
            throw IOException("ECH native call failed: ${e.message}", e)
        }
        val resp = gson.fromJson<EchResponse>(json, responseType)
            ?: throw IOException("ECH malformed response: ${json.take(120)}")
        resp.error?.let { throw IOException("ECH error: $it") }
        if (resp.status <= 0) throw IOException("ECH bad status ${resp.status}")

        val respHeaders = Headers.Builder().apply {
            resp.headers?.forEach { (name, value) -> add(name, value) }
        }.build()
        val bodyBytes = resp.body
            ?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
            ?: ByteArray(0)
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
