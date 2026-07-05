package ceui.pixiv.store

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

@EnabledOnOs(OS.MAC)
class KeychainTokenStoreTest {
    @Test fun `token roundtrip`() {
        val store = KeychainTokenStore(KeychainKv(service = "PixivShaftTest-${System.nanoTime()}"))
        assertFalse(store.isLoggedIn)
        store.saveTokens("at", "rt", """{"id":1}""")
        assertTrue(store.isLoggedIn)
        assertEquals("at", store.getAccessToken())
        assertEquals("Bearer at", store.getBearerToken())
        assertEquals("rt", store.getRefreshToken())
        store.clear()
        assertFalse(store.isLoggedIn)
    }
}
