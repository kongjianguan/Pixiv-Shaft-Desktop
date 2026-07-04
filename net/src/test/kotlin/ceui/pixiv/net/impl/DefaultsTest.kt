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

    @Test fun `stub refresher returns null`() =
        assertNull(kotlinx.coroutines.runBlocking { StubTokenRefresher().refreshAccessToken("x") })

    @Test fun `default language is zh`() {
        assertEquals("zh", DefaultLanguageProvider().acceptLanguage())
    }
}
