package ceui.pixiv.poc

import io.netty.bootstrap.Bootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.nio.NioDatagramChannel
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame
import io.netty.handler.codec.http3.Http3
import io.netty.handler.codec.http3.Http3ClientConnectionHandler
import io.netty.handler.codec.http3.Http3DataFrame
import io.netty.handler.codec.http3.Http3HeadersFrame
import io.netty.handler.codec.http3.Http3RequestStreamInboundHandler
import io.netty.handler.codec.quic.QuicChannel
import io.netty.handler.codec.quic.QuicSslContextBuilder
import io.netty.handler.codec.quic.QuicStreamChannel
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import io.netty.util.ReferenceCountUtil
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class NettyQuicInterceptor : Interceptor {

    private val group = MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!PixivHosts.shouldQuic(request.url.host)) return chain.proceed(request)
        return runQuic(request)
    }

    private fun runQuic(request: Request): Response {
        val host = request.url.host
        val port = request.url.port.takeIf { it != 443 } ?: 443
        val cfIp = PixivHosts.CF_IPS.first()

        // 反墙关键：连 CF IP，但 SNI=app-api.pixiv.net。
        // netty QUIC 的 QuicheQuicSslEngine 从 peerHost 自动设 SNI（须为有效主机名非 IP），
        // 故用 InetAddress.getByAddress(hostname, ipBytes) 把主机名附到 IP 上，解耦 SNI 与连接目标。
        val ipBytes = cfIp.split('.').let { p ->
            byteArrayOf(p[0].toInt().toByte(), p[1].toInt().toByte(),
                         p[2].toInt().toByte(), p[3].toInt().toByte())
        }
        val inetAddr = InetAddress.getByAddress(host, ipBytes)
        val remote = InetSocketAddress(inetAddr, port)

        // trust-all：与源 app TrustAllCertManager 一致（CF anycast IP 不做主机名校验）
        val sslContext = QuicSslContextBuilder.forClient()
            .trustManager(InsecureTrustManagerFactory.INSTANCE)
            .applicationProtocols(*Http3.supportedApplicationProtocols())
            .build()

        val codec = Http3.newQuicClientCodecBuilder()
            .sslContext(sslContext)
            .maxIdleTimeout(30, TimeUnit.SECONDS)
            .initialMaxData(10_000_000)
            .initialMaxStreamDataBidirectionalLocal(1_000_000)
            .build()

        var datagram: Channel? = null
        var quicChannel: QuicChannel? = null
        try {
            datagram = Bootstrap()
                .group(group)
                .channel(NioDatagramChannel::class.java)
                .handler(codec)
                .bind(0).sync().channel()

            quicChannel = QuicChannel.newBootstrap(datagram)
                .handler(Http3ClientConnectionHandler())
                .remoteAddress(remote)
                .connect()
                .get()

            val done = CompletableFuture<Pair<Int, ByteArray>>()
            val bodyBuf = ByteArrayOutputStream()
            val statusHolder = intArrayOf(0)

            val streamChannel: QuicStreamChannel = Http3.newRequestStream(
                quicChannel,
                object : Http3RequestStreamInboundHandler() {
                    override fun channelRead(ctx: ChannelHandlerContext, frame: Http3HeadersFrame) {
                        statusHolder[0] = frame.headers().status()?.toString()?.toIntOrNull() ?: 0
                        ReferenceCountUtil.release(frame)
                    }
                    override fun channelRead(ctx: ChannelHandlerContext, frame: Http3DataFrame) {
                        val b = frame.content()
                        val bytes = ByteArray(b.readableBytes())
                        b.readBytes(bytes)
                        bodyBuf.write(bytes)
                        ReferenceCountUtil.release(frame)
                    }
                    override fun channelInputClosed(ctx: ChannelHandlerContext) {
                        done.complete(statusHolder[0] to bodyBuf.toByteArray())
                        ctx.close()
                    }
                }
            ).sync().getNow()

            val path = request.url.encodedPath +
                (request.url.encodedQuery?.let { "?$it" } ?: "")
            val nonce = RequestNonce.build()
            val headersFrame = DefaultHttp3HeadersFrame()
            val h = headersFrame.headers()
                .method(request.method)
                .path(path)
                .authority(host)
                .scheme("https")
            // 转发原请求头（Authorization 等），跳过 hop-by-hop 与已设/覆盖项
            val skip = setOf(
                "host", "connection", "proxy-connection", "transfer-encoding",
                "keep-alive", "te", "trailer", "content-length",
                "method", "path", "authority", "scheme", "status",
                "user-agent", "app-os", "app-os-version", "app-version",
                "x-client-time", "x-client-hash"
            )
            for (i in 0 until request.headers.size) {
                val name = request.headers.name(i).lowercase()
                if (name !in skip) h.add(name, request.headers.value(i))
            }
            h.add("user-agent", PixivHosts.IOS_UA)
                .add("app-os", PixivHosts.APP_OS)
                .add("app-os-version", PixivHosts.APP_OS_VERSION)
                .add("app-version", PixivHosts.APP_VERSION)
                .add("x-client-time", nonce.xClientTime)
                .add("x-client-hash", nonce.xClientHash)

            streamChannel.writeAndFlush(headersFrame)
                .addListener(QuicStreamChannel.SHUTDOWN_OUTPUT).sync()

            val (status, body) = done.get(30, TimeUnit.SECONDS)
            if (status <= 0) throw java.io.IOException("QUIC response missing status for $host")

            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_2)
                .code(status)
                .message("OK")
                .body(body.toResponseBody("application/json".toMediaTypeOrNull()))
                .build()
        } catch (e: java.io.IOException) {
            throw e
        } catch (e: Exception) {
            throw java.io.IOException("QUIC failure for $host: ${e.message}", e)
        } finally {
            try { quicChannel?.close()?.sync() } catch (_: Exception) {}
            try { datagram?.close()?.sync() } catch (_: Exception) {}
        }
    }
}
