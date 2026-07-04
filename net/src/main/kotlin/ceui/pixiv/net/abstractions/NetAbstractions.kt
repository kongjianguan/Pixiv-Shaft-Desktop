package ceui.pixiv.net.abstractions

interface Settings {
    val isDirectConnect: Boolean
    val isUseSecureDns: Boolean
    val imageHostMode: Int
    val customImageHost: String
}

interface TokenStore {
    val isLoggedIn: Boolean
    fun getAccessToken(): String?
    fun getBearerToken(): String? = getAccessToken()?.let { "Bearer $it" }
    fun getRefreshToken(): String?
    fun saveTokens(accessToken: String?, refreshToken: String?, userJson: String? = null)
    fun clear()
}

interface TokenRefresher {
    suspend fun refreshAccessToken(currentAccessToken: String?): String?
}

interface Logger {
    fun d(msg: String)
    fun w(msg: String, t: Throwable? = null)
    fun e(msg: String, t: Throwable? = null)
}

interface LanguageProvider {
    fun acceptLanguage(): String          // Accept-Language
    fun appAcceptLanguage(): String       // App-Accept-Language
}
