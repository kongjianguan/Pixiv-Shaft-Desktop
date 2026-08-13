package ceui.pixiv.net.impl

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class DefaultsTest {
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
}
