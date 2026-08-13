package ceui.pixiv.net.image

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import javax.net.ssl.SSLSocketFactory

class RubySSLSocketFactoryTest {
    @Test fun `unsupported overloads throw`() {
        val factory = RubySSLSocketFactory()
        assertThrows<UnsupportedOperationException> { factory.createSocket("host", 443) }
    }
}
