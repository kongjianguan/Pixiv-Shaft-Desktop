package ceui.pixiv.store

import java.util.prefs.Preferences

class PreferencesKv(private val prefs: Preferences) : KvStore {
    override fun getString(key: String): String? = prefs.get(key, null)
    override fun putString(key: String, value: String) = prefs.put(key, value)
    override fun remove(key: String) = prefs.remove(key)

    companion object {
        fun forApp(): PreferencesKv = PreferencesKv(Preferences.userRoot().node("PixivShaft"))
    }
}
