package ceui.pixiv.net.image

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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
