package com.frynetworks.fryapp.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.frynetworks.fryapp.BuildConfig
import com.frynetworks.fryapp.auth.SessionState
import com.frynetworks.fryapp.ui.common.CopyableText
import com.frynetworks.fryapp.ui.theme.FryCard

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
    val session by viewModel.session.collectAsStateWithLifecycle()
    val account by viewModel.account.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(account.message) {
        val message = account.message ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        viewModel.consumeAccountMessage()
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }) },
        snackbarHost = { SnackbarHost(snackbar, modifier = Modifier.testTag("settings_snackbar")) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            (session as? SessionState.SignedIn)?.let { signedIn ->
                AccountCard(
                    session = signedIn,
                    busy = account.busy,
                    onSignOut = viewModel::signOutAccount,
                    onRebind = viewModel::rebindDevice,
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            }

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

/** Signed-in dashboard account: address (copyable), wallet vendor, sign out, re-bind device. */
@Composable
fun AccountCard(
    session: SessionState.SignedIn,
    busy: Boolean,
    onSignOut: () -> Unit,
    onRebind: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth().testTag("settings_account"), colors = CardDefaults.cardColors(containerColor = FryCard)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Dashboard account", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            CopyableText(
                text = session.profile.address,
                label = "Wallet address",
                textTag = "account_address",
                copyTag = "account_copy_address",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                session.profile.vendor?.let {
                    AssistChip(onClick = {}, label = { Text(it.displayName) }, modifier = Modifier.testTag("account_vendor"))
                }
                AssistChip(
                    onClick = {},
                    label = { Text(if (session.fingerprintBound) "Device bound" else "Device not bound") },
                    modifier = Modifier.testTag("account_fingerprint"),
                )
            }
            session.profile.email?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onRebind,
                    enabled = !busy,
                    modifier = Modifier.weight(1f).testTag("settings_rebind_fingerprint").semantics { contentDescription = "Re-bind device" },
                ) { Text("Re-bind device") }
                OutlinedButton(
                    onClick = onSignOut,
                    enabled = !busy,
                    modifier = Modifier.weight(1f).testTag("settings_signout").semantics { contentDescription = "Sign out" },
                ) { Text("Sign out") }
            }
        }
    }
}
