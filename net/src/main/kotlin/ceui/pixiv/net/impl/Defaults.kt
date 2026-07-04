package ceui.pixiv.net.impl

import ceui.pixiv.net.abstractions.*
import com.google.gson.Gson
import java.nio.file.Path
import kotlin.io.path.*

class InMemorySettings(
    override var isDirectConnect: Boolean = false,
    override var isUseSecureDns: Boolean = false,
    override var imageHostMode: Int = 0,
    override var customImageHost: String = ""
) : Settings

class FileTokenStore(private val path: Path) : TokenStore {
    private val gson = Gson()
    private data class Bag(var access: String? = null, var refresh: String? = null, var user: String? = null)
    private var bag: Bag = load()
    override val isLoggedIn get() = bag.access != null
    override fun getAccessToken() = bag.access
    override fun getRefreshToken() = bag.refresh
    override fun saveTokens(accessToken: String?, refreshToken: String?, userJson: String?) {
        bag = Bag(accessToken, refreshToken ?: bag.refresh, userJson ?: bag.user)
        flush()
    }
    override fun clear() { bag = Bag(); flush() }
    private fun load(): Bag =
        if (path.exists()) try { gson.fromJson(path.readText(), Bag::class.java) ?: Bag() } catch (e: Exception) { Bag() } else Bag()
    private fun flush() { path.parent.createDirectories(); path.writeText(gson.toJson(bag)) }
}

class StubTokenRefresher : TokenRefresher {
    override suspend fun refreshAccessToken(currentAccessToken: String?): String? = null
}

object StdoutLogger : Logger {
    override fun d(msg: String) = println("[D] $msg")
    override fun w(msg: String, t: Throwable?) = println("[W] $msg").also { t?.printStackTrace() }
    override fun e(msg: String, t: Throwable?) = println("[E] $msg").also { t?.printStackTrace() }
}

class DefaultLanguageProvider : LanguageProvider {
    override fun acceptLanguage() = "zh"
    override fun appAcceptLanguage() = "zh-Hans"
}
