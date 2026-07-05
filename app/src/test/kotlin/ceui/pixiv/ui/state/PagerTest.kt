package ceui.pixiv.ui.state

import ceui.loxia.KListShow
import ceui.pixiv.net.abstractions.LanguageProvider
import ceui.pixiv.net.abstractions.Settings
import ceui.pixiv.net.abstractions.TokenRefresher
import ceui.pixiv.net.abstractions.TokenStore
import ceui.pixiv.net.api.Client
import ceui.pixiv.net.impl.StdoutLogger
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.Serializable

data class FakeItem(val id: Long) : Serializable
data class FakeResponse(
    val items: List<FakeItem> = listOf(),
    val next_url: String? = null,
) : Serializable, KListShow<FakeItem> {
    override val displayList: List<FakeItem> get() = items
    override val nextPageUrl: String? get() = next_url
}

class PagerTest {

    private fun fakeClient(): Client {
        return Client(
            object : Settings {
                override val isDirectConnect: Boolean get() = false
                override val isUseSecureDns: Boolean get() = false
                override val imageHostMode: Int get() = 0
                override val customImageHost: String get() = ""
            },
            object : TokenStore {
                override val isLoggedIn: Boolean get() = false
                override fun getAccessToken(): String? = null
                override fun getBearerToken(): String? = null
                override fun getRefreshToken(): String? = null
                override fun saveTokens(accessToken: String?, refreshToken: String?, userJson: String?) {}
                override fun clear() {}
            },
            object : TokenRefresher {
                override suspend fun refreshAccessToken(currentAccessToken: String?): String? = null
            },
            object : LanguageProvider {
                override fun acceptLanguage(): String = "en"
                override fun appAcceptLanguage(): String = "en"
            },
            StdoutLogger
        )
    }

    @Test
    fun `refresh sets items and hasNext`() {
        val pager = Pager<FakeResponse, FakeItem>(fakeClient(), FakeResponse::class.java)
        pager.refresh(FakeResponse(listOf(FakeItem(1), FakeItem(2)), "https://next"))
        assertEquals(2, pager.items.value.size)
        assertTrue(pager.hasNext.value)
    }

    @Test
    fun `refresh with null next_url sets hasNext false`() {
        val pager = Pager<FakeResponse, FakeItem>(fakeClient(), FakeResponse::class.java)
        pager.refresh(FakeResponse(listOf(FakeItem(1)), null))
        assertEquals(1, pager.items.value.size)
        assertFalse(pager.hasNext.value)
    }
}
