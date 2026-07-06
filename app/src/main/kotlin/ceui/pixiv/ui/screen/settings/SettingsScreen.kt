package ceui.pixiv.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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

class SettingsScreen : Screen {

    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { SettingsScreenModel() }
        val restartRequired by screenModel.restartRequired.collectAsState()
        var customHost by remember { mutableStateOf(screenModel.customImageHost) }
        var currentHostMode by remember { mutableStateOf(screenModel.imageHostMode) }

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Settings", style = MaterialTheme.typography.headlineMedium)

            if (restartRequired) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Restart required — network settings changed",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            // Direct connect
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Direct Connect (QUIC)", style = MaterialTheme.typography.bodyLarge)
                    Text("Bypass GFW via QUIC + no-SNI TLS", style = MaterialTheme.typography.labelSmall)
                }
                Switch(
                    checked = screenModel.isDirectConnect,
                    onCheckedChange = screenModel::setDirectConnect
                )
            }

            // Secure DNS
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Secure DNS (DoH)", style = MaterialTheme.typography.bodyLarge)
                    Text("DNS-over-HTTPS for pximg resolution", style = MaterialTheme.typography.labelSmall)
                }
                Switch(
                    checked = screenModel.isUseSecureDns,
                    onCheckedChange = screenModel::setUseSecureDns
                )
            }

            // Image host
            Text("Image Host", style = MaterialTheme.typography.bodyLarge)
            val hostModes = listOf("Pixiv" to 0, "pixiv.cat" to 1, "pixiv.re" to 2, "pixiv.nl" to 3, "Custom" to 4)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(hostModes) { (label, mode) ->
                    FilterChip(
                        selected = currentHostMode == mode,
                        onClick = {
                            currentHostMode = mode
                            screenModel.setImageHostMode(mode)
                        },
                        label = { Text(label) }
                    )
                }
            }

            if (currentHostMode == 4) {
                OutlinedTextField(
                    value = customHost,
                    onValueChange = {
                        customHost = it
                        screenModel.setCustomImageHost(it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("https://your.proxy.com") },
                    singleLine = true
                )
            }

            // Logout
            Button(
                onClick = screenModel::logout,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Logout")
            }
        }
    }
}
