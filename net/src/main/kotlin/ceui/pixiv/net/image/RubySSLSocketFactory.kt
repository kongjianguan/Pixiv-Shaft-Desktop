package ceui.pixiv.net.image

import java.io.IOException
import java.net.Socket
import java.security.KeyManagementException
import java.security.NoSuchAlgorithmException
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager

class RubySSLSocketFactory : SSLSocketFactory() {
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
