package ceui.pixiv.store

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PreferencesKvTest {
    @Test fun `string roundtrip`() {
        val kv = PreferencesKv(java.util.prefs.Preferences.userRoot().node("test-${System.nanoTime()}"))
        assertNull(kv.getString("x"))
        kv.putString("x", "hello")
        assertEquals("hello", kv.getString("x"))
        kv.remove("x")
        assertNull(kv.getString("x"))
    }
    @Test fun `boolean and int`() {
        val kv = PreferencesKv(java.util.prefs.Preferences.userRoot().node("test-${System.nanoTime()}"))
        assertFalse(kv.getBoolean("b"))
        kv.putBoolean("b", true)
        assertTrue(kv.getBoolean("b"))
        assertEquals(0, kv.getInt("i"))
        kv.putInt("i", 42)
        assertEquals(42, kv.getInt("i"))
    }
}
