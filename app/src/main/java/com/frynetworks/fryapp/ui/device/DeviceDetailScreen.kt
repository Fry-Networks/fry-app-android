package com.frynetworks.fryapp.ui.device

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.frynetworks.fryapp.auth.SessionState
import com.frynetworks.fryapp.ble.ProvState
import com.frynetworks.fryapp.data.UpdateCheckResult
import com.frynetworks.fryapp.ui.common.SessionViewModel
import com.frynetworks.fryapp.ui.home.ClaimLink

private const val MILLIS_PER_SECOND = 1000L
private const val SECONDS_PER_MINUTE = 60
private const val MINUTES_PER_HOUR = 60
private const val HOURS_PER_DAY = 24

@Composable
fun DeviceDetailScreen(
    onOpenMiners: (String) -> Unit = {},
    viewModel: DeviceDetailViewModel = hiltViewModel(),
    sessionViewModel: SessionViewModel = hiltViewModel(),
) {
    val device by viewModel.device.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    val session by sessionViewModel.state.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current

    Scaffold(topBar = { TopAppBar(title = { Text(device?.name ?: "Device") }) }) { padding ->
        val currentDevice = device
        if (currentDevice == null) {
            Box(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text("Device not found")
            }
        } else {
            Column(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
                Text("Name: ${currentDevice.name}")
                Text(
                    text = "Miner key: ${currentDevice.minerKey}",
                    modifier = Modifier
                        .testTag("device_minerkey")
                        .semantics { contentDescription = "Miner key" },
                )
                Text("Chip: ${currentDevice.chip}")
                Text("Firmware: ${currentDevice.fwVersion}")
                Text("VPN state: ${statusLabel(currentDevice.status)}")
                Text("Last seen: ${formatElapsed(currentDevice.lastSeen)}")

                Spacer(Modifier.height(16.dp))
                Button(onClick = { viewModel.checkForUpdate() }) { Text("Check for update") }
                when (val update = updateState) {
                    is UpdateCheckResult.Available -> Text("Update available: ${update.latestVersion}")
                    is UpdateCheckResult.UpToDate -> Text("Firmware is up to date")
                    is UpdateCheckResult.Unknown -> Text("Update status unknown")
                    null -> {}
                }

                Spacer(Modifier.height(16.dp))
                TextButton(
                    // Was "/rewards?minerKey=..." — that route 404s (shipped broken in v0.1.0
                    // and v0.1.1). ClaimLink.CLAIM_URL is the verified /rewards-claim page,
                    // which takes no query string.
                    onClick = { uriHandler.openUri(ClaimLink.CLAIM_URL) },
                    modifier = Modifier
                        .testTag("device_claim_link")
                        .semantics { contentDescription = "Open rewards dashboard" },
                ) {
                    Text("View rewards on dashboard")
                }
                if (session is SessionState.SignedIn) {
                    TextButton(
                        onClick = { onOpenMiners(currentDevice.minerKey) },
                        modifier = Modifier
                            .testTag("device_open_miners")
                            .semantics { contentDescription = "Open in Miners" },
                    ) {
                        Text("Open in Miners")
                    }
                }
            }
        }
    }
}

private fun statusLabel(status: Int): String {
    val state = runCatching { ProvState.fromCode(status) }.getOrNull() ?: return "Unknown"
    return when (state) {
        ProvState.IDLE -> "Idle"
        ProvState.PROVISIONING -> "Provisioning"
        ProvState.CONNECTING -> "Connecting"
        ProvState.CONNECTED -> "Connected"
        ProvState.ERROR -> "Error"
    }
}

/** Device.lastSeen is the only timestamp this app persists, so "uptime" is shown as elapsed
 *  time since that last observation rather than a live device-reported uptime counter. */
private fun formatElapsed(lastSeenMillis: Long): String {
    val elapsedMs = System.currentTimeMillis() - lastSeenMillis
    if (elapsedMs < 0) return "just now"
    val seconds = elapsedMs / MILLIS_PER_SECOND
    val minutes = seconds / SECONDS_PER_MINUTE
    val hours = minutes / MINUTES_PER_HOUR
    val days = hours / HOURS_PER_DAY
    return when {
        days > 0 -> "${days}d ${hours % HOURS_PER_DAY}h ago"
        hours > 0 -> "${hours}h ${minutes % MINUTES_PER_HOUR}m ago"
        minutes > 0 -> "${minutes}m ago"
        else -> "just now"
    }
}
