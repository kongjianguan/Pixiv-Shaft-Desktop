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
