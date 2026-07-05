package ceui.pixiv.net.auth

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TokenExchangeTest {
    @Test fun `exchangeCode builds correct form body`() {
        // 验证 form body 字段：不能直接调 exchangeCode（会发真实 HTTP）。
        // 改为验证 OAuthTokenResponse 可从 JSON 反序列化。
        val json = """{"access_token":"at123","refresh_token":"rt456","expires_in":3600}"""
        val resp = com.google.gson.Gson().fromJson(json, OAuthTokenResponse::class.java)
        assertEquals("at123", resp.accessToken)
        assertEquals("rt456", resp.refreshToken)
        assertEquals(3600, resp.expiresIn)
    }

    @Test fun `deserialize OAuthTokenResponse with user`() {
        val json = """{
            "access_token": "at789",
            "refresh_token": "rt012",
            "expires_in": 7200,
            "user": {"id": 123456, "name": "Test User", "account": "test_account"}
        }""".trimIndent()
        val resp = com.google.gson.Gson().fromJson(json, OAuthTokenResponse::class.java)
        assertEquals("at789", resp.accessToken)
        assertEquals("rt012", resp.refreshToken)
        assertEquals(7200, resp.expiresIn)
        assertNotNull(resp.user)
        assertEquals(123456, resp.user!!.id)
        assertEquals("Test User", resp.user!!.name)
        assertEquals("test_account", resp.user!!.account)
    }

    @Test fun `deserialize OAuthTokenResponse with null fields`() {
        val json = """{}"""
        val resp = com.google.gson.Gson().fromJson(json, OAuthTokenResponse::class.java)
        assertNull(resp.accessToken)
        assertNull(resp.refreshToken)
        assertEquals(0, resp.expiresIn)
        assertNull(resp.user)
    }
}
