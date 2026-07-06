package ceui.pixiv.ui.screen.login

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import ceui.pixiv.di.AppContainer
import ceui.pixiv.net.auth.PkceUtils
import ceui.pixiv.ui.auth.AuthState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import java.net.URI

class LoginScreenModel : ScreenModel {

    private val tokenExchange = AppContainer.tokenExchange
    private val tokenStore = AppContainer.tokenStore

    private val _state = MutableStateFlow<LoginState>(LoginState.Idle)
    val state: StateFlow<LoginState> = _state.asStateFlow()

    private var pkceVerifier: String? = null

    fun startLogin() {
        val (verifier, challenge) = PkceUtils.generate()
        pkceVerifier = verifier
        val authUrl = PkceUtils.buildAuthUrl(challenge)
        _state.value = LoginState.AwaitingCode(authUrl)

        // Open browser
        try {
            java.awt.Desktop.getDesktop().browse(URI(authUrl))
        } catch (e: Exception) {
            // Browser open failed — user can copy URL manually
        }
    }

    fun submitCode(pastedUrlOrCode: String) {
        val verifier = pkceVerifier ?: run {
            _state.value = LoginState.Error("Login session expired. Click 'Login' again.")
            return
        }

        // Extract code from pasted URL or raw code
        val code = extractCode(pastedUrlOrCode)
        if (code.isNullOrBlank()) {
            _state.value = LoginState.Error("Could not extract authorization code. Paste the full redirect URL or the code value.")
            return
        }

        screenModelScope.launch {
            _state.value = LoginState.Exchanging
            try {
                val resp = tokenExchange.exchangeCode(code, verifier)
                if (resp.accessToken != null) {
                    tokenStore.saveTokens(resp.accessToken, resp.refreshToken, null)
                    AppContainer.updateAuthState()
                    _state.value = LoginState.Success
                } else {
                    _state.value = LoginState.Error("Token exchange returned no access_token.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = LoginState.Error(e.message ?: "Token exchange failed.")
            }
        }
    }

    private fun extractCode(input: String): String? {
        val trimmed = input.trim()
        // If it looks like a URL, require code= parameter
        if (trimmed.startsWith("http", ignoreCase = true)) {
            val codeParam = "code="
            val codeIdx = trimmed.indexOf(codeParam, ignoreCase = true)
            if (codeIdx >= 0) {
                val start = codeIdx + codeParam.length
                val end = trimmed.indexOf('&', start).let { if (it < 0) trimmed.length else it }
                return trimmed.substring(start, end)
            }
            // URL without code= — user pasted the wrong redirect URL
            return null
        }
        // Not a URL: treat as raw code
        return trimmed.ifBlank { null }
    }
}

sealed class LoginState {
    data object Idle : LoginState()
    data class AwaitingCode(val authUrl: String) : LoginState()
    data object Exchanging : LoginState()
    data object Success : LoginState()
    data class Error(val message: String) : LoginState()
}
