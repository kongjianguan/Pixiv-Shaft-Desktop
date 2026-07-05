package ceui.pixiv.ui.auth

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AuthStateTest {

    @Test
    fun `LoggedOut is object`() {
        assertInstanceOf(AuthState.LoggedOut::class.java, AuthState.LoggedOut)
    }

    @Test
    fun `LoggedIn holds userId`() {
        val state = AuthState.LoggedIn(userId = 12345L)
        assertEquals(12345L, state.userId)
    }

    @Test
    fun `LoggedIn with null userId`() {
        val state = AuthState.LoggedIn()
        assertNull(state.userId)
    }
}
