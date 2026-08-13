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
        assertFalse(s.isShowR18)
        assertEquals(
            System.getProperty("user.home") + "/Pictures/PixivShaft",
            s.downloadRootPath,
        )
        assertEquals("Illusts/{author}/{title} {id}{page}", s.illustFileNameTemplate)
        assertEquals("Ugoira/{author}/{title} {id}", s.ugoiraFileNameTemplate)
        assertEquals("Novels/{series}/{series_order}_{title}_{id}", s.novelFileNameTemplate)
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

    @Test fun `download settings persist and blank values fall back to defaults`() {
        val kv = PreferencesKv(java.util.prefs.Preferences.userRoot().node("test-${System.nanoTime()}"))
        val s = SettingsStore(kv)

        s.setIsShowR18(true)
        assertTrue(s.isShowR18)

        s.setDownloadRootPath("  /tmp/downloads  ")
        assertEquals("/tmp/downloads", s.downloadRootPath)
        s.setDownloadRootPath("   ")
        assertEquals(
            System.getProperty("user.home") + "/Pictures/PixivShaft",
            s.downloadRootPath,
        )

        s.setIllustFileNameTemplate("Custom/{title}")
        assertEquals("Custom/{title}", s.illustFileNameTemplate)
        s.setIllustFileNameTemplate("  ")
        assertEquals("Illusts/{author}/{title} {id}{page}", s.illustFileNameTemplate)

        s.setNovelFileNameTemplate("Novels/{id}")
        assertEquals("Novels/{id}", s.novelFileNameTemplate)
        s.setNovelFileNameTemplate("")
        assertEquals("Novels/{series}/{series_order}_{title}_{id}", s.novelFileNameTemplate)

        s.setUgoiraFileNameTemplate("Gif/{id}")
        assertEquals("Gif/{id}", s.ugoiraFileNameTemplate)
        s.setUgoiraFileNameTemplate(" ")
        assertEquals("Ugoira/{author}/{title} {id}", s.ugoiraFileNameTemplate)
    }
}
