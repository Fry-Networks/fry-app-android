package com.frynetworks.fryapp.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.frynetworks.fryapp.BuildConfig

/** Public links surfaced from Settings. Not a fleet/API credential — plain marketing/docs URLs. */
private object FryLinks {
    const val DASHBOARD = "https://dashboard.frynetworks.com"
    // The site's own /docs redirect lands here, so use the canonical target directly.
    const val DOCS = "https://docs.frynetworks.com/docs/"
    // Verified against the live frynetworks.com, which links here; the previous
    // https://frynetworks.com/discord was a placeholder and returns 404.
    const val DISCORD = "https://discord.gg/frynetworks"
}

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val wallet by viewModel.wallet.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            OutlinedTextField(
                value = wallet,
                onValueChange = { viewModel.setWallet(it) },
                label = { Text("Default wallet address") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_wallet")
                    .semantics { contentDescription = "Default wallet address" },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Text("Links", style = MaterialTheme.typography.titleSmall)
            TextButton(
                onClick = { uriHandler.openUri(FryLinks.DASHBOARD) },
                modifier = Modifier
                    .testTag("settings_link_dashboard")
                    .semantics { contentDescription = "Open Fry Dashboard" },
            ) { Text("Fry Dashboard") }
            TextButton(
                onClick = { uriHandler.openUri(FryLinks.DISCORD) },
                modifier = Modifier
                    .testTag("settings_link_discord")
                    .semantics { contentDescription = "Open Fry Networks Discord" },
            ) { Text("Discord") }
            TextButton(
                onClick = { uriHandler.openUri(FryLinks.DOCS) },
                modifier = Modifier
                    .testTag("settings_link_docs")
                    .semantics { contentDescription = "Open Fry Networks docs" },
            ) { Text("Docs") }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Text("About Fry Networks", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "Fry Networks builds decentralized VPN and edge-compute hardware. This " +
                    "app provisions Fry miners over Bluetooth or Wi-Fi and shows the devices " +
                    "you've paired — rewards and claims are handled on the Fry Dashboard.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp).testTag("settings_about_body"),
            )
            Text(
                text = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp).testTag("settings_app_version"),
            )
        }
    }
}
