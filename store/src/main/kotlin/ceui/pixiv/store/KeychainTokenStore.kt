package ceui.pixiv.store

import ceui.pixiv.net.abstractions.TokenStore

class KeychainTokenStore(
    private val keychain: KeychainKv = KeychainKv(),
) : TokenStore {
    private companion object {
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_USER = "user_json"
    }

    override val isLoggedIn: Boolean get() = keychain.getString(KEY_ACCESS) != null
    override fun getAccessToken(): String? = keychain.getString(KEY_ACCESS)
    override fun getRefreshToken(): String? = keychain.getString(KEY_REFRESH)
    override fun saveTokens(accessToken: String?, refreshToken: String?, userJson: String?) {
        if (accessToken != null) keychain.putString(KEY_ACCESS, accessToken)
        if (refreshToken != null) keychain.putString(KEY_REFRESH, refreshToken)
        if (userJson != null) keychain.putString(KEY_USER, userJson)
    }
    override fun clear() {
        keychain.remove(KEY_ACCESS); keychain.remove(KEY_REFRESH); keychain.remove(KEY_USER)
    }
    fun getUserJson(): String? = keychain.getString(KEY_USER)
}
