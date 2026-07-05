package ceui.pixiv.ui.screen.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import ceui.pixiv.ui.navigation.MainScreen

class LoginScreen : Screen {

    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { LoginScreenModel() }
        val state by screenModel.state.collectAsState()
        val navigator = LocalNavigator.currentOrThrow
        var codeInput by remember { mutableStateOf("") }

        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Pixiv Shaft",
                style = MaterialTheme.typography.headlineMedium
            )
            Text(
                text = "Login to access all features",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            when (val s = state) {
                is LoginState.Idle -> {
                    Button(onClick = screenModel::startLogin) {
                        Text("Login with Pixiv")
                    }
                }
                is LoginState.AwaitingCode -> {
                    Text(
                        text = "Browser opened. Authorize, then paste the redirect URL or code here.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    OutlinedButton(onClick = {
                        try {
                            java.awt.Desktop.getDesktop().browse(java.net.URI(s.authUrl))
                        } catch (_: Exception) {}
                    }) {
                        Text("Reopen browser")
                    }
                    OutlinedTextField(
                        value = codeInput,
                        onValueChange = { codeInput = it },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        placeholder = { Text("Paste redirect URL or code") },
                        singleLine = true
                    )
                    Button(
                        onClick = { screenModel.submitCode(codeInput) },
                        enabled = codeInput.isNotBlank()
                    ) {
                        Text("Submit")
                    }
                }
                is LoginState.Exchanging -> {
                    CircularProgressIndicator()
                    Text("Exchanging code for token…", modifier = Modifier.padding(top = 8.dp))
                }
                is LoginState.Success -> {
                    Text("Login successful!", color = MaterialTheme.colorScheme.primary)
                    // Navigator will switch to MainScreen via auth gate in Main.kt
                }
                is LoginState.Error -> {
                    Text(
                        text = s.message,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    Button(onClick = screenModel::startLogin) {
                        Text("Try again")
                    }
                }
            }
        }
    }
}
