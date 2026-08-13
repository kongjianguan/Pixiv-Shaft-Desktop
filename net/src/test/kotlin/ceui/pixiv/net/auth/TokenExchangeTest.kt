package ceui.pixiv.net.auth

import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Test

class TokenExchangeTest {

    /** 拦截请求捕获 form body，返回固定 JSON，不发真实网络 */
    private fun fakeClient(
        responseJson: String = """{"access_token":"at","refresh_token":"rt","expires_in":3600}""",
        statusCode: Int = 200,
        onBody: (FormBody) -> Unit,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            onBody(chain.request().body as FormBody)
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(statusCode)
                .message("OK")
                .body(responseJson.toResponseBody("application/json".toMediaType()))
                .build()
        }
        .build()

    private fun formParams(body: FormBody): Map<String, String> =
        (0 until body.size).associate { body.name(it) to body.value(it) }

    @Test
    fun `exchangeCode sends client credentials grant code and verifier`() = runBlocking {
        var params = emptyMap<String, String>()
        val exchange = TokenExchange(fakeClient(onBody = { params = formParams(it) }))

        exchange.exchangeCode(code = "auth-code", codeVerifier = "verifier-abc")

        assertEquals(PixivOAuthConfig.CLIENT_ID, params["client_id"])
        assertEquals(PixivOAuthConfig.CLIENT_SECRET, params["client_secret"])
        assertEquals("authorization_code", params["grant_type"])
        assertEquals("auth-code", params["code"])
        assertEquals("verifier-abc", params["code_verifier"])
        assertEquals(PixivOAuthConfig.REDIRECT_URI, params["redirect_uri"])
        assertEquals("true", params["include_policy"])
    }

    @Test
    fun `refreshToken sends refresh grant without code fields`() = runBlocking {
        var params = emptyMap<String, String>()
        val exchange = TokenExchange(fakeClient(onBody = { params = formParams(it) }))

        exchange.refreshToken("refresh-token-1")

        assertEquals("refresh_token", params["grant_type"])
        assertEquals("refresh-token-1", params["refresh_token"])
        assertEquals("true", params["include_policy"])
        assertNull(params["code"])
        assertNull(params["code_verifier"])
    }

    @Test
    fun `successful exchange parses token response`() = runBlocking {
        val exchange = TokenExchange(fakeClient(onBody = {}))

        val resp = exchange.exchangeCode("c", "v")

        assertEquals("at", resp.accessToken)
        assertEquals("rt", resp.refreshToken)
        assertEquals(3600, resp.expiresIn)
    }

    @Test
    fun `non-2xx response throws`() {
        val exchange = TokenExchange(fakeClient(statusCode = 400, responseJson = "bad_request", onBody = {}))

        val error = assertThrows<RuntimeException> {
            runBlocking { exchange.exchangeCode("c", "v") }
        }
        assertTrue(error.message!!.contains("400"))
    }
}
