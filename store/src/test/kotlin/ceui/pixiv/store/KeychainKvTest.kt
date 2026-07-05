package ceui.pixiv.store

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

@EnabledOnOs(OS.MAC)
class KeychainKvTest {
    @Test fun `string roundtrip`() {
        val kv = KeychainKv(service = "PixivShaftTest-${System.nanoTime()}")
        assertNull(kv.getString("token"))
        kv.putString("token", "abc123")
        assertEquals("abc123", kv.getString("token"))
        kv.remove("token")
        assertNull(kv.getString("token"))
    }
}
