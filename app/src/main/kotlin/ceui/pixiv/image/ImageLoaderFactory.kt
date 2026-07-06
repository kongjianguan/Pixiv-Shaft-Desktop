package ceui.pixiv.image

import ceui.pixiv.net.dns.HttpDns
import ceui.pixiv.net.image.RubySSLSocketFactory
import ceui.pixiv.net.image.TrustAllCertManager
import ceui.pixiv.net.imagehost.ImageHostManager
import ceui.pixiv.net.abstractions.Settings
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import okhttp3.OkHttpClient
import okhttp3.Protocol
// okio Path bridge via CoilFactoryBridge (Kotlin cannot resolve @JvmName companion extensions)
import java.nio.file.Paths
import java.util.Collections
import java.util.concurrent.TimeUnit

object ImageLoaderFactory {
    fun create(settings: Settings): ImageLoader {
        val client = buildImageClient(settings)
        return ImageLoader.Builder(PlatformContext.INSTANCE)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(PlatformContext.INSTANCE, 0.25)
                    .build()
            }
            .diskCache {
                val cacheDir = Paths.get(
                    System.getProperty("user.home"),
                    "Library", "Caches", "PixivShaft", "images"
                ).toFile()
                cacheDir.mkdirs()
                DiskCache.Builder()
                    .directory(CoilFactoryBridge.okioPathFromFile(cacheDir))
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }
            .components {
                add(CoilFactoryBridge.createImageFetcherFactory(client))
            }
            .build()
    }

    fun createImageClient(settings: Settings): OkHttpClient = buildImageClient(settings)

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
