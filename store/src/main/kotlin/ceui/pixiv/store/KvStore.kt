package ceui.pixiv.store

interface KvStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun getBoolean(key: String, default: Boolean = false): Boolean = getString(key)?.toBoolean() ?: default
    fun getInt(key: String, default: Int = 0): Int = getString(key)?.toIntOrNull() ?: default
    fun putBoolean(key: String, value: Boolean) = putString(key, value.toString())
    fun putInt(key: String, value: Int) = putString(key, value.toString())
    fun remove(key: String)
}
