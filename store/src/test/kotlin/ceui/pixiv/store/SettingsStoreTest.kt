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
        assertEquals("partial_match_for_tags", s.searchIllustTarget)
        assertEquals("partial_match_for_tags", s.searchNovelTarget)
    }

    @Test fun `search targets persist and reject unknown values`() {
        val kv = PreferencesKv(java.util.prefs.Preferences.userRoot().node("test-${System.nanoTime()}"))
        val s = SettingsStore(kv)

        s.setSearchIllustTarget("title_and_caption")
        s.setSearchNovelTarget("keyword")
        assertEquals("title_and_caption", s.searchIllustTarget)
        assertEquals("keyword", s.searchNovelTarget)

        s.setSearchIllustTarget("unknown")
        assertEquals("partial_match_for_tags", s.searchIllustTarget)
    }
}
