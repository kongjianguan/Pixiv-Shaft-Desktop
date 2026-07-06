package ceui.pixiv.store

import ceui.pixiv.net.abstractions.Settings

class SettingsStore(
    private val kv: KvStore = PreferencesKv.forApp(),
) : Settings {
    override val isDirectConnect: Boolean get() = kv.getBoolean("isDirectConnect", true)
    override val isUseSecureDns: Boolean get() = kv.getBoolean("isUseSecureDns", false)
    override val imageHostMode: Int get() = kv.getInt("imageHostMode", 0)
    override val customImageHost: String get() = kv.getString("customImageHost") ?: ""

    fun setDirectConnect(value: Boolean) {
        kv.putBoolean("isDirectConnect", value)
    }
    fun setUseSecureDns(value: Boolean) {
        kv.putBoolean("isUseSecureDns", value)
    }
    fun setImageHostMode(value: Int) {
        kv.putInt("imageHostMode", value)
    }
    fun setCustomImageHost(value: String) {
        kv.putString("customImageHost", value)
    }
}
