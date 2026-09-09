package com.frynetworks.fryapp.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.frynetworks.fryapp.data.Device
import com.frynetworks.fryapp.data.Transport
import com.frynetworks.fryapp.ui.theme.FryCard
import com.frynetworks.fryapp.util.MinerType
import com.frynetworks.fryapp.util.isDeviceOnline

private const val MINER_KEY_PREFIX_CHARS = 8
private const val MINER_KEY_SUFFIX_CHARS = 4

@Composable
fun HomeScreen(
    onAddDevice: () -> Unit,
    onDeviceClick: (String) -> Unit,
    onSettings: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val sections by viewModel.sections.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current

    // The claim URL always carries the app-wide default wallet (Settings), not a per-device
    // one — see the task contract: claims are a dashboard concept, not a per-device action.
    val onClaim: () -> Unit = onClaim@{
        val url = viewModel.claimUrlOrNull()
        if (url == null) {
            onSettings()
            return@onClaim
        }
        uriHandler.openUri(url)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Fry devices") },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddDevice,
                modifier = Modifier
                    .testTag("home_add_device_fab")
                    .semantics { contentDescription = "Add device" },
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            if (sections.isEmpty()) {
                EmptyDevicesState(onAddDevice = onAddDevice, modifier = Modifier.fillMaxSize())
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item {
                        Text(
                            text = "Claims are processed on the Fry Dashboard",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    sections.forEach { section ->
                        item(key = "header_${section.minerType.name}") {
                            Text(
                                text = section.minerType.label,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        items(section.devices, key = { it.minerKey }) { device ->
                            DeviceCard(
                                device = device,
                                onClick = { onDeviceClick(device.minerKey) },
                                onClaim = onClaim,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                    }
                    item { Spacer(Modifier.height(72.dp)) }
                }
            }
        }
    }
}

@Composable
private fun EmptyDevicesState(onAddDevice: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Text("No devices yet", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Provision your first Fry miner to see it here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onAddDevice,
                modifier = Modifier
                    .testTag("home_empty_add_device")
                    .semantics { contentDescription = "Add your first device" },
            ) {
                Text("Add your first device")
            }
        }
    }
}

@Composable
private fun DeviceCard(
    device: Device,
    onClick: () -> Unit,
    onClaim: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = FryCard),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = boardIcon(device.transport),
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(device.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = truncateMinerKey(device.minerKey),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("device_card_minerkey_${device.minerKey}"),
                    )
                }
                OnlineStatusChip(device = device)
            }
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                AssistChip(
                    onClick = {},
                    label = { Text(MinerType.fromMinerKey(device.minerKey).label) },
                    modifier = Modifier.testTag("device_card_type_${device.minerKey}"),
                )
                TextButton(
                    onClick = onClaim,
                    modifier = Modifier
                        .testTag("device_card_claim_${device.minerKey}")
                        .semantics { contentDescription = "Claim rewards for ${device.name}" },
                ) {
                    Text("Claim")
                }
            }
        }
    }
}

@Composable
private fun OnlineStatusChip(device: Device) {
    val online = isDeviceOnline(device)
    AssistChip(
        onClick = {},
        label = { Text(if (online) "Online" else "Offline") },
        colors = AssistChipDefaults.assistChipColors(
            labelColor = if (online) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        modifier = Modifier.testTag("device_card_status_${device.minerKey}"),
    )
}

private fun boardIcon(transport: String): ImageVector = when (transport) {
    Transport.BLE -> Icons.Filled.Bluetooth
    Transport.SOFTAP -> Icons.Filled.Wifi
    else -> Icons.Filled.DeveloperBoard
}

private fun truncateMinerKey(minerKey: String): String {
    val visible = MINER_KEY_PREFIX_CHARS + MINER_KEY_SUFFIX_CHARS
    if (minerKey.length <= visible) return minerKey
    return "${minerKey.take(MINER_KEY_PREFIX_CHARS)}…${minerKey.takeLast(MINER_KEY_SUFFIX_CHARS)}"
}
