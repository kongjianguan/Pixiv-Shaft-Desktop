package ceui.pixiv.net.auth

import com.google.gson.annotations.SerializedName
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class OAuthTokenResponse(
    @SerializedName("access_token") val accessToken: String?,
    @SerializedName("refresh_token") val refreshToken: String?,
    @SerializedName("expires_in") val expiresIn: Long = 0,
    @SerializedName("user") val user: OAuthUser? = null,
)

data class OAuthUser(
    @SerializedName("id") val id: Long = 0,
    @SerializedName("name") val name: String? = null,
    @SerializedName("account") val account: String? = null,
)

open class TokenExchange(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    open suspend fun exchangeCode(code: String, codeVerifier: String): OAuthTokenResponse {
        val body = FormBody.Builder()
            .add("client_id", PixivOAuthConfig.CLIENT_ID)
            .add("client_secret", PixivOAuthConfig.CLIENT_SECRET)
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("code_verifier", codeVerifier)
            .add("redirect_uri", PixivOAuthConfig.REDIRECT_URI)
            .add("include_policy", "true")
            .build()
        return postToken(body)
    }

    open suspend fun refreshToken(refreshToken: String): OAuthTokenResponse {
        val body = FormBody.Builder()
            .add("client_id", PixivOAuthConfig.CLIENT_ID)
            .add("client_secret", PixivOAuthConfig.CLIENT_SECRET)
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("include_policy", "true")
            .build()
        return postToken(body)
    }

    private suspend fun postToken(body: FormBody): OAuthTokenResponse {
        val request = Request.Builder()
            .url(PixivOAuthConfig.TOKEN_ENDPOINT)
            .post(body)
            .build()
        return kotlinx.coroutines.Dispatchers.IO.let { ctx ->
            kotlinx.coroutines.withContext(ctx) {
                client.newCall(request).execute().use { resp ->
                    val json = resp.body?.string() ?: ""
                    if (!resp.isSuccessful) throw RuntimeException("OAuth token exchange failed: ${resp.code} $json")
                    com.google.gson.Gson().fromJson(json, OAuthTokenResponse::class.java)
                }
            }
        }
    }
}
