package ceui.pixiv.store

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SettingsStoreTest {
    @Test fun `defaults`() {
        val kv = PreferencesKv(java.util.prefs.Preferences.userRoot().node("test-${System.nanoTime()}"))
        val s = SettingsStore(kv)
        assertTrue(s.isDirectConnect)
        assertFalse(s.isUseSecureDns)
        assertEquals(0, s.imageHostMode)
        assertEquals("", s.customImageHost)
    }
}
