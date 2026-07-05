package ceui.pixiv.net.auth

import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

data class PkcePair(val verifier: String, val challenge: String)

object PkceUtils {
    fun generate(): PkcePair {
        val random = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(random)
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        return PkcePair(verifier, challenge)
    }

    fun buildAuthUrl(challenge: String): String {
        val params = mapOf(
            "client_id" to PixivOAuthConfig.CLIENT_ID,
            "redirect_uri" to PixivOAuthConfig.REDIRECT_URI,
            "response_type" to "code",
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "client" to PixivOAuthConfig.CLIENT_PARAM,
        )
        val query = params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        return "${PixivOAuthConfig.LOGIN_URL}?$query"
    }
}
