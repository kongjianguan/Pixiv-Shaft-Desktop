package ceui.pixiv.store

/** 测试用内存 KvStore：避免测试把偏好写进真实的 java.util.prefs 节点。 */
class InMemoryKvStore : KvStore {
    private val map = HashMap<String, String>()

    override fun getString(key: String): String? = map[key]

    override fun putString(key: String, value: String) {
        map[key] = value
    }

    override fun remove(key: String) {
        map.remove(key)
    }
}
