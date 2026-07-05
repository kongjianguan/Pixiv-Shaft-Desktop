package ceui.pixiv.store

class KeychainKv(
    private val service: String = "PixivShaft",
    private val account: String = System.getProperty("user.name"),
) : KvStore {

    override fun getString(key: String): String? = try {
        val proc = ProcessBuilder("security", "find-generic-password",
            "-a", account, "-s", "$service:$key", "-w")
            .redirectErrorStream(true).start()
        val out = proc.inputStream.readBytes().decodeToString().trim()
        proc.waitFor()
        if (proc.exitValue() == 0 && out.isNotEmpty()) out else null
    } catch (e: Exception) { null }

    override fun putString(key: String, value: String) {
        val proc = ProcessBuilder("security", "add-generic-password",
            "-a", account, "-s", "$service:$key", "-w", value, "-U")
            .redirectErrorStream(true).start()
        proc.waitFor()
    }

    override fun remove(key: String) {
        val proc = ProcessBuilder("security", "delete-generic-password",
            "-a", account, "-s", "$service:$key")
            .redirectErrorStream(true).start()
        proc.waitFor()
    }
}
